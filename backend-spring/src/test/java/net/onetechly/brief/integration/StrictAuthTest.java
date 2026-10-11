package net.onetechly.brief.integration;

import com.fasterxml.jackson.databind.JsonNode;
import net.onetechly.brief.controller.AccountController;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Strict mode: writing needs a login token, and members can only act as themselves.
 * Reading stays public. The first eight cases are the same as the Javalin edition's
 * StrictAuthTest; the rest cover what 2.1 added (JWT, the strong password rule, the login
 * rate limit) and what 2.3 added (the username rule, and the visitor's address behind a proxy).
 *
 * brief.client-ip.header is set, as it is on a deployment: the application reads the visitor's
 * address from that header when a request carries it. Requests without the header are counted
 * by the address of the connection, which is what every test before 2.3 relies on.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "brief.auth.mode=strict",
                "brief.client-ip.header=" + StrictAuthTest.PROXY_HEADER,
                "spring.datasource.url=${BRIEF_TEST_DB_URL:jdbc:h2:mem:brief-strict;DB_CLOSE_DELAY=-1}",
                "spring.datasource.username=${BRIEF_TEST_DB_USER:sa}",
                "spring.datasource.password=${BRIEF_TEST_DB_PASSWORD:sa}"})
class StrictAuthTest extends ApiTestBase {

    /** The header a proxy in front of the application would put the visitor's address in. */
    static final String PROXY_HEADER = "X-Test-Client-Ip";

    private String loginToken(String username, String password) throws Exception {
        HttpResponse<String> response = send("POST", "/login", accountJson(username, password), null);
        assertEquals(200, response.statusCode());
        return response.headers().firstValue(AccountController.TOKEN_HEADER).orElseThrow();
    }

    /** Registers a second member (account id 2) and returns their token. */
    private String registerSecondUser() throws Exception {
        assertEquals(200, send("POST", "/register", accountJson("second", "secret12"), null).statusCode());
        return loginToken("second", "secret12");
    }

    @Test
    void readingIsPublic() throws Exception {
        assertEquals(200, get("/messages").statusCode());
        assertEquals(200, get("/messages/1").statusCode());
        assertEquals(200, get("/accounts/1/messages").statusCode());
        assertEquals(200, get("/accounts/1/mentions").statusCode());
        assertEquals(200, get("/accounts").statusCode());
    }

    @Test
    void writingWithoutATokenIs401AndChangesNothing() throws Exception {
        assertEquals(401, send("POST", "/messages", messageJson(1, "hello", 1L), null).statusCode());
        assertEquals(401, send("PATCH", "/messages/1", textJson("hacked"), null).statusCode());
        assertEquals(401, send("DELETE", "/messages/1", null, null).statusCode());
        assertEquals(401, send("DELETE", "/messages/1", null, "made-up-token").statusCode());

        assertSeedMessage(mapper.readTree(get("/messages/1").body()));
    }

    @Test
    void ownerCanPostEditAndDelete() throws Exception {
        String token = loginToken("testuser1", "password");

        HttpResponse<String> created = send("POST", "/messages", messageJson(1, "mine", 1700000000L), token);
        assertEquals(200, created.statusCode());
        assertMessage(created.body(), 2, 1, "mine", 1700000000L);

        HttpResponse<String> updated = send("PATCH", "/messages/2", textJson("mine, edited"), token);
        assertEquals(200, updated.statusCode());
        assertMessage(updated.body(), 2, 1, "mine, edited", 1700000000L);

        HttpResponse<String> deleted = send("DELETE", "/messages/2", null, token);
        assertEquals(200, deleted.statusCode());
        assertEquals("", get("/messages/2").body());
    }

    @Test
    void cannotPostAsSomeoneElse() throws Exception {
        String second = registerSecondUser();
        assertEquals(403, send("POST", "/messages", messageJson(1, "pretending to be user 1", 1L), second).statusCode());
        assertEquals(200, send("POST", "/messages", messageJson(2, "as myself", 1L), second).statusCode());
    }

    @Test
    void cannotEditOrDeleteSomeoneElsesMessage() throws Exception {
        String second = registerSecondUser();
        assertEquals(403, send("PATCH", "/messages/1", textJson("not mine"), second).statusCode());
        assertEquals(403, send("DELETE", "/messages/1", null, second).statusCode());
        assertSeedMessage(mapper.readTree(get("/messages/1").body()));
    }

    @Test
    void deleteOfMissingMessageIsStill200ForASignedInUser() throws Exception {
        String token = loginToken("testuser1", "password");
        HttpResponse<String> response = send("DELETE", "/messages/999", null, token);
        assertEquals(200, response.statusCode());
        assertEquals("", response.body());
    }

    @Test
    void serverFillsInThePostTimeWhenTheClientSendsNone() throws Exception {
        String token = loginToken("testuser1", "password");
        long before = System.currentTimeMillis() / 1000;
        HttpResponse<String> created = send("POST", "/messages", messageJson(1, "no time sent", 0L), token);
        assertEquals(200, created.statusCode());
        assertTrue(mapper.readTree(created.body()).get("time_posted_epoch").asLong() >= before);
    }

    @Test
    void logoutEndsTheSession() throws Exception {
        String token = loginToken("testuser1", "password");
        assertEquals(200, send("POST", "/logout", null, token).statusCode());
        assertEquals(401, send("POST", "/messages", messageJson(1, "after logout", 1L), token).statusCode());
        assertEquals(200, send("POST", "/logout", null, token).statusCode()); // repeat is fine
    }

    // ---- JWT (2.1) ----

    private static String decode(String base64Url) {
        return new String(Base64.getUrlDecoder().decode(base64Url), StandardCharsets.UTF_8);
    }

    @Test
    void theLoginTokenIsAJwtForTheRightAccount() throws Exception {
        String[] parts = loginToken("testuser1", "password").split("\\.");
        assertEquals(3, parts.length, "header.payload.signature");

        assertEquals("HS256", mapper.readTree(decode(parts[0])).get("alg").asText());
        JsonNode payload = mapper.readTree(decode(parts[1]));
        assertEquals("1", payload.get("sub").asText());
        assertTrue(payload.get("exp").asLong() > System.currentTimeMillis() / 1000);
        assertFalse(decode(parts[1]).contains("password"), "nothing secret belongs in a token's payload");
    }

    @Test
    void aTokenWhosePayloadWasChangedIsRejected() throws Exception {
        registerSecondUser(); // account 2 exists, so "pretend to be account 2" is a meaningful attack
        String[] parts = loginToken("testuser1", "password").split("\\.");
        String forgedPayload = Base64.getUrlEncoder().withoutPadding().encodeToString(
                decode(parts[1]).replace("\"sub\":\"1\"", "\"sub\":\"2\"").getBytes(StandardCharsets.UTF_8));
        String forged = parts[0] + "." + forgedPayload + "." + parts[2];

        HttpResponse<String> response = send("POST", "/messages", messageJson(2, "forged", 1L), forged);
        assertEquals(401, response.statusCode());
        assertEquals("", response.body());
    }

    @Test
    void aBadTokenDoesNotBlockPublicReading() throws Exception {
        assertEquals(200, send("GET", "/messages", null, "made-up-token").statusCode());
    }

    @Test
    void signingOutOneTokenLeavesAnotherLoginOfTheSameMemberWorking() throws Exception {
        String phone = loginToken("testuser1", "password");
        String laptop = loginToken("testuser1", "password");
        assertEquals(200, send("POST", "/logout", null, phone).statusCode());

        assertEquals(401, send("POST", "/messages", messageJson(1, "from the phone", 1L), phone).statusCode());
        assertEquals(200, send("POST", "/messages", messageJson(1, "from the laptop", 1L), laptop).statusCode());
    }

    // ---- strong passwords (2.1) ----

    @Test
    void registrationNeedsAStrongPassword() throws Exception {
        assertEquals(400, send("POST", "/register", accountJson("newbie", "password"), null).statusCode());  // no digit
        assertEquals(400, send("POST", "/register", accountJson("newbie", "12345678"), null).statusCode());  // no letter
        assertEquals(400, send("POST", "/register", accountJson("newbie", "abc1234"), null).statusCode());   // 7 characters
        assertEquals(400, send("POST", "/register", accountJson("newbie12", "newbie12"), null).statusCode()); // same as the username

        HttpResponse<String> accepted = send("POST", "/register", accountJson("newbie", "secret12"), null);
        assertEquals(200, accepted.statusCode());
        assertAccount(accepted.body(), 2, "newbie", "secret12");
    }

    @Test
    void healthTellsTheWebAppWhichPasswordRuleApplies() throws Exception {
        assertEquals("strong", mapper.readTree(get("/health").body()).get("password_policy").asText());
    }

    // ---- login rate limit (2.1) ----

    @Test
    void fiveWrongPasswordsLockTheLoginEvenForTheRightPassword() throws Exception {
        for (int attempt = 1; attempt <= 5; attempt++) {
            assertEquals(401, send("POST", "/login", accountJson("testuser1", "wrong"), null).statusCode());
        }

        HttpResponse<String> locked = send("POST", "/login", accountJson("testuser1", "password"), null);
        assertEquals(429, locked.statusCode());
        assertEquals("", locked.body());
        long retryAfter = Long.parseLong(locked.headers().firstValue("Retry-After").orElseThrow());
        assertTrue(retryAfter > 0 && retryAfter <= 15 * 60, "Retry-After should be within the 15 minute lock-out");
        assertTrue(locked.headers().firstValue(AccountController.TOKEN_HEADER).isEmpty(), "no token while locked out");
    }

    @Test
    void theLockIsPerUsernameAndASuccessClearsTheCount() throws Exception {
        registerSecondUser();
        for (int attempt = 1; attempt <= 5; attempt++) {
            send("POST", "/login", accountJson("testuser1", "wrong"), null);
        }
        assertEquals(429, send("POST", "/login", accountJson("testuser1", "password"), null).statusCode());
        assertEquals(200, send("POST", "/login", accountJson("second", "secret12"), null).statusCode(), "another member is not affected");

        // four misses, a hit, four more misses: never five in a row, so never locked
        for (int attempt = 1; attempt <= 4; attempt++) {
            send("POST", "/login", accountJson("second", "wrong"), null);
        }
        assertEquals(200, send("POST", "/login", accountJson("second", "secret12"), null).statusCode());
        for (int attempt = 1; attempt <= 4; attempt++) {
            send("POST", "/login", accountJson("second", "wrong"), null);
        }
        assertEquals(200, send("POST", "/login", accountJson("second", "secret12"), null).statusCode());
    }

    // ---- the username rule (2.3) ----

    @Test
    void registrationNeedsAUsernameMadeOfPlainLettersDigitsDotsDashesOrUnderscores() throws Exception {
        assertEquals(400, send("POST", "/register", accountJson("ab", "secret12"), null).statusCode());            // too short
        assertEquals(400, send("POST", "/register", accountJson("two words", "secret12"), null).statusCode());     // a space
        assertEquals(400, send("POST", "/register", accountJson("maya#2", "secret12"), null).statusCode());        // a symbol
        assertEquals(400, send("POST", "/register", accountJson("a".repeat(31), "secret12"), null).statusCode());  // too long

        HttpResponse<String> accepted = send("POST", "/register", accountJson("Nora.Lane-7_x", "secret12"), null);
        assertEquals(200, accepted.statusCode());
        assertAccount(accepted.body(), 2, "Nora.Lane-7_x", "secret12");
        assertEquals(2, mapper.readTree(get("/accounts").body()).size(), "only the valid name was registered");
    }

    @Test
    void capitalsDoNotMatterForRegisteringSigningInOrTheLockOut() throws Exception {
        registerSecondUser();
        assertEquals(400, send("POST", "/register", accountJson("SECOND", "secret12"), null).statusCode(), "already taken");
        assertNotNull(loginToken("Second", "secret12"));

        // Wrong passwords typed with different capitals all count against the same name.
        String[] spellings = {"testuser1", "TestUser1", "TESTUSER1", "tESTUSER1", "Testuser1"};
        for (String spelling : spellings) {
            assertEquals(401, send("POST", "/login", accountJson(spelling, "wrong"), null).statusCode());
        }
        assertEquals(429, send("POST", "/login", accountJson("testuser1", "password"), null).statusCode());
    }

    // ---- the visitor's address behind a proxy (2.3) ----

    private HttpResponse<String> loginFrom(String visitorAddress, String username, String password) throws Exception {
        return send("POST", "/login", accountJson(username, password), null, Map.of(PROXY_HEADER, visitorAddress));
    }

    @Test
    void theLockOutCountsByTheVisitorsAddressThatTheProxyPassesOn() throws Exception {
        for (int attempt = 1; attempt <= 5; attempt++) {
            assertEquals(401, loginFrom("203.0.113.7", "testuser1", "wrong").statusCode(), "attempt " + attempt);
        }
        // That visitor is locked out...
        assertEquals(429, loginFrom("203.0.113.7", "testuser1", "password").statusCode());
        // ...but the member, arriving through the same proxy from another address, is not.
        assertEquals(200, loginFrom("198.51.100.20", "testuser1", "password").statusCode());
        // Nor is a request that carries no such header at all.
        assertEquals(200, send("POST", "/login", accountJson("testuser1", "password"), null).statusCode());
    }

    @Test
    void aNewIpv6AddressFromTheSameBlockForEveryGuessDoesNotEscapeTheLockOut() throws Exception {
        // One internet line owns a whole block of IPv6 addresses (a /64) and can use a different one each time.
        for (int attempt = 1; attempt <= 5; attempt++) {
            assertEquals(401, loginFrom("2001:db8:12:34::" + attempt, "testuser1", "wrong").statusCode(), "attempt " + attempt);
        }
        assertEquals(429, loginFrom("2001:db8:12:34:aaaa:bbbb:cccc:dddd", "testuser1", "password").statusCode());
        // Another block is another visitor.
        assertEquals(200, loginFrom("2001:db8:12:35::1", "testuser1", "password").statusCode());
    }

    @Test
    void aHeaderThatIsNotAnAddressDoesNotGiveEachAttemptAFreshCount() throws Exception {
        // Someone guessing passwords sends a different made-up value every time.
        for (int attempt = 1; attempt <= 5; attempt++) {
            assertEquals(401, loginFrom("made-up-" + attempt, "testuser1", "wrong").statusCode());
        }
        // All five fell back to the address of the connection, so together they reached the limit.
        assertEquals(429, loginFrom("made-up-6", "testuser1", "password").statusCode());
        assertEquals(429, send("POST", "/login", accountJson("testuser1", "password"), null).statusCode());
    }
}
