# Changelog

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
