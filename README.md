# Brief!

A small message board where every post fits in 255 characters.

One web app (React + TypeScript) and **two editions of the same Java API**:

| Folder | Edition | Stack |
|---|---|---|
| `backend/` | Javalin edition (1.x) | Javalin, hand-written JDBC, H2 |
| `backend-spring/` | Spring Boot edition (2.x) | Spring Web, Spring Security (JWT), Spring Data JPA (Hibernate), Flyway, Bean Validation, H2 or PostgreSQL |
| `frontend-spring/` | Web app | React 18, TypeScript, Vite |

Both backends answer the original requests with the same responses, so the web app works with either.
**Development continues on the Spring Boot edition only.** The Javalin edition is frozen at 1.1.0 and
kept as the reference that shows what the framework does for you. Since 2.1 the Spring Boot edition
goes further (signed tokens, paging, a login rate limit, strong passwords, indexed mentions); the web
app notices which one it is talking to. Run **one at a time** - they both use
port 8080. `docs/EDITIONS.md` compares them file by file.

**2.3 is the first build that can be deployed:** it runs on PostgreSQL, creates its tables with versioned
migrations (Flyway), ships a `Dockerfile` and a `render.yaml`, and can be installed on a phone as an app.
See [Deploy it](#deploy-it). It runs at https://briefmessage.net.

**2.4 makes accounts real:** an account has an email address, confirmed through a mailed link; a forgotten
password is replaced through a mailed link; and a board can be **closed**, so that only invited people can
join and only members can read. See [Email, and a closed board](#email-and-a-closed-board).

**2.5 makes joining professional:** the person who runs a closed board invites people by **email or by
link**, and nobody types a code; a member can **delete their account**; the web app has **Terms** and
**Privacy** pages. See [Invitations](#invitations-25).

Brief! started as the Revature "Social Media Blog API" training project and keeps that project's
eight-endpoint contract intact.

![Brief! on desktop](docs/screenshot-desktop.png)

## Run it

You need a JDK (17 or newer) and Node 18+. You do **not** need to install Maven: each backend
includes the Maven Wrapper (`mvnw`), which downloads Maven the first time you use it.

### Spring Boot edition (Windows PowerShell)

```powershell
cd backend-spring
.\mvnw.cmd test                    # 354 tests
.\mvnw.cmd package                 # builds target\brief-spring.jar
java -jar target\brief-spring.jar  # open http://localhost:8080
```

The jar is called `brief-spring.jar` (the Javalin one is `brief-api.jar`). Wait for the line
`Started BriefApplication`, then open the address. Data is stored in `backend-spring/h2/` (an H2 file
database), separate from the Javalin edition's, so the first time you start it there are no accounts yet.

New accounts need a username of **3 to 30 letters, numbers, dots, dashes or underscores** and a password of
**8 or more characters with a letter and a number** (for example `secret12`). A username is one name
whatever its capitals: if `Maya` exists, `maya` is taken, and either spelling signs in.

Since 2.4 a new account also gives an **email address**, and can read but not post until the address is
confirmed. On your own machine no mail is sent: the application writes each mail to the console instead.
Look for the line `MAIL NOT SENT (no mail service is configured)`, and open the link below it (it starts
with `http://localhost:8080/?verify=`). "Forgot password?" works the same way.

When it starts, the log says which database is in use (`Database: H2 file ./h2/brief-spring on this machine.`).
You may also see `Flyway upgrade recommended: H2 2.2.224 is newer than this version of Flyway`. That is a
notice, not an error.

**Coming from 2.3 with data you want to keep?** Copy the `backend-spring\h2` folder of the old version into
the new one, and keep a second copy somewhere else first. At the first start the log shows
`Migrating schema "PUBLIC" to version "3 - account email and tokens"`. Accounts made before 2.4 have no
email address; they keep working, and the web app asks their owners for one after signing in.

**The web app's folder was renamed in 2.4**, from `frontend` to `frontend-spring`. If you unpack 2.4 over an
older copy, delete the old `frontend` folder (in a Git repository: `git rm -r frontend`, or rename it first
with `git mv frontend frontend-spring`, which keeps its history).

**Coming from 2.2 with data you want to keep?** Copy the `backend-spring\h2` folder of the old version into
the new one, **and keep a second copy somewhere else first**. At the first start 2.3 changes that database
(foreign keys, the `username_key` column); the log shows `Successfully baselined schema` and
`Migrating schema "PUBLIC" to version "2 - foreign keys and username key"`. H2 cannot undo a change that
fails half-way, so the spare copy is your way back.

**PostgreSQL on your machine** (needs Docker Desktop):

```powershell
docker compose up -d postgres                                        # PostgreSQL 16 on port 5432
java -jar target\brief-spring.jar --spring.profiles.active=postgres
```

**The tests on PostgreSQL.** The same 354 tests, with the end-to-end ones against a real PostgreSQL:

```powershell
docker compose up -d postgres-test                                   # port 5433, starts empty every time
$env:BRIEF_TEST_DB_URL = "jdbc:postgresql://localhost:5433/brief_test"
$env:BRIEF_TEST_DB_USER = "brief"
$env:BRIEF_TEST_DB_PASSWORD = "brief"
.\mvnw.cmd test
Remove-Item Env:BRIEF_TEST_DB_URL, Env:BRIEF_TEST_DB_USER, Env:BRIEF_TEST_DB_PASSWORD   # back to H2
```

Give the tests a database of their own, as above: they delete every row before each case.

**MySQL** remains as a study profile (`docker compose up -d mysql`, then `--spring.profiles.active=mysql`).
It has never been run, it does not use the migrations, and it is not the path to production; see
`src/main/resources/application-mysql.properties`.

### Javalin edition

```powershell
cd backend
.\mvnw.cmd test                    # 79 tests
.\mvnw.cmd package                 # builds target\brief-api.jar
java -jar target\brief-api.jar     # open http://localhost:8080
```

Data is stored in `backend/h2/`. The two editions keep separate databases.

On macOS or Linux use `./mvnw` instead of `.\mvnw.cmd`.

### Frontend

```powershell
cd frontend-spring
npm install      # required once - until this runs, the editor shows red lines (it cannot find React)
npm run dev      # http://localhost:5173, API calls are forwarded to :8080
npm run build    # type-checks, builds, and copies the web app into BOTH backends
```

Start a backend first; the web app talks to it.

`npm run build` also puts the built web app into `backend-spring/src/main/resources/public`, which is
what the jar and the Docker image serve. After changing the web app, build it before you commit.

## Deploy it

Everything a host needs is in the repository. The step-by-step guide, with every screen, is
`docs/Brief_Launch_Roadmap_v2.pdf`, and `docs/Brief_Launch_Roadmap_v3.pdf` records how the first deployment
went; this is the short version.

**As a Docker image, anywhere:**

```powershell
docker build -t brief .                  # from this folder (the one that holds backend-spring\)
docker run --rm -p 8080:8080 brief       # http://localhost:8080 - an H2 file inside the container, forgotten on exit
```

Give the container a `DATABASE_URL` of the form `postgresql://user:password@host:5432/database` and it
uses that PostgreSQL database instead; the tables are created at the first start.

**On Render:** push the repository to GitHub, then in Render choose **New > Blueprint** and pick it.
`render.yaml` creates the web service (built from the `Dockerfile`) and a PostgreSQL 16 database, generates
the token-signing key, and connects the two. After the first deploy, check the log for the line
`Database: PostgreSQL, ...`.

Three values are **not** filled in by `render.yaml`: `BRIEF_RESEND_API_KEY` and `BRIEF_RESEND_WEBHOOK_SECRET` (secrets) and `BRIEF_OWNER_USERNAME`.
Add them under the service's **Environment** tab. **On a service that already exists, Render does not add a
variable that a later `render.yaml` introduces either**: every new variable is listed in `CHANGELOG.md` under
"Before you deploy", to be added by hand before pushing. The start-up log tells you what the application found,
in the lines that start with `Board:`, `Owner:`, `Mail:` and `Bounces:`.

Since 2.4 Render deploys a commit only **after its checks on GitHub have passed** (`autoDeployTrigger: checksPass`).
A push whose tests fail stays on GitHub.

What the deployed application reads:

| Variable | Set by | Meaning |
|---|---|---|
| `PORT` | Render | The port to listen on |
| `DATABASE_URL` | `render.yaml`, from the database | PostgreSQL connection string; turned into JDBC settings at start-up (`config/DatabaseUrl.java`) |
| `BRIEF_JWT_SECRET` | `render.yaml`, generated once | Signs login tokens. Because it is fixed, logins survive a redeploy |
| `BRIEF_CLIENT_IP_HEADER` | `render.yaml`: `CF-Connecting-IP` | The header that carries the visitor's address behind Render's proxy, for the login rate limit |
| `BRIEF_AUTH_MODE` | `render.yaml`: `strict` | See "Two modes" below |
| `BRIEF_PUBLIC_URL` | `render.yaml`: `https://briefmessage.net` | Where the links in mails point |
| `BRIEF_MAIL_FROM` | `render.yaml`: `Brief! <hello@briefmessage.net>` | The sender of the mails. Its domain must be verified at Resend |
| `BRIEF_RESEND_API_KEY` | **you, in the dashboard** | The key of the mail service. Without it mails are written to the log, not sent |
| `BRIEF_RESEND_WEBHOOK_SECRET` | **you, in the dashboard** | 2.7. The signing secret (`whsec_...`) of the Resend webhook to `/webhooks/resend`. Without it Brief! does not learn when a mail bounces |
| `BRIEF_BOARD` | `render.yaml`: `closed` | `closed`: joining needs an invitation and reading a sign-in. `open`: anyone reads and joins |
| `BRIEF_OWNER_USERNAME` | **you, in the dashboard** | The member who runs the board and sends invitations (on briefmessage.net: `AmbroLum`) |

## Features

- Accounts with hashed passwords (PBKDF2), login tokens, and ownership rules: you can only post as
  yourself and only change your own posts.
- **Spring Boot edition, since 2.1:** Spring Security checks every request; the login token is a signed
  JWT; the feed loads ten posts at a time ("Show older posts"); five wrong passwords lock that username
  from that address for 15 minutes; new passwords must be strong.
- **Since 2.2:** the Create account form asks for the password twice, can show what you typed, and tells
  you while you type whether a username is free. In the Spring Boot edition, mentions are stored in their
  own indexed table when a post is saved, so "who mentioned me?" stays fast however many posts exist.
- **Since 2.3:** PostgreSQL and versioned migrations; mentions load 20 at a time ("Show older mentions");
  usernames are one name whatever their capitals; the login rate limit works behind a proxy; the web app's
  files are cached by the browser; **Install Brief! on this device** puts the app on a phone's home screen or
  a computer's desktop, where it opens in its own window and still opens without a connection; **Stay signed
  in on this device** keeps the session after the tab or the app is closed.
- **Since 2.4:** an email address per account, confirmed through a mailed link (until then the member reads
  but does not post); **Forgot password?** mails a link for choosing a new one, which also signs the account
  out on every device; a board can be **closed** to invited people; an address is shown to nobody but
  its owner.
- **Since 2.5:** invitations by email or by link from the person who runs a closed board; a "Check your email"
  step until a new address is confirmed; **Your account**, with what Brief! keeps and how to delete it all;
  Terms and Privacy pages.
- **Since 2.7:** when a mail **bounces** (a mistyped address, a mailbox that does not exist), Resend tells
  Brief!, the member sees "We couldn't deliver your link" and is asked for the address again, and the board
  owner sees "Bounced" next to the invitation. A bounced address is not mailed again for a day.
- **Since 2.6:** a **Help Center** (`/help/index.html`, the **Help** link beside Privacy): what Brief! is,
  who it is for, getting started, an FAQ, pricing (free during early access) and the refund policy.
- Posts of up to 255 characters, with the limit drawn as a ruler while you type.
- **Links:** web addresses in posts open in a new tab, with or without `https://` in front
  (`pixelperfectapi.net` works). Posts are never treated as HTML.
- **Mentions:** write a member's username (`TestUser1` or `@TestUser1`) and they see a count on their
  Mentions button and in the tab title, a message on screen, and optionally a desktop notification.
- The page re-reads the feed and mentions every 15 seconds.
- API documentation (Swagger UI) at `/docs/index.html`.

Limits: alerts only reach someone who has Brief! open, in a tab or as the installed app; mentions are not
mailed, and there is no phone push yet. A confirmed email address cannot be changed yet, and an account
without a confirmed address has no way back in if its password is forgotten. Desktop notifications and installing need `localhost` or HTTPS, so on a phone they work
once the site is deployed, not over `http://192.168...`. A username that is an ordinary word will be matched
whenever that word is written. A bare address becomes a link only if it ends in a common ending (`.com`,
`.net`, `.org`, `.io`, ...; the list is in `frontend-spring/src/lib/richText.ts`); any address works with `https://`.

## API

| Method | Path | Success | Failure |
|---|---|---|---|
| POST | `/register` | 200 + account | 400 invalid, username, password or email against the rule, or username or email taken; 403 no usable invitation (closed board); 429 after too many bad invitations |
| POST | `/login` | 200 + account, token in `X-Auth-Token` header | 401; 429 after too many attempts (Spring, strict) |
| POST | `/logout` | 200 (always) | |
| GET | `/accounts` | 200 + list of `{account_id, username}` | |
| GET | `/accounts/{id}` | 200 + `{account_id, username}` | 404 |
| GET | `/accounts/{id}/messages` | 200 + list (may be empty); pages like `/messages` | 400 bad paging value |
| GET | `/accounts/{id}/mentions` | 200 + posts by others that name this member (may be empty), oldest first | 404 |
| GET | `/accounts/{id}/mentions?limit=20&before=42` | 200 + one page, **newest first**, header `X-Has-More` (Spring, 2.3) | 400 bad paging value; 404 |
| POST | `/messages` | 200 + message | 400 invalid |
| GET | `/messages` | 200 + list (may be empty), oldest first | |
| GET | `/messages?limit=10&before=42` | 200 + one page, **newest first**, header `X-Has-More` (Spring) | 400 bad paging value |
| GET | `/messages/{id}` | 200 + message, or 200 with empty body | |
| PATCH | `/messages/{id}` | 200 + full updated message | 400 |
| DELETE | `/messages/{id}` | 200 + deleted message, or 200 with empty body | |
| GET | `/health` | 200 `{"status":"UP"}` (Spring adds `"password_policy"`, and since 2.4 `"email"` and `"board"`) | |
| GET | `/me` | 200 + `{account_id, username, email, email_verified, can_post, is_owner}` (Spring, 2.4; `is_owner` 2.5) | 401 |
| POST | `/invitations` | 200; mails an invitation (Spring, 2.5, board owner) | 400 not an address or already a member; 401; 403 not the owner; 429 |
| POST | `/invitations/link` | 200 + `{link, invitation}`, the link shown once (Spring, 2.5, board owner) | 401; 403; 429 |
| GET | `/invitations` | 200 + the invitations sent, newest first (Spring, 2.5, board owner) | 401; 403 |
| DELETE | `/invitations/{id}` | 200; the link stops working (Spring, 2.5, board owner) | 400 used; 404 |
| POST | `/invitations/lookup` | 200 + `{inviter, email, expires_at}`; no sign-in needed (Spring, 2.5) | 400 unknown, used, withdrawn or expired |
| POST | `/accounts/{id}/deletion` | 200; deletes the account and its posts; body `{"password": "..."}` (Spring, 2.5) | 400 board owner; 401; 403 other account or wrong password; 429 |
| POST | `/accounts/{id}/email` | 200 + the same; mails the confirmation link (Spring, 2.4) | 400 not an address, taken, or already confirmed; 401; 403 someone else's account |
| POST | `/email/verification` | 200; mails the link again (Spring, 2.4) | 400 nothing to confirm; 401; 429 within a minute |
| POST | `/email/verify` | 200; body `{"token": "<secret from the link>"}`, no sign-in needed (Spring, 2.4) | 400 unknown, used or expired |
| POST | `/password/forgot` | 200 **always**, whether or not the address has an account (Spring, 2.4) | 429 after five requests from one address |
| POST | `/password/reset` | 200; body `{"token": "...", "password": "..."}`, no sign-in needed (Spring, 2.4) | 400 link not good, or password against the rule |

On a **closed board** every `GET` of posts and members (`/messages...`, `/accounts...`) also answers 401
without a valid token.

Error responses have an empty body; the status code carries the meaning.

**Paging (Spring Boot edition).** `limit` is 1 to 100. `before` is the `message_id` of the last post you
already have. Ask for `?limit=10`, then `?limit=10&before=<last id>`, and stop when `X-Has-More` is `false`.
Without either parameter you get the full list exactly as before. Try it: `http://localhost:8080/messages?limit=2`.
The three lists that page (`/messages`, `/accounts/{id}/messages`, `/accounts/{id}/mentions`) all work this way.

### Two modes

| | compat | strict (default) |
|---|---|---|
| Used by | the contract tests | the running app |
| Who may write | anyone | signed-in members, as themselves only |
| Missing or bad token on a write | allowed | 401 |
| Writing as, or changing a post of, someone else | allowed | 403 |
| New username (Spring Boot edition, 2.3) | not blank | 3 to 30 letters, digits, dots, dashes or underscores |
| New password (Spring Boot edition) | 4+ characters | 8+ characters, a letter and a digit, not the username |
| Failed logins (Spring Boot edition) | not limited | 5 per username and address, then 429 for 15 minutes. An IPv6 address counts together with the rest of its /64 block |
| Email address for a new account (Spring Boot edition, 2.4) | optional | required |
| Posting before the address is confirmed (2.4) | allowed | 403 |
| Closed board (`BRIEF_BOARD=closed`) | not allowed: the application refuses to start | optional |

Send the token as `Authorization: Bearer <token>`. In the Spring Boot edition the token is a JWT
(`header.payload.signature`), signed with HMAC-SHA256, valid for 12 hours, or until you sign out, or (2.4)
until the account's password is replaced through a mailed link.

### Email, and a closed board

**Confirming an address.** `POST /register` with an `email` stores the account and mails a link,
`<BRIEF_PUBLIC_URL>/?verify=<secret>`. The link opens the web app, which sends the secret to
`POST /email/verify`. A link works once, for 24 hours; asking for a new one makes the earlier one stop
working. The database keeps only a SHA-256 hash of the secret. An account made before 2.4 has no address:
it keeps posting, and can add one (`POST /accounts/{id}/email`), after which it confirms like any other.

**A forgotten password.** `POST /password/forgot` with an address answers 200 whatever the address is. If
it is the *confirmed* address of an account, a link `<BRIEF_PUBLIC_URL>/?reset=<secret>` is mailed, good
once, for one hour. `POST /password/reset` with the secret and a new password stores it and makes every
login token of that account worthless, on every device.

**Sending.** Mails go through [Resend](https://resend.com)'s HTTPS API when `BRIEF_RESEND_API_KEY` is set
(`mail/ResendMailer.java`), otherwise into the log (`mail/LogMailer.java`). To use another service, write
one more class that implements `mail/Mailer.java`.

**Bounces (2.7).** Resend reports mail that could not be delivered to `POST /webhooks/resend`, signed with
`BRIEF_RESEND_WEBHOOK_SECRET` (`mail/ResendWebhookVerifier.java`, `controller/ResendWebhookController.java`).
The address is kept for 30 days (`service/EmailBounces.java`, table `email_bounce`): the member's page says the
link could not be delivered, and for a day Brief! does not mail that address again (422). To set it up: in Resend,
**Webhooks > Add Webhook**, URL `https://<your site>/webhooks/resend`, events `email.bounced` and
`email.suppressed`; copy the signing secret into the variable.

**A closed board.** Start the application with `BRIEF_BOARD=closed` (strict mode only) and:

- `POST /register` needs a usable invitation (see below); without one it answers 403. Five bad ones
  from one address lock that address out for 15 minutes.
- Reading posts and members needs a sign-in (401 without).
- The web app, `/health`, the API documentation, the Terms and Privacy pages, the Help Center and the mailed links stay
  reachable without one.

`GET /health` then says `"board": "closed"`, and the web app shows a sign-in page instead of the feed. To
open the board, set `BRIEF_BOARD=open` and restart.

### Invitations (2.5)

The member named in `BRIEF_OWNER_USERNAME` runs the board. Signed in, they see **Invite people**:

- **By email:** `POST /invitations` mails `<BRIEF_PUBLIC_URL>/?invite=<secret>`. The account created with it
  starts with that address **already confirmed**: opening the link proved the inbox.
- **By link:** `POST /invitations/link` answers with a link, shown once, to pass on by text message or in
  person. The newcomer gives an address and confirms it as usual.

An invitation works once, for 7 days (`BRIEF_INVITATION_DAYS`), and can be withdrawn while unused. The page it
opens says who sent it and asks only for a username and a password. At most 50 invitations a day.

**Starting a new closed board:** the owner's account has to exist first. Start with `BRIEF_BOARD=open`,
create the account, then set `BRIEF_BOARD=closed` and `BRIEF_OWNER_USERNAME` and restart. From then on
nobody can register the owner's name, so no one can take the board over by claiming it first.

**Coming from 2.4:** the shared code (`BRIEF_INVITE_CODE`) is no longer asked for. A server that still has it,
and no `BRIEF_BOARD`, stays closed so that an upgrade never opens a board by accident; its log asks for
`BRIEF_BOARD=closed` in its place.

**Deleting an account.** `POST /accounts/{id}/deletion` with the password deletes the account and every
post it wrote, at once (web app: **Your account**). The board owner cannot delete theirs.

### Settings

| Variable | Default | Meaning |
|---|---|---|
| `PORT` | 8080 | Port to listen on |
| `BRIEF_AUTH_MODE` | strict | `strict` or `compat` |
| `BRIEF_DB_URL` | an H2 file in the edition's `h2/` folder | JDBC URL |
| `BRIEF_DB_USER`, `BRIEF_DB_PASSWORD` | `sa` / `sa` (H2) | Spring only. Database login |
| `DATABASE_URL` | not set | Spring only. A PostgreSQL connection string (`postgresql://user:password@host/db`), as hosts provide it. Used when `BRIEF_DB_URL` is not set |
| `BRIEF_CLIENT_IP_HEADER` | empty | Spring only. Behind a proxy: the request header that carries the visitor's address (`CF-Connecting-IP` on Render). Empty = use the address of the connection. Leave it empty on your own machine |
| `BRIEF_JWT_SECRET` | empty | Spring only. The key that signs login tokens, 32+ characters. Empty = a random key at each start, so members sign in again after a restart. Never put a real key in a file that is committed. |
| `BRIEF_JWT_TTL_MINUTES` | 720 | Spring only. How long a login lasts |
| `BRIEF_LOGIN_MAX_FAILURES` | 5 | Spring only. Attempts before a lock-out |
| `BRIEF_LOGIN_LOCKOUT_MINUTES` | 15 | Spring only. Length of the lock-out |
| `BRIEF_BOARD` | open | Spring only, 2.5. `open` or `closed` (strict mode only). See "A closed board" |
| `BRIEF_OWNER_USERNAME` | empty | Spring only, 2.5. The username of the member who runs the board and can invite |
| `BRIEF_INVITATION_DAYS` | 7 | Spring only, 2.5. How long an invitation link works |
| `BRIEF_INVITE_CODE` | empty | Spring only, 2.4. No longer used as a code since 2.5; set with no `BRIEF_BOARD`, it keeps the board closed |
| `BRIEF_PUBLIC_URL` | `http://localhost:8080` | Spring only, 2.4. The address of the web app, used in mailed links |
| `BRIEF_RESEND_API_KEY` | empty | Spring only, 2.4. The Resend API key. Empty = mails are written to the log. Never put a real key in a file that is committed |
| `BRIEF_RESEND_WEBHOOK_SECRET` | empty | Spring only, 2.7. The Resend webhook's signing secret (`whsec_...`). Empty = `/webhooks/resend` answers 404 and bounces are not known |
| `BRIEF_MAIL_FROM` | empty | Spring only, 2.4. The sender, for example `Brief! <hello@briefmessage.net>`. Required when the key is set |

Locked yourself out while testing? Restart the server (the counts are in memory), or wait.

## Open it on your phone

`localhost` always means "this device", so on a phone it points at the phone itself. Use the computer's
address on your Wi-Fi network instead:

1. Start a backend on the computer.
2. In PowerShell run `ipconfig` and note the **IPv4 Address** of your Wi-Fi adapter, for example `192.168.1.23`.
3. Put the phone on the **same Wi-Fi network** and open `http://192.168.1.23:8080` (your own number).

The first time, Windows Firewall asks whether Java may accept connections: allow it for **Private** networks.
If no prompt appeared and the page does not load, run this once in PowerShell **as Administrator**:

```powershell
New-NetFirewallRule -DisplayName "Brief 8080" -Direction Inbound -Protocol TCP -LocalPort 8080 -Action Allow -Profile Private
```

## Help Center (2.6)

Plain pages in `frontend-spring/public/help/`, styled by `legal.css` like the Terms and Privacy pages, and
copied into both backends by `npm run build`. Open **http://localhost:8080/help/index.html** (or click
**Help**). To add an article: write `help/<name>.html` on the model of an existing one, and add it to the
list in `help/index.html`. The test `ClosedBoardTest.theHelpCenterIsPublicAndEveryArticleItListsExists`
checks that every article the list names answers, so it needs the new name too.

## API documentation

With a backend running, open **http://localhost:8080/docs/index.html** (or click "API docs" in the app).
"Try it out" sends real requests to your server. For the protected endpoints: run `POST /login`, copy the
`x-auth-token` value from the response headers, click **Authorize**, and paste it.

The page reads `frontend-spring/public/docs/openapi.json`, which is written by hand. When you change an endpoint,
update that file too, then run `npm run build`.

## Tests

| Edition | Unit | End to end | Total |
|---|---|---|---|
| Javalin (`backend/`) | 39 | 40 | 79 |
| Spring Boot (`backend-spring/`) | 225 | 129 | 354 |

The end-to-end tests start the real application, call it over HTTP, and check status codes and JSON.
The 41 cases the two editions share are the same; the Spring Boot edition adds 74 for what 2.1 to 2.5
brought (JWT, paging, the password rule, the rate limit, the mention table, usernames, the migrations and
foreign keys, caching, the visitor's address behind a proxy, email confirmation, password reset, the closed
board, invitations, deleting an account). The mails in these tests are never sent: the application is started without a mail service, and the
tests read the links from the mailer that writes to the log. Tests use an in-memory database and never
touch the `h2/` folders. Since 2.3 that database gets its tables from the same migration files production
runs, so every test run also tests the migrations.

`.github/workflows/ci.yml` runs all of it on GitHub for every push: both backends, the Spring Boot suite a
second time against PostgreSQL 16, a build and start of the Docker image, and the web app build.

## Documents

- `docs/EDITIONS.md` - the Javalin and Spring Boot editions side by side.
- `CHANGELOG.md` - what changed in each version.
- `docs/Brief_OTSR_v7_October2026.pdf` - status report for 2.4.0 to 2.6.0: email on accounts, invitation
  links, the independent review of 2.5.0, the deployments, what was verified live, what is open.
- `docs/Brief_Launch_Roadmap_v4.pdf` - the roadmap after the closed board went live: paid plans before
  November 10, the Help Center, 2.7 (push notifications), pricing stance, legal checklist.
- `docs/Brief_OTSR_v6_October2026.pdf` - status report after the first deployment: 2.3.0 verified on your
  machine and on GitHub, live at briefmessage.net, what the deployment taught, what is open.
- `docs/Brief_Launch_Roadmap_v3.pdf` - the roadmap after going live: what M0 to M2 took, the plan for 2.4
  and 2.5, the pilot, costs and dates.
- `docs/Brief_Architecture_Color.pdf`, `docs/Brief_Architecture_BW.pdf` - the system on one page.
- `docs/Brief_OTSR_v5_October2026.pdf` - status report for 2.3.0, the deployable build: what changed and
  why, how it was verified, defects found, what is still open.
- `docs/Brief_Launch_Roadmap_v2.pdf` - from localhost to briefmessage.net, updated for 2.3.0: the Cloudflare
  and Render steps screen by screen, attachments and the installable app, costs.
- `docs/Brief_OTSR_v4_October2026.pdf` - status report for the Spring line, 2.0.0 to 2.2.0: the move from
  Javalin to Spring, test results, what changed and why.
- `docs/Brief_Backend_Concepts_Guide.pdf` - Spring Boot, dependency injection, JPA and Hibernate, Spring
  Security, JWT and password hashing, each explained with the Brief! file that uses it.
- `docs/Brief_Launch_Roadmap.pdf` - the first version of the roadmap (October 6), kept for history.
- `docs/Brief_OTSR_v3_October2026.pdf` - status report at 1.0.2 (test results, warnings, phone access). The
  last report of the Javalin line.
- `docs/Brief_OTSR_v2_October2026.pdf` - how the application works, limitations, product directions, roadmap.
- `docs/Brief_OTSR_v1_October2026.pdf` - the first report, kept for history.
