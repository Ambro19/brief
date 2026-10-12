# Changelog

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
