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
| Database access | `DAO/AccountDAO.java`, `DAO/MessageDAO.java`: SQL, `PreparedStatement`, `ResultSet`, about 240 lines | `repository/AccountRepository.java`, `MessageRepository.java`, `MentionRepository.java`: three **interfaces** with thirteen method declarations between them and no method bodies. Spring Data writes the implementation |
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

## What the Spring edition does not have yet

- Flyway migrations instead of `ddl-auto=update`, including a foreign key from message to account
- springdoc-openapi to generate the API description from the code
- A shared store (Redis) for the rate-limit counts and revoked tokens, needed with more than one server
- Refresh tokens: today a login lasts 12 hours and then the member signs in again
