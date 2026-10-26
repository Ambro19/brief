package net.onetechly.brief.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.onetechly.brief.controller.AccountController;
import net.onetechly.brief.mail.LogMailer;
import net.onetechly.brief.mail.Mail;
import net.onetechly.brief.mail.Mailer;
import net.onetechly.brief.security.LoginRateLimiter;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Shared setup for the end-to-end tests.
 *
 * The subclasses carry @SpringBootTest, which starts the whole application on a free port
 * against an in-memory database. Before every test the database is put back to the same
 * state: account 1 = testuser1 / password, message 1 = "test message 1" by account 1.
 * (That seed row is written straight into the table, the way an account from before the
 * strong-password rule would be. The rule applies to new registrations, not to old accounts.)
 * Tests then talk to the application over real HTTP, exactly as a client would.
 *
 * Since 2.3 the tables are created by the Flyway migrations in src/main/resources/db/migration,
 * the same files production runs, and Hibernate only validates them. So every run of these
 * tests is also a test of the migrations.
 *
 * The same tests against PostgreSQL. By default the database is H2 in memory. Setting three
 * environment variables points both test classes at a PostgreSQL database instead:
 *
 *     BRIEF_TEST_DB_URL       jdbc:postgresql://localhost:5433/brief_test
 *     BRIEF_TEST_DB_USER      brief
 *     BRIEF_TEST_DB_PASSWORD  brief
 *
 * (docker compose up -d postgres-test starts exactly that database; see docker-compose.yml.)
 * Use a database kept for testing only: resetDatabase() below deletes every row before each test.
 *
 * Mail (2.4). No mail service is configured in the tests, so the application uses LogMailer:
 * every message is written to the log and remembered. The helpers at the end of this class
 * read the link a member would have found in the inbox.
 */
public abstract class ApiTestBase {

    /** The port Spring picked for this test run. */
    @LocalServerPort
    protected int port;

    /** Plain SQL access, used only to reset the tables between tests. */
    @Autowired
    protected JdbcTemplate jdbc;

    /** The application's own rate limiter, so its counts can be cleared between tests. */
    @Autowired
    protected LoginRateLimiter loginRateLimiter;

    /**
     * The application's mailer. Declared by its interface, because that is the type the bean
     * has; with no API key set it is a LogMailer, which mailbox() relies on.
     */
    @Autowired
    protected Mailer mailer;

    protected final ObjectMapper mapper = new ObjectMapper();
    protected final HttpClient client = HttpClient.newHttpClient();

    @BeforeEach
    protected void resetDatabase() {
        loginRateLimiter.reset();
        mailbox().clear();
        jdbc.execute("delete from account_token");
        jdbc.execute("delete from email_bounce");
        jdbc.execute("delete from invitation");
        jdbc.execute("delete from mention");
        jdbc.execute("delete from message");
        jdbc.execute("delete from account");
        // Start the generated ids from 1 again, so every test sees the same ids.
        jdbc.execute("alter table mention alter column mention_id restart with 1");
        jdbc.execute("alter table message alter column message_id restart with 1");
        jdbc.execute("alter table account alter column account_id restart with 1");
        jdbc.execute("alter table account_token alter column token_id restart with 1");
        jdbc.execute("alter table invitation alter column invitation_id restart with 1");
        jdbc.execute("alter table email_bounce alter column bounce_id restart with 1");
        // The seed password is plain text on purpose: it exercises the "upgrade to a hash on first login" path.
        // username_key is the lower-case form the application compares names by (Account.keyOf).
        // The row has no email address: it stands for an account from before 2.4.
        jdbc.update("insert into account (username, username_key, password) values (?, ?, ?)",
                "testuser1", "testuser1", "password");
        jdbc.update("insert into message (posted_by, message_text, time_posted_epoch) values (?, ?, ?)",
                1, "test message 1", 1669947792L);
    }

    protected HttpResponse<String> get(String path) throws IOException, InterruptedException {
        return send("GET", path, null, null);
    }

    protected HttpResponse<String> send(String method, String path, String jsonBody, String token)
            throws IOException, InterruptedException {
        return send(method, path, jsonBody, token, Map.of());
    }

    /** As above, with extra request headers (used to play the part of a proxy in front of the application). */
    protected HttpResponse<String> send(String method, String path, String jsonBody, String token,
                                        Map<String, String> extraHeaders) throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json");
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        extraHeaders.forEach(builder::header);
        HttpRequest.BodyPublisher body = jsonBody == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(jsonBody);
        builder.method(method, body);
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    protected static String accountJson(String username, String password) {
        return "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}";
    }

    protected static String messageJson(int postedBy, String text, long epoch) {
        return "{\"posted_by\":" + postedBy + ",\"message_text\":\"" + text + "\",\"time_posted_epoch\":" + epoch + "}";
    }

    protected static String textJson(String text) {
        return "{\"message_text\":\"" + text + "\"}";
    }

    protected static String repeat(char c, int n) {
        return String.valueOf(c).repeat(n);
    }

    /** Checks an account JSON object field by field - which also checks the JSON key names. */
    protected void assertAccount(String json, int accountId, String username, String password) throws IOException {
        JsonNode node = mapper.readTree(json);
        assertEquals(accountId, node.get("account_id").asInt());
        assertEquals(username, node.get("username").asText());
        assertEquals(password, node.get("password").asText());
    }

    /** Checks a message JSON object field by field - which also checks the JSON key names. */
    protected void assertMessage(JsonNode node, int messageId, int postedBy, String text, long epoch) {
        assertEquals(4, node.size(), "a message has exactly four fields");
        assertEquals(messageId, node.get("message_id").asInt());
        assertEquals(postedBy, node.get("posted_by").asInt());
        assertEquals(text, node.get("message_text").asText());
        assertEquals(epoch, node.get("time_posted_epoch").asLong());
    }

    protected void assertMessage(String json, int messageId, int postedBy, String text, long epoch) throws IOException {
        assertMessage(mapper.readTree(json), messageId, postedBy, text, epoch);
    }

    protected void assertSeedMessage(JsonNode node) {
        assertMessage(node, 1, 1, "test message 1", 1669947792L);
    }

    // ------------------------------------------------------------------ accounts with email (2.4)

    /** The JSON body of POST /register with an email address. */
    protected static String registrationJson(String username, String password, String email) {
        return "{\"username\":\"" + username + "\",\"password\":\"" + password + "\",\"email\":\"" + email + "\"}";
    }

    /** As above, with the secret of an invitation link (2.5). A null email leaves the field out. */
    protected static String registrationJson(String username, String password, String email, String invitation) {
        return "{\"username\":\"" + username + "\",\"password\":\"" + password + "\""
                + (email == null ? "" : ",\"email\":\"" + email + "\"")
                + ",\"invitation\":\"" + invitation + "\"}";
    }

    protected static String emailJson(String email) {
        return "{\"email\":\"" + email + "\"}";
    }

    protected static String tokenJson(String secret) {
        return "{\"token\":\"" + secret + "\"}";
    }

    /** Signs in and returns the login token. */
    protected String loginToken(String username, String password) throws IOException, InterruptedException {
        HttpResponse<String> response = send("POST", "/login", accountJson(username, password), null);
        assertEquals(200, response.statusCode());
        return response.headers().firstValue(AccountController.TOKEN_HEADER).orElseThrow();
    }

    /** What the application "sent": with no mail service configured, the messages are kept here. */
    protected LogMailer mailbox() {
        return assertInstanceOf(LogMailer.class, mailer, "the tests run without a mail service");
    }

    /**
     * The secret of the newest link of this kind ("verify", "reset" or "invite") mailed to this address.
     * Fails the test if no such mail was sent.
     */
    protected String secretMailedTo(String email, String kind) {
        Mail mail = mailbox().lastTo(email);
        assertNotNull(mail, "no mail was sent to " + email);
        String marker = "/?" + kind + "=";
        int at = mail.text().indexOf(marker);
        assertTrue(at >= 0, "the newest mail to " + email + " carries no " + kind + " link: " + mail.subject());
        int end = mail.text().indexOf('\n', at);
        return mail.text().substring(at + marker.length(), end < 0 ? mail.text().length() : end).trim();
    }

    /** Opens the confirmation link that was mailed to this address, as its owner would. */
    protected void confirmEmail(String email) throws IOException, InterruptedException {
        assertEquals(200, send("POST", "/email/verify", tokenJson(secretMailedTo(email, "verify")), null).statusCode());
    }

    /**
     * Creates an account with a confirmed email address (username at example.test) and returns
     * its login token: a member who can post, as the tests of everything else need one.
     */
    protected String registerConfirmed(String username, String password) throws IOException, InterruptedException {
        String email = username.toLowerCase(java.util.Locale.ROOT) + "@example.test";
        assertEquals(200, send("POST", "/register", registrationJson(username, password, email), null).statusCode());
        confirmEmail(email);
        return loginToken(username, password);
    }
}
