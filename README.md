# Brief!

A small message board where every post fits in 255 characters.

One web app (React + TypeScript) and **two editions of the same Java API**:

| Folder | Edition | Stack |
|---|---|---|
| `backend/` | Javalin edition (1.x) | Javalin, hand-written JDBC, H2 |
| `backend-spring/` | Spring Boot edition (2.x) | Spring Web, Spring Security (JWT), Spring Data JPA (Hibernate), Flyway, Bean Validation, H2 or PostgreSQL |
| `frontend/` | Web app | React 18, TypeScript, Vite |

Both backends answer the original requests with the same responses, so the web app works with either.
**Development continues on the Spring Boot edition only.** The Javalin edition is frozen at 1.1.0 and
kept as the reference that shows what the framework does for you. Since 2.1 the Spring Boot edition
goes further (signed tokens, paging, a login rate limit, strong passwords, indexed mentions); the web
app notices which one it is talking to. Run **one at a time** - they both use
port 8080. `docs/EDITIONS.md` compares them file by file.

**2.3 is the first build that can be deployed:** it runs on PostgreSQL, creates its tables with versioned
migrations (Flyway), ships a `Dockerfile` and a `render.yaml`, and can be installed on a phone as an app.
See [Deploy it](#deploy-it).

Brief! started as the Revature "Social Media Blog API" training project and keeps that project's
eight-endpoint contract intact.

![Brief! on desktop](docs/screenshot-desktop.png)

## Run it

You need a JDK (17 or newer) and Node 18+. You do **not** need to install Maven: each backend
includes the Maven Wrapper (`mvnw`), which downloads Maven the first time you use it.

### Spring Boot edition (Windows PowerShell)

```powershell
cd backend-spring
.\mvnw.cmd test                    # 207 tests
.\mvnw.cmd package                 # builds target\brief-spring.jar
java -jar target\brief-spring.jar  # open http://localhost:8080
```

The jar is called `brief-spring.jar` (the Javalin one is `brief-api.jar`). Wait for the line
`Started BriefApplication`, then open the address. Data is stored in `backend-spring/h2/` (an H2 file
database), separate from the Javalin edition's, so the first time you start it there are no accounts yet.

New accounts need a username of **3 to 30 letters, numbers, dots, dashes or underscores** and a password of
**8 or more characters with a letter and a number** (for example `secret12`). A username is one name
whatever its capitals: if `Maya` exists, `maya` is taken, and either spelling signs in.

When it starts, the log says which database is in use (`Database: H2 file ./h2/brief-spring on this machine.`).
You may also see `Flyway upgrade recommended: H2 2.2.224 is newer than this version of Flyway`. That is a
notice, not an error.

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

**The tests on PostgreSQL.** The same 207 tests, with the end-to-end ones against a real PostgreSQL:

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
cd frontend
npm install      # required once - until this runs, the editor shows red lines (it cannot find React)
npm run dev      # http://localhost:5173, API calls are forwarded to :8080
npm run build    # type-checks, builds, and copies the web app into BOTH backends
```

Start a backend first; the web app talks to it.

`npm run build` also puts the built web app into `backend-spring/src/main/resources/public`, which is
what the jar and the Docker image serve. After changing the web app, build it before you commit.

## Deploy it

Everything a host needs is in the repository. The step-by-step guide, with every screen, is
`docs/Brief_Launch_Roadmap_v2.pdf`; this is the short version.

**As a Docker image, anywhere:**

```powershell
docker build -t brief .                  # from this folder (the one that holds backend-spring\)
docker run --rm -p 8080:8080 brief       # http://localhost:8080 - an H2 file inside the container, forgotten on exit
```

Give the container a `DATABASE_URL` of the form `postgresql://user:password@host:5432/database` and it
uses that PostgreSQL database instead; the tables are created at the first start.

**On Render:** push the repository to GitHub, then in Render choose **New > Blueprint** and pick it.
`render.yaml` creates the web service (built from the `Dockerfile`) and a PostgreSQL 16 database, generates
the token-signing key, and connects the two. Nothing has to be typed in. After the first deploy, check the
log for the line `Database: PostgreSQL, ...`.

What the deployed application reads:

| Variable | Set by | Meaning |
|---|---|---|
| `PORT` | Render | The port to listen on |
| `DATABASE_URL` | `render.yaml`, from the database | PostgreSQL connection string; turned into JDBC settings at start-up (`config/DatabaseUrl.java`) |
| `BRIEF_JWT_SECRET` | `render.yaml`, generated once | Signs login tokens. Because it is fixed, logins survive a redeploy |
| `BRIEF_CLIENT_IP_HEADER` | `render.yaml`: `CF-Connecting-IP` | The header that carries the visitor's address behind Render's proxy, for the login rate limit |
| `BRIEF_AUTH_MODE` | `render.yaml`: `strict` | See "Two modes" below |

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
- Posts of up to 255 characters, with the limit drawn as a ruler while you type.
- **Links:** web addresses in posts open in a new tab, with or without `https://` in front
  (`pixelperfectapi.net` works). Posts are never treated as HTML.
- **Mentions:** write a member's username (`TestUser1` or `@TestUser1`) and they see a count on their
  Mentions button and in the tab title, a message on screen, and optionally a desktop notification.
- The page re-reads the feed and mentions every 15 seconds.
- API documentation (Swagger UI) at `/docs/index.html`.

Limits: alerts only reach someone who has Brief! open, in a tab or as the installed app; there is no email
or phone push yet. Desktop notifications and installing need `localhost` or HTTPS, so on a phone they work
once the site is deployed, not over `http://192.168...`. A username that is an ordinary word will be matched
whenever that word is written. A bare address becomes a link only if it ends in a common ending (`.com`,
`.net`, `.org`, `.io`, ...; the list is in `frontend/src/lib/richText.ts`); any address works with `https://`.

## API

| Method | Path | Success | Failure |
|---|---|---|---|
| POST | `/register` | 200 + account | 400 invalid, username or password against the rule, or username taken (in any capitals) |
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
| GET | `/health` | 200 `{"status":"UP"}` (Spring adds `"password_policy"`) | |

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

Send the token as `Authorization: Bearer <token>`. In the Spring Boot edition the token is a JWT
(`header.payload.signature`), signed with HMAC-SHA256, valid for 12 hours or until you sign out.

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

## API documentation

With a backend running, open **http://localhost:8080/docs/index.html** (or click "API docs" in the app).
"Try it out" sends real requests to your server. For the protected endpoints: run `POST /login`, copy the
`x-auth-token` value from the response headers, click **Authorize**, and paste it.

The page reads `frontend/public/docs/openapi.json`, which is written by hand. When you change an endpoint,
update that file too, then run `npm run build`.

## Tests

| Edition | Unit | End to end | Total |
|---|---|---|---|
| Javalin (`backend/`) | 39 | 40 | 79 |
| Spring Boot (`backend-spring/`) | 128 | 79 | 207 |

The end-to-end tests start the real application, call it over HTTP, and check status codes and JSON.
The 41 cases the two editions share are the same; the Spring Boot edition adds 38 for what 2.1, 2.2 and 2.3
brought (JWT, paging, the password rule, the rate limit, the mention table, usernames, the migrations and
foreign keys, caching, the visitor's address behind a proxy). Tests use an in-memory database and never
touch the `h2/` folders. Since 2.3 that database gets its tables from the same migration files production
runs, so every test run also tests the migrations.

`.github/workflows/ci.yml` runs all of it on GitHub for every push: both backends, the Spring Boot suite a
second time against PostgreSQL 16, a build and start of the Docker image, and the web app build.

## Documents

- `docs/EDITIONS.md` - the Javalin and Spring Boot editions side by side.
- `CHANGELOG.md` - what changed in each version.
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
