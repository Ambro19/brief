# Brief!: the Javalin edition and the Spring Boot edition, side by side

Both editions implement the same original API and pass the same 41 shared end-to-end test cases. The
point of having both is to see **what the framework does for you** - everything in the right-hand column
that is shorter than the left is work Spring took over.

Since 2.1 the Spring Boot edition also has things the Javalin edition does not: Spring Security, JWT,
paging, a login rate limit, a strong password rule, (2.2) indexed mentions and (2.3) everything a
deployment needs: PostgreSQL, Flyway migrations, foreign keys, paged mentions, a Docker image. Those are
listed in their own sections below.

**Status:** the Javalin edition is frozen at 1.1.0 (79 of 79 tests). New work happens in the Spring Boot
edition only.

## File by file

| Job | Javalin edition (`backend/`) | Spring Boot edition (`backend-spring/`) |
|---|---|---|
| Start the app | `Main.java`: read settings, create the controller, `app.start(port)` | `BriefApplication.java`: `SpringApplication.run(...)`; settings in `application.properties` |
| Table rows as objects | `Model/Account.java`, `Model/Message.java` (plain classes) | `entity/Account.java`, `entity/Message.java` (`@Entity`: Hibernate maps them to tables) |
| Database access | `DAO/AccountDAO.java`, `DAO/MessageDAO.java`: SQL, `PreparedStatement`, `ResultSet`, about 240 lines | `repository/AccountRepository.java`, `MessageRepository.java`, `MentionRepository.java`, `AccountTokenRepository.java`: four **interfaces** with sixteen method declarations between them and no method bodies. Spring Data writes the implementation |
| Connections | `Util/ConnectionUtil.java`: a connection pool, try-with-resources everywhere | Nothing to write. Spring Boot configures a pool (HikariCP) from `spring.datasource.*` |
| Creating tables | `schema.sql`, run at startup by `ConnectionUtil` | Until 2.2: `spring.jpa.hibernate.ddl-auto=update`, Hibernate creates tables from the entities. Since 2.3: numbered SQL files in `db/migration`, run by **Flyway**; Hibernate only checks them (`validate`) |
| Input rules | `if` statements in the services (`isBlank()`, `length() > 255`) | Annotations on the request records (`@NotBlank`, `@Size(max = 255)`) + `@Valid` in the controller |
| Rules that need the database | Services return `null` when a rule is broken | Services throw `BadRequestException`, `UnauthorizedException`, ... |
| Turning results into status codes | In every handler: `if (x == null) { ctx.status(400); return; }` | Once, in `exception/ApiExceptionHandler.java` (`@RestControllerAdvice`) |
| Routes | `app.post("/register", this::registerHandler)` in `startAPI()` | `@PostMapping("/register")` on the method itself |
| Reading the request | `mapper.readValue(ctx.body(), Account.class)`, `ctx.pathParam("id")`, `Integer.parseInt(...)` | Method parameters: `@RequestBody Credentials c`, `@PathVariable("id") int id` |
| Writing the response | `ctx.json(object)` | `return object;` |
| Wiring objects together | `new AccountService(new AccountDAO())` in the controller's constructor | Constructor parameters; Spring creates and passes the objects (dependency injection) |
| Who may write | `allowWriteAs(...)`, a private method in the controller, called by each handler | Two layers. "Is anyone signed in?" - URL rules in `config/SecurityConfig.java`, enforced by Spring Security before the controller runs. "Is it your post?" - `security/AuthGuard.java` |
| Reading the token | `ctx.header("Authorization")` inside `allowWriteAs` | `security/JwtAuthenticationFilter.java`, a servlet filter that runs once per request and records the caller in the `SecurityContext` |
| Login tokens | `Service/SessionService.java`: random text, remembered in a map | `security/JwtService.java`: a signed JWT; the server remembers nothing except tokens that were signed out |
| Password hashing | `Util/PasswordHasher.java` | The same class |
| Mentions | `Service/MentionService.java`: read every message and search its text, at every request | The same whole-word rule, applied once when a post is saved; the result is stored in the `mention` table (`entity/Mention.java`) and read back through an index |
| Unit tests | JUnit 4; hand-written fake DAOs (subclasses with a `Map`) | JUnit 5; Mockito mocks of the repository interfaces |
| End-to-end tests | Start Javalin on a free port; reset with a SQL script | `@SpringBootTest(webEnvironment = RANDOM_PORT)`; reset with `JdbcTemplate` |
| One runnable jar | `maven-shade-plugin` | `spring-boot-maven-plugin` |
| Web server inside | Jetty | Tomcat |

## Concept by concept

| Concept | By hand (Javalin edition) | With Spring |
|---|---|---|
| **Dependency injection** | You call `new` and pass objects along | You declare what a class needs in its constructor; the container supplies it |
| **ORM** | You write SQL and copy columns into objects (`mapRow`) | You describe the mapping once with annotations; Hibernate writes the SQL |
| **Derived queries** | `select ... where posted_by = ? order by message_id` | A method named `findByPostedByOrderByMessageIdAsc` |
| **Validation** | Code you write and must remember to call | Declared on the data; runs before your method |
| **Error handling** | Repeated in each handler | One class for the whole API |
| **Configuration** | `System.getenv(...)` | `application.properties`, profiles, `@Value` |
| **Auto-configuration** | - | Spring Boot sees a JDBC driver and JPA on the classpath and sets up the database layer |
| **Cross-cutting checks** | A method each handler must remember to call | A filter chain that every request passes through, whether or not the handler remembers |

## What Spring costs

- **It is less visible.** In the Javalin edition you can read every line that runs. In the Spring edition
  a repository method has no body, and a mistake in a method name is found at startup, not by the compiler.
- **Startup is slower** and the jar is larger.
- **You have to know the conventions.** Why does `/` serve `index.html`? Because Spring Boot serves
  `classpath:/public/` by default. Nothing in the code says so.

Being able to explain the left column is what makes the right column understandable, not magic.

## Added in 2.1 (Spring Boot edition only)

| Feature | Where | The idea |
|---|---|---|
| Spring Security | `config/SecurityConfig.java` | A chain of filters in front of every controller. This app's chain: stateless, no CSRF (no cookies), its own `/logout`, bare 401/403 |
| JWT | `security/JwtService.java`, `JwtAuthenticationFilter.java` | `header.payload.signature`. The payload says who and until when; the signature (HMAC-SHA256 with a server-only key) proves the server wrote it. The server checks a token without looking anything up |
| Sign-out with JWT | `JwtService.revoke` | A signed token stays valid until it expires, so sign-out keeps a list of revoked token ids |
| Paging | `MessageRepository` (`Pageable`), `MessageService.getPage`, `controller/Paging.java` | "Cursor" paging: `?limit=10&before=<last id>`. One extra row is fetched to learn whether more exist (`X-Has-More`) |
| Login rate limit | `security/LoginRateLimiter.java` | Count attempts per username and address **before** checking the password; refuse with 429 after five |
| Password rule | `security/PasswordPolicy.java` | 8+ characters, a letter and a digit, not the username; at registration, in strict mode |
| One switch for the mode | `security/AuthMode.java` | strict or compat is read in one place and injected where needed |

### Session tokens (1.x, 2.0) and JWT (2.1)

| | Random token in a map | JWT |
|---|---|---|
| What the token is | 32 random bytes; means nothing by itself | Readable data plus a signature |
| How the server checks it | Looks it up in memory | Recomputes the signature with its key |
| Server memory | One entry per login | Nothing per login (only signed-out ids) |
| Several servers | All would need the same map | All need only the same key |
| Sign-out | Delete the entry | Needs a revocation list, or short lifetimes |
| Restart | Everyone is signed out | Tokens survive if the key is configured |
| Risk to know | - | The payload is not secret. Never put a password in it. Never accept a token without checking the signature |

The JWT code here uses only the JDK so every step is visible. In a larger system the usual choice is a
maintained library (jjwt, or Spring's `spring-boot-starter-oauth2-resource-server`).

## Added in 2.2 (Spring Boot edition only)

| Feature | Where | The idea |
|---|---|---|
| Mention table | `entity/Mention.java`, `repository/MentionRepository.java` | Do the expensive work once, at write time, and store the answer. A composite index `(account_id, message_id)` makes the read a look-up; a unique constraint keeps one row per message and member |
| Keeping it in step | `MentionService.index`, `remove` | After an edit, change only the rows that differ |
| All or nothing | `@Transactional` on `MessageService.create`, `updateText`, `delete` | The post and its mention rows are stored together or not at all |
| A query written out | `AccountRepository.findByUsernameContainedIn` (`@Query`, JPQL) | For the cases a method name cannot express |
| Upgrading old data | `service/MentionBackfill.java` (`ApplicationRunner`) | Code that runs once at start-up to fill the new table from existing posts |

Read-time search versus write-time index:

| | Search when asked (Javalin, Spring up to 2.1) | Index when written (Spring 2.2) |
|---|---|---|
| Work per "who mentioned me?" | Reads every post | Reads only that member's rows |
| Work per new post | None | One query to find the mentioned members, a few inserts |
| Asked how often | Every 15 seconds per signed-in member | Once per post |
| A member registers after being named | Becomes a mention | Does not |
| Code | Simple | More: three write paths must stay in step |

## Added in 2.3 (Spring Boot edition only)

| Feature | Where | The idea |
|---|---|---|
| Versioned migrations | `src/main/resources/db/migration/V1__...sql`, `V2__...`, `V3__...` (Flyway) | The schema changes through numbered SQL files that run once each, in order, on every database. Hibernate only checks the result (`ddl-auto=validate`). A file that has run is never edited; a change is a new file |
| Two databases, one SQL | the same files | Written in the SQL that H2 and PostgreSQL share, so the tests (H2) and production (PostgreSQL) run the same migrations |
| Settings from the host | `config/DatabaseUrl.java` | A host hands over one connection string; it is turned into Spring's settings before Spring starts |
| The visitor's address | `security/ClientAddress.java`, `AddressGroup.java` | Behind a proxy the connection's address is the proxy's. Trust exactly one header, the one the platform sets |
| Caching static files | `config/WebConfig.java` | Files with a fingerprint in their name can be kept by the browser for a year |

## Added in 2.4 (Spring Boot edition only)

| Feature | Where | The idea |
|---|---|---|
| One-time links | `service/AccountTokens.java`, `entity/AccountToken.java` | A long random secret goes into the mail; the database keeps only its SHA-256 hash, as with passwords. A link has a purpose, an expiry time and a "used" mark |
| Confirming an address | `service/AccountEmailService.java` | Only someone who can read the inbox can send the secret back. Until then: read, but not post |
| Resetting a password | the same service | The answer to "Forgot password?" never tells whether the address is known. A new password ends every session |
| Ending sessions with JWT | `Account.tokenVersion`, claim `ver`, `JwtAuthenticationFilter` | A signed token cannot be taken back, so the token carries a number and the account row holds the current one. Costs one look-up by primary key per request with a token: the price of being able to say "not any more" |
| An interface with two implementations | `mail/Mailer.java`, `LogMailer`, `ResendMailer`, `config/MailConfig.java` (`@Bean`) | The services ask for "a Mailer". Which one they get is decided once, from configuration. The tests get the one that sends nothing |
| Calling another service | `mail/ResendMailer.java` (`java.net.http.HttpClient`) | In the background, with a time limit, never failing the member's request, never logging the key |
| Rules that depend on configuration | `config/SecurityConfig.java`, `security/BoardAccess.java` | The filter chain is built at start-up; a closed board adds rules to it. A setting that makes no sense (a code in compat mode) stops the start instead of being ignored |
| Writing only what changed | `@DynamicUpdate` on `entity/Account.java` | By default Hibernate's UPDATE names every column, so two overlapping changes to one row can undo each other |
| Deploy what passed | `render.yaml` (`autoDeployTrigger: checksPass`), `.github/workflows/ci.yml` | The tests on GitHub are the gate in front of production |

## Added in 2.5 (Spring Boot edition only)

| Feature | Where | The idea |
|---|---|---|
| Invitations | `service/InvitationService.java`, `entity/Invitation.java`, `db/migration/V4__invitations.sql` | A secret per person instead of one code for everyone: it can be traced, withdrawn, used once. Mailed, it also proves the inbox, so the new account needs no separate confirmation |
| A role from configuration | `security/BoardOwner.java` | Who may invite is one setting (`BRIEF_OWNER_USERNAME`), not a column a request could change |
| A safe upgrade path | `security/BoardAccess.java` | An old setting that meant "closed" still means closed. Changing what a setting means must never quietly open a door |
| Deleting an account | `service/AccountDeletionService.java` | The code deletes what the database cannot (posts, whose author must exist); the database deletes the rest through `on delete cascade` and `on delete set null` |
| Static pages next to the app | `public/terms.html`, `privacy.html` | Plain HTML served by the same backend; no route, no code |

## Added in 2.7 (Spring Boot edition only)

| Feature | Where | The idea |
|---|---|---|
| Receiving a webhook | `controller/ResendWebhookController.java` | Another service calls Brief!. No login token: the signature is the authentication, and every unknown event is answered 200 so the sender never retries it |
| Checking a signature | `mail/ResendWebhookVerifier.java` | HMAC-SHA256 over the raw body, a five-minute window against replays, and a comparison that takes the same time whatever differs |
| The raw body | `@RequestBody String` | The signature covers the bytes as sent; reading them into objects first could change them |
| A check and a write in one statement | `AccountTokenRepository.claim`, `InvitationRepository.claim` | `update ... where used_at is null`: the database decides who was first, not the application |
| A new status code | `exception/UndeliverableAddressException.java` (422) | "This address cannot receive mail" is a different answer from "this address is taken" (400), so the web app can say the right thing |

## What the Spring edition does not have yet

- springdoc-openapi to generate the API description from the code
- A shared store (Redis) for the rate-limit counts and revoked tokens, needed with more than one server
- Refresh tokens: today a login lasts 12 hours and then the member signs in again
