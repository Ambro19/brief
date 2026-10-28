# Changelog

## 2.8.0 - 2026-10-27

**Brief! reaches you.** One milestone, as agreed: a mention now reaches a member's phone or computer even
when Brief! is closed, and "Stay signed in" finally means it, so that tapping the notification opens Brief!
signed in. Until 2.7, every sign-in ended after 12 hours whatever the member chose, and signing out was
remembered only in the server's memory, which a Render restart forgot.

**Read "Before you deploy" below before pushing this to GitHub: one key pair to make, two Render variables.**

Spring Boot edition:

- **Sessions in the database** (migration `V6__sessions_and_push.sql`, `service/Sessions.java`). Every sign-in
  is a row; the login token names it ("sid"), and `JwtAuthenticationFilter` accepts the token only while the
  row exists and has not ended. Signing out deletes the row: permanent, after any restart.
  - `POST /login` with `"stay": true` (the "Stay signed in on this device" box): 30 days from the last use,
    renewed as it is used (written at most once an hour), at most a year in all. Without: 12 hours, as before.
  - `GET /sessions`: where the member is signed in ("Chrome on Windows", "Safari on iPhone"; the label only,
    never the full User-Agent). `DELETE /sessions/{id}`: sign one device out. `POST /sessions/sign-out-others`:
    every device but this one, and every token from before 2.8; this device gets a new token in `x-auth-token`.
  - A new password ("Forgot password?") and deleting the account end every session.
  - At most 20 sessions per member (the oldest go). A session that ends by itself stops counting at once; its
    row is deleted at the next sign-in on the board (at most hourly) or when a notification would go to it.
  - Signing out ends the session the server accepted for the request: an old token that "Sign out everywhere
    else" replaced cannot end the session that replaced it.
  - Tokens issued by 2.7 keep working after the deploy, until they expire (12 hours at most), so nobody is
    signed out by the upgrade. What only a session can do (notifications, "Sign out everywhere else") answers
    409 for them: sign in again.
- **Notifications for mentions** (Web Push: RFC 8030, 8291, 8292), with no new dependency: the encryption
  (`push/WebPushCrypto.java`, ECDH P-256 + HKDF + AES-128-GCM) and the signature (`push/Vapid.java`, ES256) use
  the JDK alone.
  - `GET /push/key`, `POST /push/subscription`, `POST /push/subscription/check`,
    `POST /push/subscription/deletion`, `POST /push/test` (once a minute).
  - A post, or an edit, that **newly** mentions a member sends "<name> mentioned you" and the first 140
    characters of the post to each of their devices, after the post is saved (`afterCommit`). Editing a typo
    alerts nobody twice; your own name alerts nobody; at most 10 notifications per member per 10 minutes.
  - Only a "Stay signed in" session can turn notifications on (409 otherwise), and only a session that has not
    ended receives them; the subscription of an ended session is deleted with it. A subscription belongs to the
    session that made it: signing that device out deletes it (on delete cascade). One the push service reports
    gone (404/410) is deleted.
  - A browser's subscription moves to another member only when the request carries the same auth secret, which
    only that browser knows: knowing someone's endpoint is not enough to take their notifications over.
  - At most 30 members notified per author per 10 minutes, so editing names in and out cannot flood the queue.
    The notifications for a post are prepared on the push thread, after the post is saved.
  - Endpoints are stored in one spelling (host in lower case, no `:443`).
  - Only the push services of Google, Mozilla, Apple and Microsoft are accepted as endpoints, over https, and
    redirects are not followed (`push/PushEndpoints.java`): the server POSTs to that URL, so anything else
    would be a server-side request forgery. A browser key that is not a point on the P-256 curve is refused.
  - Off unless `BRIEF_VAPID_PUBLIC_KEY` and `BRIEF_VAPID_PRIVATE_KEY` are both set. One without the other, or
    a pair that does not match, stops the start with a message saying which.
- `GET /health` reports `sessions` ("kept") and `push` ("on"/"off"). New start-up lines: "Push:" and "Sessions:".

Web app:

- **Notifications** card in Your account (on / off / test / blocked / iPhone: add to Home Screen first / sign
  in with "Stay signed in" first), and an offer on the Mentions page. While notifications are on, the page no
  longer shows its own desktop alert as well (known from the moment of signing in).
- Two tabs: when "Sign out everywhere else" gives one tab a new token, the others take it over instead of
  signing out. Signing out also drops this browser's subscription, so the next person on the device does not inherit it.
- **Where you're signed in** card in Your account: every device, "This device", Sign out, Sign out everywhere else.
- "Stay signed in on this device **for 30 days**".
- Service worker (`public/sw.js`): shows the notification (a newer one replaces the older), and a tap opens
  or focuses Brief! on Mentions. It opens only pages of this site, whatever a notification says.
- Help: Getting started step 6 and three FAQ entries (notifications, iPhone, how long you stay signed in / a
  lost phone). Privacy: two new rows (sign-ins, notifications) and the push services.

Web app files: `index-DHGFgW9f.js` and `index-BMrsFfYN.css` replace `index-wHQMRvRV.js` and `index-DqdTzKJY.css`.

### Before you deploy

1. Make the key pair, on your computer (Node is already installed for the web app):
   `npx web-push generate-vapid-keys`. It prints a Public Key and a Private Key. Keep the private key to
   yourself, like BRIEF_JWT_SECRET: never paste it into a chat, a file or a commit.
2. Render > brief > Environment: add `BRIEF_VAPID_PUBLIC_KEY` and `BRIEF_VAPID_PRIVATE_KEY` with those two
   values, then Save. (Render does not add new render.yaml variables to an existing service by itself.)
3. Push. The log must show `Migrating schema "public" to version "6 - sessions and push"`, then
   `Push: on. ...` and `Sessions: kept in the database. ...`.
4. Keep the pair: replacing it later makes every device turn notifications on again.

Pushing without step 2 is safe: everything else works, and the log says `Push: off`.

**Going back to 2.7 after 2.8 has run:** first change `BRIEF_JWT_SECRET` in Render (everyone signs in again).
2.7 does not know sessions, and would accept every "Stay signed in" token of 2.8 until its date, a year
ahead, including tokens that were signed out.

### Independent review

A separate review of the finished code, before release, found nothing critical. Fixed before release:

- **High:** a device whose 12-hour sign-in had ended (a shared computer, the tab closed without signing out)
  went on receiving the member's mentions, and could not be seen or signed out in "Where you're signed in".
  Now only live sessions receive notifications, the ended one is deleted when found, and notifications need
  "Stay signed in" (which also means a tap opens Brief! signed in).
- **Medium:** "Sign out everywhere else" signed this device's other tabs out, and with them its notifications;
  an old token could end the renewed session. Rolling back to 2.7 would revive signed-out tokens (note above).
  A member who learned another's endpoint could take their notifications over.
- **Low:** the page could alert as well as the notification until Mentions was opened; work after the commit
  could be lost; a notification link could point to another site; an author could fill the queue by editing
  names in and out; endpoints in two spellings; the FAQ did not name the one-year limit.

Checked and fine, among others: the encryption and the VAPID signature against the RFCs, every authorization
path of the new endpoints, the endpoint check against server-side request forgery (user info, trailing dots,
IDN and IPv6 hosts, redirects), transactions and bean wiring under real Spring, the entity types against V6.

### Verification

- 428 tests (277 unit, 151 end to end), all passing on H2 and on PostgreSQL 16. New: `SessionsTest`,
  `SessionsAndPushTest` (a stand-in push service inside the test receives what Brief! sends, and the test
  decrypts it as the browser would), `WebPushCryptoTest`, `VapidTest`, `PushEndpointsTest`,
  `PushNotificationsTest`, `DeviceLabelTest`, and new cases in the filter, token, mention and message tests.
- The encryption produces byte for byte what Mozilla's reference implementation (`http_ece` 1.2.0) produces
  from the same keys and salt (three vectors, in `WebPushCryptoTest`), and `http_ece` decrypts what it produces.
- Deliberate bugs: 42, each breaking one rule of 2.8 (a session not checked, an endpoint allowed too widely,
  a wrong byte in the encryption, a notification sent before the post is saved, ...). The tests caught 41; the
  42nd (marking a session as used on every request instead of once an hour) changes nothing a test can see,
  because the database statement keeps its own once-an-hour condition.
- Browser checks, 27: a stand-in push service verified every VAPID signature and decrypted every notification
  with `http_ece`; the service worker showed the notification; sessions listed, signed out one by one and
  everywhere else; a second tab of the same browser stays signed in; without "Stay signed in" the card explains
  instead of offering; turned off and signed out, nothing more is sent; the iPhone-in-a-tab explanation; phone width.
  The 2.7.1 and 2.7.0 browser checks pass again.
- Migration from a database made by 2.3.0 straight to version 6, on PostgreSQL.

## 2.7.1 - 2026-10-27

**Three small fixes from the first days of 2.7 in production.** Web app only: no server code, no migration,
no new Render variable. Nothing to set up in Resend or Render before pushing.

- **"We couldn't deliver your link" no longer shows "Sent again" under it.** If a member pressed "Send the
  link again" and Resend's bounce report arrived afterwards, the panel turned red but kept the green line
  from the earlier send, which read as a contradiction. Each line under the panel now belongs to the step it
  was written for (add an address / check your email / couldn't deliver) and disappears when the panel
  changes step (`components/EmailStatus.tsx`).
- **"I've opened it" is now "I've opened the link", and it always answers.** It asks the server whether the
  address is confirmed. When it is, the panel closes, as before. When it isn't yet, nothing used to change on
  screen, so the button looked broken; now it says "Not confirmed yet. Open the link in the mail we sent, then
  come back to this page: it updates by itself." It shows "Checking…" while it asks. The page still checks
  by itself whenever the tab comes back into view.
- **Invite people: "Make another link".** After "Create a link", a second link needed the page opened
  again (noted in 2.7.0 for 2.8). A "Make another link" button now sits next to "This link is shown only
  now." Each press makes a new, separate invitation; the earlier ones keep working until used or withdrawn.

Web app files: `index-BLTn_za-.js` and `index-DqdTzKJY.css` replace `index-DTDoO4CX.js` and `index-C6TIDFPY.css`.

## 2.7.0 - 2026-10-23

**Mail you can trust.** On October 21 a new member typed `lovepetschoice@` for `lovepetschoices@`. Gmail
refused the mail, Resend recorded a bounce and blocked the address, and Brief! went on saying "Check your
email" for a mail that would never arrive; "Send the link again" could not help. Brief! sent every mail it
was asked to; what it lacked was any way to learn that one had not arrived. 2.7 gives it one. As agreed, one
milestone: push notifications and longer sessions move to 2.8.

**Read "Before you deploy" below before pushing this to GitHub: one new Render variable, and one webhook
to add in Resend.**

Spring Boot edition:

- **`POST /webhooks/resend`.** Resend reports what happened to a mail after it left. Brief! acts on
  `email.bounced` (permanent bounces only; a temporary one may still arrive) and `email.suppressed`, for mail
  sent from its own domain, and acknowledges everything else, once per message. Each call must be
  signed with the webhook's secret (`BRIEF_RESEND_WEBHOOK_SECRET`, "whsec_...") and be at most five minutes
  old, as Svix, which delivers Resend's webhooks, specifies (`mail/ResendWebhookVerifier.java`, checked
  against the example in Svix's documentation). Without the secret the address answers 404.
- **Migration `V5__email_bounces.sql`:** one row per address that cannot receive mail: the address, what
  happened, the receiving server's reason, the date. Rows are deleted after 30 days, and at once when the
  address is confirmed after all or its account is deleted (`service/EmailBounces.java`).
- **For a day after a bounce** Brief! does not mail that address: `POST /email/verification`,
  `POST /accounts/{id}/email`, `POST /register` and `POST /invitations` answer **422**, and a password link is
  not sent (the answer to the form stays the same, as always). After a day one more try is allowed and clears
  the report; if it bounces again, Resend says so again. For 30 days `GET /me` reports `email_bounced`.
- **Invitations:** the owner's list says `bounced` for an invitation whose mail could not be delivered, and
  `taken` for one whose address is now a member's confirmed address (it can no longer create an account; one address,
  one account). Both can be withdrawn.
- **Confirmation and password links are used up in one database statement** (`AccountTokenRepository.claim`),
  as invitations have been since the review of 2.5.0: two clicks on one password link at the same moment set
  one password. Before, both could.
- The start-up log has a fifth line, `Bounces: ...`, and warns when mail is sent through Resend without the
  webhook secret.

Web app (both editions):

- **"We couldn't deliver your link"** takes the place of "Check your email" when the mail bounced: it names
  the address, says it may be mistyped, and opens the form for another one. "Send the link again" is not
  offered for a refused address. The page notices on its own within 15 seconds, as it already does for a
  confirmation made on another device.
- **Invite people:** "Bounced: check the address" in red, "Already a member", and Withdraw on both. Inviting
  an address that bounced says so. The list is read again on Refresh and every 15 seconds while the page is
  open, so a bounce shows up without leaving the page (until now the list was read once).
- **Create account:** an address that bounced recently is pointed out under the email field.
- **An old confirmation link** opened by a member whose address is already confirmed now says "Your email
  address is already confirmed. There is nothing more to do." (from the live test of October 20).
- **Help Center, FAQ:** "Brief! says it couldn't deliver my link" and "I was invited at an address that already
  has an account"; Getting started asks to type the address carefully.
- **Privacy notice:** a new row for addresses that mail could not be delivered to (kept 30 days), and Resend's
  line says it reports undeliverable mail. **Read the new row before deploying.**
- `docs/openapi.json` describes 2.7.0: 22 paths.

### Before you deploy

1. **In Resend:** Webhooks > Add Webhook. Endpoint URL `https://briefmessage.net/webhooks/resend`. Events:
   `email.bounced` and `email.suppressed`. Save, then copy its **signing secret**
   (it starts with `whsec_`). Never send it to anyone, me included.
2. **In Render** (brief > Environment): add `BRIEF_RESEND_WEBHOOK_SECRET` with that secret, and save. 2.6, still
   running, ignores it. (Render does not add variables from render.yaml to an existing service.)
3. **Push 2.7.0.** The log must show `Migrating schema "public" to version "5 - email bounces"` and the line
   `Bounces: Resend reports them to /webhooks/resend; ...`.
4. **Check the webhook in Resend:** its page shows the deliveries it made and the answer Brief! gave (200).
   Resend can also replay an event from there; a replayed bounce for an address in use shows up on that
   member's page.

If the variable is missing, Brief! learns of no bounce (as in 2.6), the log warns, and the webhook answers 404;
the rest of 2.7 works. A report Resend sends before 2.7 is live is answered 404 and sent again later: nothing is lost.

In your repository, after copying the zip over it: the web app's files are now `index-DTDoO4CX.js` and
`index-C6TIDFPY.css`. From `C:\Users\xiggy\Brief_v2.3.0\brief`:

```powershell
Get-ChildItem backend-spring\src\main\resources\public\assets, backend\src\main\resources\public\assets -Filter "index-*" |
  Where-Object { $_.Name -notin "index-DTDoO4CX.js", "index-C6TIDFPY.css" } | Remove-Item
```

Tests: 358 (was 333). 226 unit, 132 end-to-end. New: `EmailBounceTest` (the whole path from a
signed report to what the member and the owner see), `ResendWebhookVerifierTest`, `EmailBouncesTest`, and
in `StrictAuthTest` four simultaneous uses of one password link.

Verification status at release:
- **Not yet run with real Spring.** Expect `Tests run: 358`.
- Run on the stand-in for Spring: all 358 tests on H2 and on PostgreSQL 16. The signature check agrees with the
  example in Svix's documentation, which was not made with this code.
- 29 deliberate bugs put into the new code after the review (each 422, each status, the timestamp window, the
  signature comparison, the body limit, the replay check, the sender filter, the one-day and 30-day windows,
  the hourly purge, the once-only password link, ...): the tests caught all 29 (22 of 22 in the round before the
  review). The test of four simultaneous uses of one password link failed against the old code each of the
  five times it was run, and passed five times out of five against the new.
- Migration `V5` run on PostgreSQL over a database 2.3.0 had created (V3, V4 and V5 in turn).
- The web app in Chromium, on PostgreSQL with a webhook secret, the script playing Resend with signed reports:
  19 new checks (Mayayo's case end to end: the typo, the report, "We couldn't deliver your link" appearing on its
  own, the same typo refused, the right address, the already-confirmed message; the owner's Bounced and Already
  a member; sign-up with a bounced address; the Help and Privacy pages; a phone screen), and again the 33 checks
  of 2.5, the 17 of 2.6, the 56 of 2.3 and the 10 of a 2.3.0 server.

### Independent review

A separate reviewer read 2.7.0 before release. No critical or high findings; the signature check, the raw body,
the transactions around the new UPDATEs and the 422 placement were found sound. Fixed:

- **No way out of a mistaken report** (medium): an address reported by mistake (suppressed because of another
  sender on the same Resend account, say) would have blocked a member's confirmation and password mail for a
  month, with nobody able to undo it. Now the block lasts a day; after it one more try is allowed.
- **"Deleted after 30 days" was not kept** (medium): old reports were deleted only when a new one arrived. Now at
  most once an hour from the member's page, the owner's list and every report.
- **Spam complaints** blocked mail to the person who complained, including their own password link. No longer
  acted on; Resend suppresses such an address itself.
- **A huge unsigned body** was read into memory before the signature check (low-medium). Now the headers are
  checked first and the body is read only up to 256 KB (413 above).
- **Sender filter** used "contains", and accepted reports without a sender: now the exact domain of the address.
- **Replays** inside the five-minute window: each Svix message id is handled once.
- **"Already a member"** counted an address claimed but not confirmed: now only a confirmed one.
- The already-confirmed notice and the 422 messages were reworded to be right in every case.

Left as they are: 422 at sign-up tells that mail to an address was refused in the last day (it is shown only
after every other check passed); the owner's list makes two look-ups per invitation (fine for a small board);
a race between two first reports can put the address in Hibernate's error log line.

Known limits:
- Brief! learns of a bounce only through Resend's webhook. Mail written to the log (your computer) never
  bounces.
- A mailbox that is full or briefly down gives a temporary bounce, which Brief! ignores; if Resend gives up later,
  it reports nothing more.
- After "Create a link", making a second link still needs the page opened again (as in 2.5). For 2.8.
- Bounces of mail sent before 2.7 are not known (Mayayo's address, for one). Sign in as Mayayo and use
  "Use a different address" once.

## 2.6.0 - 2026-10-21

**A Help Center.** Plain, short pages that say what Brief! is and how to use it, for members and for visitors
who have not been invited yet. One milestone, as agreed: notifications to a closed app and longer sessions
move to 2.7.

Web app (both editions):

- **Help** in the rail, beside Privacy, opens `/help/index.html`: six articles in three groups.
  - **About Brief!:** *What Brief! is* (the 255-character rule and why it works) and *Who Brief! is for*
    (software teams, small businesses, project groups; and what Brief! is not for).
  - **Using Brief!:** *Getting started* (invitation, username and password, confirming the address, posting
    and mentions, signing in) and *Frequently asked questions*.
  - **Plans and billing:** *Pricing*: free during early access; paid plans announced at least 30 days
    ahead, and never charged without the member choosing a plan. *Refund policy*: nothing is charged
    today; terms for paid plans published before the first charge; a mistaken charge is refunded.
- Same look as the Terms and Privacy pages (`legal.css`), light and dark, readable on a phone. Every page
  ends with the support address and the way back.
- The pages describe only what Brief! does today. Private boards per team are named as planned, not as
  available.

Spring Boot edition: no change to the Java code or the API. Pages under `/help/` are public on a closed board,
like the Terms and Privacy pages. `docs/openapi.json` says 2.6.0.

### Before you deploy

Nothing to change in Render: no new variable. Copy the zip over your repository as before (robocopy), delete
the old web app files, test, commit and push. The web app's files are now `index-DcmQyTm0.js` and
`index-DOyME9Bf.css` (the style sheet is unchanged).

Tests: 333 (was 332). 215 unit, 118 end-to-end. New: `ClosedBoardTest.theHelpCenterIsPublicAndEveryArticleItListsExists`
opens the Help Center on a closed board and every article it lists.

Verification status at release:
- **Not yet run with real Spring.** Expect `Tests run: 333`.
- Run on the stand-in for Spring: all 333 tests on H2 and on PostgreSQL 16. Removing one article makes the new
  test fail, as it should.
- The web app in Chromium: 17 new checks (Help in the rail for visitors and members, every article complete,
  the way back, no sideways scrolling on a phone, dark mode, no script errors); the 33 checks of 2.5 (one now
  allows the extra link); the 56 checks of 2.3 (one now expects 2.6.0 in the API docs); and the 10 checks of
  the web app served by a 2.3.0 server.

Known limits:
- The articles are plain HTML files: changing one means a release. A Help Center you can edit without a release
  can come later, if the articles start changing often.
- No search. Six articles do not need one.

## 2.5.0 - 2026-10-16

**Joining becomes professional.** Tested on the live site, 2.4's shared invitation code showed its weakness:
a newcomer had no way to know it and nowhere to get it. 2.5 replaces it with **invitation links** that the
person who runs the board sends, by email or by hand. It also brings what a board needs before strangers
join: **deleting an account**, and **Terms** and **Privacy** pages. The Javalin edition's Java code is
unchanged; it receives the new web app build, which works with it as before.

**Read "Before you deploy" below before pushing this to GitHub.**

Spring Boot edition:

- **Invitations.** Migration `V4__invitations.sql` adds the table `invitation`. The board owner
  (`BRIEF_OWNER_USERNAME`) can:
  - mail one: `POST /invitations` sends `<BRIEF_PUBLIC_URL>/?invite=<secret>` ("AmbroLum invited you to
    Brief!"). The account created with it starts with that address **already confirmed**: no second mail;
  - make a link to pass on by text message or in person: `POST /invitations/link` (shown once). The
    newcomer then gives an address and confirms it as usual;
  - list what was sent (`GET /invitations`: waiting, joined as whom, expired) and withdraw an unused one
    (`DELETE /invitations/{id}`).
  An invitation works once, for 7 days (`BRIEF_INVITATION_DAYS`); inviting the same address again replaces
  the earlier link; at most 50 a day. As with the links of 2.4, only a SHA-256 hash of the secret is stored.
- **`POST /register` takes `"invitation"`** (the secret). On a closed board it is required (403 without, and
  five bad ones from an address lock it out for 15 minutes); with a mailed invitation the address is fixed.
  `POST /invitations/lookup` tells the web app who sent a link and to which address. The 2.4 field
  `invite_code` is ignored.
- **`BRIEF_BOARD=open|closed`** replaces "closed when `BRIEF_INVITE_CODE` is set". A server that still has
  `BRIEF_INVITE_CODE` and no `BRIEF_BOARD` stays **closed**, so the upgrade cannot open the board, and the
  start-up log asks for `BRIEF_BOARD=closed`. A new log line names the owner, and warns when a closed
  board has none, or when the name matches no account.
- **Deleting an account.** `POST /accounts/{id}/deletion` with the password deletes the account and every
  post it wrote (`service/AccountDeletionService.java`). Mentions, mailed links and sent invitations go with
  it through the database's foreign keys; the invitation it joined with, which holds its address, is deleted too. Wrong
  passwords answer 403 (not 401, which the web app reads as an ended session) and count with sign-ins. The
  board owner cannot delete theirs.
- `GET /me` reports `is_owner`.

Web app (both editions):

- **Invitation links** open "AmbroLum invited you to Brief!" with Create account ready: a mailed
  invitation shows its address, fixed and confirmed, so only a username and a password are asked. A used,
  withdrawn or expired link says so.
- **No more code field.** On a closed board without an invitation, Create account explains: "Brief! is
  invitation-only here. To join, ask the person who runs this board to invite you."
- **Invite people** (for the owner): by email, or a link with a Copy button; the list of what was sent,
  with Withdraw.
- **Check your email** (your idea from Resend's sign-up): while a new address waits for confirmation, a
  full step with "I've opened it", "Send the link again" and "Use a different address" takes the place of
  the post box.
- **Your account:** what Brief! keeps about you, and **Delete my account** (tick box and password).
- **Terms** and **Privacy** pages (`terms.html`, `privacy.html`), linked in the rail and on Create account.
  Plain drafts written for OneTechly, LLC; **read them before they go live**, they are not legal advice.
- `docs/openapi.json` describes 2.5.0: 21 paths.

Deployment files and documents:

- `render.yaml`: `BRIEF_BOARD=closed`, `BRIEF_OWNER_USERNAME` (to be set in the dashboard), no more
  `BRIEF_INVITE_CODE`. Its header and the README now say plainly that Render does **not** add a new variable
  to an existing service: the cause of the failed deploy of October 15.

Not in this release, moved to 2.6: push notifications to a closed app, longer sessions. Start-up time is
unchanged (still the free instance's tenth of a processor).

### Before you deploy

In Render, open the service **brief**, **Environment**, and in this order:

1. **Add** `BRIEF_BOARD` = `closed` and `BRIEF_OWNER_USERNAME` = `AmbroLum`. Save. (2.4, still running,
   ignores both.) **Do not delete `BRIEF_INVITE_CODE` yet**: without it, 2.4 would open the board.
2. **Push 2.5.0.** After the deploy the log must show `Board: closed. ...`, `Owner: AmbroLum. ...` and a
   WARN that `BRIEF_INVITE_CODE` is no longer used.
3. **Then delete** `BRIEF_INVITE_CODE` and save. After that restart the WARN is gone.

The owner can be changed later by changing `BRIEF_OWNER_USERNAME`. It must name an existing account: the
name in it can never be registered (see "Independent review"). Links the former owner handed out stop working.

In your repository: the new files come with the zip; nothing to rename this time. The web app's files are now
`index-C5wnKrKa.js` and `index-DOyME9Bf.css`; delete any other `index-*` file in the two `assets` folders.

Tests: 332 (was 303). 215 unit, 117 end-to-end.

### Independent review

Before release, a separate reviewer that had not seen how 2.5 was built read the code against a list:
joining without an invitation, using one twice, inviting or withdrawing as someone else, deleting someone
else's account, the database when an account goes, and whether the Terms and Privacy pages say what the code
does. Found and fixed:

- **High: one link passed on could admit several people.** `markUsed` read the invitation, checked it and
  saved it. Inside a transaction Hibernate answers that read from memory, so registrations sent at the same
  moment all saw "unused" and all joined. Now one `UPDATE ... where used_at is null and expires_at > now`
  (`InvitationRepository.claim`) does the check and the change together; the database lets one through.
  New test: six registrations with one link at the same instant admit one, and leave no other account. It
  failed against the old code every time it was run.
- **Medium: the owner's name could be taken.** With `BRIEF_OWNER_USERNAME` set before that account existed,
  on an open board, whoever registered the name first would have run the board. The name is now never free.
- **Medium: a deleted member's address stayed** in the invitation they joined with, and in the owner's list,
  though the Privacy notice says it goes. That invitation is now deleted with the account.
- **Low:** links from a former owner kept working after `BRIEF_OWNER_USERNAME` changed (now refused);
  invitations nobody used were kept forever (now deleted 30 days after they expire, and the Privacy notice
  says so); the Terms promised account deletion without the owner's exception; Create account blamed the
  username when an invited address had meanwhile found an account; an ended session on Your account said
  "That didn't work" instead of signing out.

Left as they are: an account being deleted while the same member posts at that instant can fail with "try
again" (409) and delete nothing; the Javalin edition serves the Terms page though its server cannot delete
accounts (it answers 404, which the web app explains).

Verification status at release:
- **Not yet run with real Spring.** 2.0.0 to 2.4.0 were written the same way and passed every test on their
  first real run (82, 133, 149, 207, 303). Expect `Tests run: 332`.
- Run on the stand-in for Spring (real JUnit 5, Mockito, Jackson, H2 and PostgreSQL 16 underneath): all 332
  tests, on H2 and again on PostgreSQL, also from a fresh unpack of the zip.
- `V4` run for real: on PostgreSQL 16 over a database 2.4.0 had created and filled, and on an H2 file taken
  from 2.4.0 to 2.5.0; each started a second time (nothing to do).
- The deployment rehearsed on PostgreSQL with Render's variables: first with the dashboard unchanged
  (`BRIEF_INVITE_CODE` still set, no `BRIEF_BOARD`, no owner): the board stayed closed, the old code was
  refused, a member signed in under 2.4 stayed signed in, and the log gave the two warnings. Then with
  `BRIEF_BOARD=closed` and the owner: an invitation mailed through a local stand-in for Resend, with the
  right sender, recipient, subject and a link to https://briefmessage.net; the invitee joined and posted at
  once; neither the key nor any secret appeared in the application's log. **No mail went through Resend.**
- The web app in Chromium: 33 new checks (invite-only page, Invite people, a mailed invitation, a link passed
  on with the Check your email step, withdrawing, the account page and deletion, Terms and Privacy, phone
  widths), the 56 checks written for 2.3 again, and 10 checks of the new web app served by a 2.3.0 server.
- 39 deliberate bugs in the new code: the tests caught 38. The one that slipped through (withdrawing an
  invitation someone else sent) showed a missing test; it is added and catches it.
- Found and fixed on the way: the "Members only" page still spoke of an invitation code; the list of sent
  invitations was indented; a unit test lacked a saved account.
- An independent review; see above. The stand-in now runs `@Transactional` methods in real database
  transactions (commit, or roll back on an exception), so that the test of simultaneous registrations means
  the same there as under Spring. The one `UPDATE` was also tried by hand on PostgreSQL 16 and H2: the second
  of two simultaneous transactions changed 0 rows.

Known limits:
- Only the board owner can invite. Letting members invite, and one owner for several boards, comes with
  private boards.
- A link passed on by hand can be used by whoever holds it, once; withdraw it if it went to the wrong place.
- Account deletion is immediate: there is no grace period and no export of one's posts yet.
- If Render keeps database backups (the paid plan does), deleted data leaves them only as they expire.

## 2.4.0 - 2026-10-11

**Accounts become real** (milestone M3a of the launch roadmap, version 3, section 3). An account has an
email address that is confirmed through a mailed link; a forgotten password is replaced through a mailed
link; a board can be closed, so that only invited people join and only members read. The Javalin edition's
Java code is unchanged; it receives the new web app build, which works with it as before.

**Read "Before you deploy" below before pushing this to GitHub.**

Spring Boot edition:

- **Email address on accounts.** Migration `V3__account_email_and_tokens.sql` adds `account.email` (unique,
  stored in lower case), `email_verified_at`, `token_version`, and the table `account_token`. In strict mode
  `POST /register` requires an `email`; in compat mode it is optional, and the original contract is
  untouched. One address, one account. `GET /accounts` still shows names only: an address is shown to its
  own account and to nobody else (`GET /me`).
- **Confirmation.** A link is mailed to the address: `<BRIEF_PUBLIC_URL>/?verify=<secret>`, good once, for
  24 hours. Until it is used the member can sign in and read, and gets 403 on `POST /messages` and
  `PATCH /messages/{id}`. `POST /email/verification` mails a new link (at most one per minute; the earlier
  link stops working); `POST /accounts/{id}/email` corrects an address that is not confirmed yet.
- **Accounts from before 2.4** have no address. They keep posting. They can add one, and then confirm it
  like everyone else.
- **Password reset.** `POST /password/forgot` answers 200 whatever the address, and mails a link
  (`/?reset=<secret>`, good once, for one hour) only to the confirmed address of an account.
  `POST /password/reset` stores the new password and ends every session of the account.
- **Only a hash of a link's secret is stored** (SHA-256 of 32 random bytes), as with passwords
  (`service/AccountTokens.java`). Opening a link does nothing by itself; the web app sends the secret with a
  POST, so a mail program that fetches links to check them does not use them up.
- **Ending sessions.** A login token now carries the account's "token generation" (claim `ver`), and the
  account row holds the current one (`token_version`). A reset adds one. The filter
  (`security/JwtAuthenticationFilter.java`) accepts a token only if the account still exists and the two
  numbers are equal: one look-up by primary key per request that carries a token. Tokens issued by 2.3
  count as generation 0, so members stay signed in across the upgrade.
- **Sending mail.** `mail/Mailer.java` has two implementations, chosen at start-up (`config/MailConfig.java`):
  `ResendMailer` posts to Resend's HTTPS API when `BRIEF_RESEND_API_KEY` is set (in the background, 20 second
  limit, never failing the member's request, never logging the key or the text of a mail); `LogMailer` writes
  the mail to the log when it is not. New settings: `BRIEF_RESEND_API_KEY`, `BRIEF_MAIL_FROM`,
  `BRIEF_PUBLIC_URL`. Links are built from `BRIEF_PUBLIC_URL`, never from the request's host name.
- **Closed board.** With `BRIEF_INVITE_CODE` set (8+ characters, strict mode only; anything else stops the
  start with a message): `POST /register` needs `"invite_code"` and answers 403 without it, before saying
  anything else about the request; five wrong codes from one address lock that address out for 15 minutes;
  every `GET` and `HEAD` of posts and members answers 401 without a token. The web app, `/health`, the API
  docs and the two mailed links stay public. `GET /health` reports `"board"` and `"email"`.
- **Fix: the rate limiter's clean-up** ran on every attempt once 10,000 keys were tracked, and looked at
  every key each time. It now runs at most once a minute. (Its test took 21 to 26 seconds; the whole suite
  now takes about that.)
- **`@DynamicUpdate` on `Account`:** an UPDATE names only the columns that changed, so confirming an address
  and replacing a password at the same moment cannot undo each other.
- The start-up log gained two lines, `Board: ...` and `Mail: ...`, and warns on Render when mails are only
  being logged.

Deployment files:

- **`render.yaml`: `autoDeployTrigger: checksPass`.** Render deploys a commit only after every job of the
  GitHub workflow has passed for it. Until now every push went live at once.
- `render.yaml` sets `BRIEF_PUBLIC_URL` and `BRIEF_MAIL_FROM`, and names `BRIEF_RESEND_API_KEY` and
  `BRIEF_INVITE_CODE` as secrets to be typed into the dashboard (`sync: false`).
- **Fix: `.gitignore` ignores every `h2` folder** and every `*.mv.db` file. 2.3.0 ignored only the two inside
  the backends, and a database made by starting the jar from the top folder was committed by accident.
- **The web app's folder is now `frontend-spring`** (was `frontend`), next to `backend-spring`. The CI job,
  `.dockerignore`, the build script and the documents follow.

Web app (both editions):

- **Create account** asks for an email address, and on a closed board for the invitation code first, when
  the server says so in `/health`. Against the Javalin edition or an older server the form is as before.
- **A panel above the composer** says when an address is waiting to be confirmed, with "Send the link
  again" and "Use a different address"; the composer is switched off until then. Members from before 2.4
  are asked for an address there and can put it off ("Not now").
- **Forgot password?** on the sign-in form, and a "Choose a new password" form opened by the mailed link.
- **Mailed links**: the secret is taken out of the address bar as soon as the page has read it.
- **Closed board:** a visitor sees "Members only" and a sign-in form, and the page asks the server for no
  posts and no member list. Every read now carries the login token.
- **A session that ended** (expired, or the password was replaced elsewhere) is noticed on the next
  refresh, not at the next attempt to post.
- **Fix: one message for passwords that differ.** The form showed two at once, one under the field and one
  above the button. Now the line under the field is the message. The same goes for every other field: a
  problem is reported under the field it is about, where the cursor is put.
- **"Also from OneTechly: PixelPerfect, a screenshot API for developers."** One line under "API docs", for
  visitors who are not signed in.
- `docs/openapi.json` describes 2.4.0: six new endpoints, 16 paths in all.

Not in this release, moved to 2.5 (see the roadmap): a bot check at registration (the invitation code does
that job on a closed board), deleting an account, Terms and Privacy pages, push notifications, longer
sessions. **Start-up time is unchanged on purpose:** the two minutes measured on Render's free instance are
the processor (a tenth of a core), and the changes that might help (a different JIT setting, an unpacked
jar) could not be measured here; one of them would slow password hashing down. The remedy that is known to
work is the paid instance.

### Before you deploy

`render.yaml` cannot give an existing service its two secrets. In Render, open the service **brief**,
**Environment**, and add them **before** you push 2.4.0:

1. `BRIEF_INVITE_CODE`: a phrase of your own, 8 characters or more. This closes the board.
2. `BRIEF_RESEND_API_KEY`: the key from your Resend account, after `briefmessage.net` is verified there.

Saving them restarts 2.3.0, which ignores both. Then push. After the deploy the log must show
`Board: closed. ...` and `Mail: sent through Resend, from Brief! <hello@briefmessage.net>. ...`.

If the code is missing, 2.4.0 runs as an **open** board, as 2.3.0 does today. If the key is missing, mails
are written to Render's log and nobody receives one: a new member, and any member who adds an address,
then cannot post until the link is taken from the log.

In your copy of the repository, the old `frontend` folder has to go: `git mv frontend frontend-spring`
before unpacking, or `git rm -r frontend` after.

Tests: 303 (was 207). 196 unit, 107 end-to-end.

Verification status at release:
- **Not yet run with real Spring** - the usual limit of the build environment, which cannot download
  Spring, Hibernate or Flyway. 2.0.0 to 2.3.0 were written the same way and passed 82 of 82, 133 of 133,
  149 of 149 and 207 of 207 on the first real run.
- Run on a stand-in for Spring (real JUnit 5, Mockito, Jackson, H2 and PostgreSQL underneath): all 303
  tests, on H2 and again on PostgreSQL 16. The stand-in was first given the unchanged 2.3.0 code: 207 of
  207, the result of the real stack.
- Run for real there: `V3` on H2 and on PostgreSQL 16, on databases that 2.3.0 had created and filled
  (accounts and posts kept, old accounts without an address, a second start finds nothing to do), and on
  an H2 file taken through 2.2.0, 2.3.0 and 2.4.0 in turn.
- The deployment was rehearsed on PostgreSQL with only the variables `render.yaml` sets: once with the two
  secrets missing (the board is open, mails go to the log, the log says so), once with them set (closed
  board, a token issued by 2.3.0 still works, the request to the mail service has the right address, key
  header and fields, and neither the key nor a link appears in the application's log), a redeploy, and two
  mistakes in the dashboard that must stop the start (a key without a sender, a code of five characters).
  The mail service was played by a small server on the same machine. **No mail was sent through Resend.**
- The web app in Chromium: 79 checks of what is new (on a database made by 2.3.0 and upgraded, and on a
  closed board), the 56 checks written for 2.3.0 again, and 10 checks of the 2.4 web app served by a 2.3.0
  server.
- 104 deliberate bugs were put into the new code, one at a time. The first pass caught 96 of 103. Of the
  seven that slipped through, six showed a missing test (now added: an edit while waiting for confirmation,
  the separate counters for codes and password links, single forbidden characters in an address, a changed
  address losing its confirmation, the unique hash of a link) and one showed a check written twice (now
  written once). The run was then repeated in full on the final code: 104 of 104 caught. The unchanged
  code, run three times while the machine was as busy as during that run, passed each time, so a slow
  machine alone does not count as "caught".
- **Not done this time: the review by a second, independent reviewer** that 2.3.0 had. The review of this
  release against the framework's behaviour is my own reading.
- Defects found by the verification above, all fixed in this release:
  - The web app did not send the login token when reading. On a closed board a member who had just signed
    in was shown nothing and signed out again. Found by the browser checks; the API tests could not see it,
    because the API was right.
  - Sessions were first ended by time ("tokens older than the reset are refused"). A login being checked at
    the moment of the reset could get a token that passed. Replaced by the token generation number.
  - `HEAD` requests were not covered by the closed board's rules.
  - Two overlapping changes to one account could undo each other (hence `@DynamicUpdate`).
  - A message at the foot of the taller Create account form was outside the screen.
  - The tests would have used a mail key or an invitation code found in the machine's environment. They
    now set both to empty themselves.
- Unverified until `.\mvnw.cmd test` runs: everything inside real Spring Boot, in particular the new
  Spring Security rules for the closed board, Hibernate `validate` against V3, `@DynamicUpdate`, the three
  new derived queries, the transactions around registering and the links, and the 107 end-to-end tests.
  Expect `Tests run: 303`.
- Unverified until tried: `docker build`; the first real mail through Resend (the sender's domain must be
  verified there first); `autoDeployTrigger: checksPass` on your Render service.

Known limits:
- A confirmed email address cannot be changed yet, and an address cannot be removed.
- An account without a confirmed address cannot recover a forgotten password.
- Someone who registers with another person's address holds that address until it is corrected: the real
  owner cannot register with it meanwhile. On a closed board only invited people can do that.
- On an open board, `POST /register` answers 400 for an address that already has an account, which tells
  the asker that it does. The forgot-password form does not tell.
- A member from before 2.4 who adds an address cannot post until it is confirmed, and cannot take the
  address back out.
- Mentions are not mailed. Rate-limit counts and signed-out tokens are still in one server's memory.

## 2.3.0 - 2026-10-09

**The deployable build** (milestone M1 of the launch roadmap). Until now Brief! ran on one computer with an
H2 file. This version can run on a host: PostgreSQL, versioned migrations, a Docker image, a Render
blueprint. It also brings the web app features asked for on October 8. The Javalin edition's Java code is
unchanged; it receives the new web app build, which works with it as before.

Spring Boot edition:

- **PostgreSQL.** New driver and a `postgres` profile for a local PostgreSQL. On a host that provides a
  `DATABASE_URL` connection string (Render), `config/DatabaseUrl.java` turns it into the JDBC settings
  before Spring starts; nothing else has to be configured.
- **Flyway migrations replace `ddl-auto=update`.** The schema is now created and changed by numbered SQL
  files in `src/main/resources/db/migration`, written in the SQL that H2 and PostgreSQL share. Hibernate is
  set to `validate`: it checks the tables against the entities at start-up and changes nothing.
  - `V1__baseline_schema.sql`: the three tables as 2.2.0 had them.
  - `V2__foreign_keys_and_username_key.sql`: everything below that touches the database.
  - A database made by 2.2 or earlier is accepted as "already at version 1" (`baseline-on-migrate`), and
    V2 is applied to it. **Keep a copy of your `h2` folder before the first start.**
- **Foreign keys.** A post must have an author; a mention must have its post and its member. Mention rows
  are removed with their post by the database itself (`on delete cascade`). New index
  `idx_message_author (posted_by, message_id)` for "posts by one member".
- **Usernames are one name whatever their capitals.** New unique column `account.username_key` (the name
  in lower case). Registering `MAYA` when `maya` exists answers 400; signing in works with any capitals and
  answers with the name as registered. If an old database holds two names that differ only in capitals,
  the older account keeps the name and the newer one signs in as `name#id` (see the comment in V2).
- **Username rule in strict mode:** 3 to 30 letters, digits, dots, dashes or underscores
  (`security/UsernamePolicy.java`). The web app already applied it; the API now does too.
- **Mentions a page at a time.** `GET /accounts/{id}/mentions?limit=20&before=<id>` answers newest first
  with an `X-Has-More` header, exactly like `/messages`. Without parameters the answer is unchanged.
- **The visitor's address behind a proxy.** `security/ClientAddress.java` reads it from the header named
  in `BRIEF_CLIENT_IP_HEADER` (`CF-Connecting-IP` on Render), so the login rate limit counts by visitor,
  not by proxy. `X-Forwarded-For` is deliberately not trusted. An IPv6 address is counted together with
  the rest of its /64 block (`security/AddressGroup.java`), so a new address per guess does not help.
- **The login rate limiter cannot be made to remember without bound:** a key holds at most 64 characters of
  the username, and beyond 50,000 tracked keys it starts again.
- **The web app's files are cached.** `/assets/**` (fingerprinted file names) is served with
  `Cache-Control: max-age=31536000, public, immutable` (`config/WebConfig.java`). `index.html` and every
  API answer stay `no-store`.
- **The character with code 0 is refused** in post text and new usernames (400), and is an ordinary failed
  login (401). H2 would store it; PostgreSQL cannot, and would have answered 409.
- **The log names the database in use** at start-up (`config/StartupReport.java`), and warns if a
  deployment on Render is running on the H2 file.
- `MySQL` stays as a study profile only: Flyway off, tables from Hibernate, never run.

Deployment files:

- `Dockerfile` (two stages: Maven build, then a Java 17 runtime image; not root), `.dockerignore`.
- `render.yaml`: one web service built from the Dockerfile, one PostgreSQL 16 database, a generated
  token-signing key, health check on `/health`.
- `backend-spring/docker-compose.yml`: PostgreSQL for running, PostgreSQL for tests, MySQL.
- CI: the Spring Boot suite also runs against PostgreSQL 16, and the Docker image is built and started.

Web app (both editions):

- **Addresses without `https://` are links.** `pixelperfectapi.net` in a post is clickable. A bare name is
  linked only if it ends in a common ending, so `report.pdf`, `Node.js` and `joe@example.com` are not.
  An address with a user part (`https://bank.example@elsewhere.example`) is never linked.
- **Install Brief! on this device.** A web app manifest, icons and a small service worker
  (`frontend/public/sw.js`). On Chrome, Edge and Android a line under "API docs" opens the browser's install
  dialog; on iPhone and iPad it says which two taps to use. The installed app opens in its own window, shows
  the unread count on its icon where the system supports that, and still opens without a connection (posts
  need the connection). Needs HTTPS or `localhost`.
- **Stay signed in on this device.** A tick box on the sign-in form keeps the session after the tab or the
  app is closed. Off by default in a browser tab, on by default in the installed app.
- **Mentions load 20 at a time**, with "Show older mentions"; the badge reads `20+` when there are more.
- The "available / already taken" line under Username ignores capitals, as the server now does.
- When the server cannot be reached, the message says whether you are offline or the server is starting.

Tests: 207 (was 149). 128 unit, 79 end-to-end.

Verification status at release:
- **Not yet run with real Spring** - the usual limit of the build environment, which cannot download
  Spring, Hibernate or Flyway. 2.0.0, 2.1.0 and 2.2.0 were written the same way and passed 82 of 82,
  133 of 133 and 149 of 149 on the first real run.
- Run for real there: both migration files on **H2 2.2.220 and PostgreSQL 16.15**, for an empty database,
  a database shaped like 2.2 (with duplicate names and orphan rows put in on purpose) and one shaped like
  2.1; the web app in Chromium; the service worker.
- Run on a stand-in for Spring (real JUnit 5, Mockito, Jackson, H2 and PostgreSQL underneath): all 207
  tests, on H2 and again on PostgreSQL 16; an upgrade of a 2.2.0 database file; a start with only the
  variables `render.yaml` sets, and a restart on the same database. First that stand-in was given the
  unchanged 2.2.0 code: 149 of 149, the result of the real stack.
- 62 deliberate bugs were put into the code, one at a time: the tests caught 62 of 62.
- An independent review against the real Flyway, Hibernate, Spring and Spring Security sources found no
  blocker and eleven smaller points; all that concern the code are fixed in this release (IPv6 addresses
  counted by block, a database that is not Brief!'s is never adopted on PostgreSQL, the start-up line
  about the database, a sturdier service worker, the character with code 0, misleading links).
- One defect was found by reading Flyway's source and would have failed on the first real run: a test
  named Flyway's history table without the double quotes H2 needs. Fixed.
- Unverified until `.\mvnw.cmd test` runs: Flyway and Hibernate `validate` inside real Spring Boot; the two
  new derived queries; the 79 end-to-end tests. Expect `Tests run: 207`.
- Unverified until tried: `docker build` (images cannot be pulled there); the deployment on Render; the
  header Render really sends (`render.yaml` assumes `CF-Connecting-IP`; one sign-in with DEBUG logging
  shows it).

Known limits:
- Rate-limit counts and signed-out tokens are still in one server's memory.
- Alerts still need Brief! to be open. Push notifications to a closed app are the next step for the
  installed app and need a small server part.
- A username with a dot that is also a web address (`maya.co`) is drawn as a link, not as a mention.
- The Docker image serves the web app build that is committed in `backend-spring/src/main/resources/public`.

## 2.2.0 - 2026-10-06

From this version on, development continues on the Spring Boot edition only. The Javalin edition stays in
the project, frozen at 1.1.0, as the reference to compare against.

- **Mentions in their own table** (Spring Boot edition). New `mention` table with one row per
  (message, mentioned member), written when a post is created or edited and removed when it is deleted,
  all in the same transaction as the post. `GET /accounts/{id}/mentions` is now a look-up through an
  index instead of a search through every post. Before, the cost of that request grew with the number of
  posts, and the web app sends it every 15 seconds for every signed-in member.
  - New: `entity/Mention.java`, `repository/MentionRepository.java`, `service/MentionBackfill.java`.
  - `MessageService` create, edit and delete are now `@Transactional`.
  - Posts written before 2.2 are indexed automatically the first time the new version starts.
  - Behaviour change: who a post mentions is decided when it is written. A name typed before that member
    registered does not become a mention afterwards.
- **Create account form redesigned** (web app, both editions, desktop and phone): a Confirm password
  field with a live "match" line; a Show / Hide button for the passwords; the username rule shown under
  the field, with "taken" or "available" as you type; the cursor jumps to the field that needs fixing; an
  error message disappears as soon as you change the input. Sign in is unchanged.
- Tests: 149 (was 133). 86 unit, 63 end-to-end.

Verification status at release:
- **Not yet run with real Spring** - the usual limit of the build environment. 2.0.0 and 2.1.0 were written
  the same way and passed 82 of 82 and 133 of 133 on the first real run.
- Checked there: every file compiles against stand-ins; the 86 unit tests pass; 177 simulated requests pass
  through the real filter, URL rules, controllers and services in both modes, with an in-memory mention
  table that enforces the unique constraint; eight deliberate bugs were each caught by the tests; an
  independent review against the real Hibernate, Spring Data and Mockito sources (its findings are fixed:
  case folding done by the database on both sides, a 409 for a constraint collision, a start-up backfill
  that cannot stop the application); the web app passed 58 browser checks (33 for the new form, 25 carried over).
- Unverified until `.\mvnw.cmd test` runs: the new JPQL query, the table and index creation, the
  transactions, and the 63 end-to-end tests. Expect `Tests run: 149`.

Known limits:
- Finding the mentioned members compares the text with every username: one query per post, done by the
  database. Fine for many thousands of members; beyond that, count only names written with `@`.
- `GET /accounts/{id}/mentions` still returns all of a member's mentions at once. It needs paging next.
- Usernames that differ only in capitals ("Maya", "maya") can both register, and both are mentioned by
  either spelling.

## 2.1.0 - 2026-10-05

**Hardening of the Spring Boot edition.** The four items left out of 2.0.0. The Javalin edition's Java
code is unchanged; it receives the new web app build, which works with it as before.

- **Spring Security.** Every request now passes through a security filter chain (`config/SecurityConfig.java`)
  before it reaches a controller. Stateless (no server session), CSRF off (no cookies), Spring's own
  `/logout` handler off so the application's stays in charge, and bare status codes for 401 and 403 so
  error bodies stay empty. In strict mode `POST /messages`, `PATCH /messages/*` and `DELETE /messages/*`
  need a signed-in caller; everything else stays public.
- **JWT.** The login token is now a signed JSON Web Token (HMAC-SHA256) made and checked by
  `security/JwtService.java`, replacing the in-memory `SessionService`. `JwtAuthenticationFilter` reads it
  on each request. Still sent in `X-Auth-Token` and returned as `Authorization: Bearer`, so clients do not
  change. Sign-out works through a list of revoked token ids.
- **Paging.** `GET /messages` and `GET /accounts/{id}/messages` accept `limit` (1-100) and `before`
  (a message id) and then answer newest first with an `X-Has-More` header. Without the parameters the
  response is exactly what it was. The web app loads ten posts at a time with a "Show older posts" button.
- **Login rate limit** (strict mode). Five attempts without a success for one username from one address,
  then `429` with `Retry-After` for 15 minutes. Attempts are counted before the password is checked, so
  guesses sent at the same instant cannot all get through.
- **Stronger passwords** (strict mode, new registrations only). At least 8 characters with a letter and
  a digit, and not the username. Existing accounts keep their passwords. `GET /health` now also reports
  `password_policy` so the web app shows the right hint.
- Web app: paging, the lock-out message, and the password hint and check. It detects a server that does
  not page (the Javalin edition) and shows the whole list there, as before.
- Tests: 133 (was 82). 76 unit, 57 end-to-end.
- API docs updated (still 13 operations; new parameters, headers and status codes).

Behaviour changes to know about:
- Members must choose a strong password when registering on the Spring Boot edition. `password` is no
  longer accepted there; `secret12` is.
- In strict mode a write without a valid token is refused with 401 before the body is looked at. In 2.0 a
  request that was both unauthenticated and malformed got 400.
- Every member is signed out when the server restarts, unless `BRIEF_JWT_SECRET` is set.

Verification: **run by the author on 2026-10-06: `Tests run: 133, Failures: 0, Errors: 0, Skipped: 0`** on
Spring Boot 3.3.5, Spring Security 6.3.4, Hibernate 6.5.3, Tomcat 10.1.31 and Java 17.0.11.

Verification status at release (kept for the record):
- **Not yet compiled or run with real Spring Security** - it could not be downloaded in the build environment.
  2.0.0 was written the same way and passed 82 of 82 on the first real run.
- Checked there: every file compiles against stand-ins written from the real Spring Security 6.3.4,
  Spring Boot 3.3.5 and Spring Data 3.3.5 sources; the 76 unit tests pass (the JWT, rate-limit, password
  and paging logic is plain Java and ran for real); 145 checks send simulated requests through the real
  filter, the real URL rules, controllers and services in both modes; nine deliberate bugs were each
  caught by the tests; an independent line-by-line review against the real sources found no compile,
  start-up or test failure; the web app passed 25 browser checks against a stand-in server in both editions.
- Unverified until `.\mvnw.cmd test` runs: the Spring Security wiring itself, the generated paging
  queries, and the 57 end-to-end tests. Expect `Tests run: 133`.

Known limits:
- The rate-limit counts and the revoked-token list are in memory: lost on restart, not shared between servers.
- Behind a reverse proxy every caller has the proxy's address unless forwarded headers are switched on.
- Spring Security sends `Cache-Control: no-store` on every response, so the browser re-downloads the web
  app's files on each visit. Unnoticeable locally; a deployment would let static files be cached.
- Still to do: Flyway migrations with a foreign key from message to account; springdoc-openapi; MySQL
  profile still untested.

## 2.0.0 - 2026-10-05

**Spring Boot edition.** A second backend, `backend-spring/`, that serves the same API on
Spring Boot 3.3, Spring Web, Spring Data JPA (Hibernate) and Bean Validation. The Javalin edition
in `backend/` is unchanged and stays for comparison (`docs/EDITIONS.md`).

- Entities (`@Entity`) and repositories (interfaces - Spring Data generates the SQL) replace the DAOs.
- Bean Validation (`@NotBlank`, `@Size`, `@Valid`) replaces the hand-written length and blank checks.
- Services throw exceptions; one `@RestControllerAdvice` turns them into status codes with empty bodies.
- H2 by default; MySQL with `--spring.profiles.active=mysql` (a `docker-compose.yml` is included).
- 82 tests: 41 unit (JUnit 5, Mockito) and 41 end-to-end (`@SpringBootTest`), the same end-to-end cases
  as the Javalin edition plus one that checks the web app is served.
- `npm run build` now copies the web app into both backends.
- New: `.github/workflows/ci.yml` runs both test suites and the web build on GitHub.

Verification: written without access to Spring in the build environment, then **run by the author on
2026-10-05: `Tests run: 82, Failures: 0, Errors: 0, Skipped: 0`** on Spring Boot 3.3.5, Hibernate 6.5.3,
Tomcat 10.1.31 and Java 17.0.11. `mvnw package` and the web build also succeeded. The MySQL profile is untested.

Deliberately not in 2.0.0 (one change at a time): Spring Security with JWT (the in-memory token service is
kept), pagination, login rate limiting, a stronger password rule, database migrations (Flyway), a foreign key
from message to account. Planned for 2.1.

## 1.1.0 - 2026-10-04

New features, so a new version number.

- **Clickable links.** Web addresses in posts open in a new tab (`rel="noopener noreferrer nofollow"`).
  Only http and https addresses are linked; trailing punctuation and surrounding quotes are left out.
- **Mentions.** New endpoint `GET /accounts/{id}/mentions` and `MentionService`: posts by other people that
  contain a member's username as a whole word. In the app: a Mentions view, an unread count on the button
  and in the tab title, an on-screen message, optional desktop notifications, and highlighted names.
- **Auto-refresh.** The feed and mentions are re-read every 15 seconds.
- Tests: 79 (was 67). 10 new unit tests for the mention rule, 2 new end-to-end tests for the endpoint.
  **Run by the author on 2026-10-06: `Tests run: 79, Failures: 0, Errors: 0, Skipped: 0`.** This is the
  final version of the Javalin edition.
- API docs: 13 operations.
- Roadmap: the "hardening" items planned for 1.1 (pagination, login rate limit, stronger passwords, CI)
  are not done and move to 1.2 or into the Spring Boot edition (2.0).

## 1.0.2, revision C - 2026-10-03

- **Fixed:** the server status dot was grey when online and when offline. Revision B's stylesheet edit
  had overwritten the two rules that colour it. It is green when online and red when offline again.
- Java target raised from 11 to 17 (`maven.compiler.release`), matching the installed JDK. This removes
  the two "JavaSE-11" warnings in VS Code and the "system modules path" warning from Maven.
- README: how to open the app on a phone.
- OTSR v3 added: first recorded run of the full test suite (67 of 67 passed).

## 1.0.2, revision B - 2026-10-03

The version number stays at 1.0.2 at the author's request.

- The product is now written **Brief!** (wordmark, browser tab title, startup message).
- New API documentation page (Swagger UI) at `/docs/index.html`, with an "API docs" link in the app.
  The description is `frontend/public/docs/openapi.json`. Swagger UI is self-hosted, no CDN.
- OTSR v2 added to `docs/`.
- Java: one text change (the startup message in `Main.java`). No logic changes.

## 1.0.2 - 2026-10-03

- Frontend is TypeScript again (`.ts` / `.tsx`), the same code as 1.0.0. The 1.0.1 JavaScript
  conversion is withdrawn: the job this project supports lists TypeScript, and the editor errors
  that prompted the conversion were caused by a missing `npm install`, not by TypeScript.
- Keeps the Maven Wrapper and the README changes from 1.0.1.
- New `npm run typecheck` script.
- No changes to Java source.

## 1.0.1 - 2026-10-03

- Frontend converted from TypeScript (`.ts` / `.tsx`) to plain JavaScript (`.js` / `.jsx`), matching the
  PixelPerfect codebase. Behavior and design are unchanged. `tsconfig.json` and the TypeScript
  dependencies are gone; `npm run build` is now just `vite build`.
- Added the Maven Wrapper (`backend/mvnw`, `backend/mvnw.cmd`, `backend/.mvn/`), so the backend builds
  and tests without a separate Maven install.
- README: Windows commands, and the `npm install` step called out.
- No changes to Java source.

## 1.0.0 - 2026-10-03

- First full-stack release. See `docs/Brief_OTSR_v1_October2026.pdf`.
