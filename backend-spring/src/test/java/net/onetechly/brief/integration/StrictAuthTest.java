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
 * rate limit), what 2.3 added (the username rule, and the visitor's address behind a proxy),
 * and what 2.4 added (email on accounts, the confirmation link, the forgotten password).
 *
 * Since 2.4 a new account needs an email address in this mode, and can post only once the
 * address is confirmed. The helpers register(...) and registerConfirmed(...) take care of that
 * for the tests that are about something else.
 *
 * brief.client-ip.header is set, as it is on a deployment: the application reads the visitor's
 * address from that header when a request carries it. Requests without the header are counted
 * by the address of the connection, which is what every test before 2.3 relies on.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "brief.auth.mode=strict",
                // Stated here so that a variable set on the machine that runs the tests can never
                // close the board under them, or make them send real mail.
                "brief.invite-code=",
                "brief.board=open",
                "brief.owner-username=",
                "brief.mail.resend-api-key=",
                "brief.client-ip.header=" + StrictAuthTest.PROXY_HEADER,
                "spring.datasource.url=${BRIEF_TEST_DB_URL:jdbc:h2:mem:brief-strict;DB_CLOSE_DELAY=-1}",
                "spring.datasource.username=${BRIEF_TEST_DB_USER:sa}",
                "spring.datasource.password=${BRIEF_TEST_DB_PASSWORD:sa}"})
class StrictAuthTest extends ApiTestBase {

    /** The header a proxy in front of the application would put the visitor's address in. */
    static final String PROXY_HEADER = "X-Test-Client-Ip";

    /** Registers a second member (account id 2) with a confirmed email address, and returns their token. */
    private String registerSecondUser() throws Exception {
        return registerConfirmed("second", "secret12");
    }

    /** POST /register with a valid email address, so that what the test varies is the only thing that can be wrong. */
    private HttpResponse<String> register(String username, String password) throws Exception {
        return send("POST", "/register", registrationJson(username, password, "someone@example.test"), null);
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
        assertEquals(400, register("newbie", "password").statusCode());  // no digit
        assertEquals(400, register("newbie", "12345678").statusCode());  // no letter
        assertEquals(400, register("newbie", "abc1234").statusCode());   // 7 characters
        assertEquals(400, register("newbie12", "newbie12").statusCode()); // same as the username

        HttpResponse<String> accepted = register("newbie", "secret12");
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
        assertEquals(400, register("ab", "secret12").statusCode());            // too short
        assertEquals(400, register("two words", "secret12").statusCode());     // a space
        assertEquals(400, register("maya#2", "secret12").statusCode());        // a symbol
        assertEquals(400, register("a".repeat(31), "secret12").statusCode());  // too long

        HttpResponse<String> accepted = register("Nora.Lane-7_x", "secret12");
        assertEquals(200, accepted.statusCode());
        assertAccount(accepted.body(), 2, "Nora.Lane-7_x", "secret12");
        assertEquals(2, mapper.readTree(get("/accounts").body()).size(), "only the valid name was registered");
    }

    @Test
    void capitalsDoNotMatterForRegisteringSigningInOrTheLockOut() throws Exception {
        registerSecondUser();
        assertEquals(400, register("SECOND", "secret12").statusCode(), "already taken");
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

    // ---- email on accounts (2.4) ----

    @Test
    void healthTellsTheWebAppThatAnEmailIsRequiredAndTheBoardIsOpen() throws Exception {
        JsonNode health = mapper.readTree(get("/health").body());
        assertEquals("required", health.get("email").asText());
        assertEquals("open", health.get("board").asText());
    }

    @Test
    void aNewAccountNeedsAnEmailAddress() throws Exception {
        assertEquals(400, send("POST", "/register", accountJson("newbie", "secret12"), null).statusCode(), "no address");
        assertEquals(400, send("POST", "/register", registrationJson("newbie", "secret12", "  "), null).statusCode(), "blank");
        for (String notAnAddress : new String[] {"newbie", "newbie@", "@example.test", "newbie@example", "a b@example.test",
                "newbie@@example.test", "newbie@example..test", "newbie@.test", "<newbie@example.test>"}) {
            assertEquals(400, send("POST", "/register", registrationJson("newbie", "secret12", notAnAddress), null).statusCode(),
                    notAnAddress);
        }
        assertEquals(1, mapper.readTree(get("/accounts").body()).size(), "none of those created an account");

        HttpResponse<String> accepted = send("POST", "/register", registrationJson("newbie", "secret12", "Newbie@Example.Test"), null);
        assertEquals(200, accepted.statusCode());
        assertAccount(accepted.body(), 2, "newbie", "secret12");
        assertFalse(accepted.body().toLowerCase().contains("example.test"), "the answer to register does not echo the address");
    }

    @Test
    void oneAddressOneAccountWhateverTheCapitals() throws Exception {
        assertEquals(200, send("POST", "/register", registrationJson("first", "secret12", "shared@example.test"), null).statusCode());
        assertEquals(400, send("POST", "/register", registrationJson("other", "secret12", "shared@example.test"), null).statusCode());
        assertEquals(400, send("POST", "/register", registrationJson("other", "secret12", " SHARED@Example.Test "), null).statusCode());
        assertEquals(200, send("POST", "/register", registrationJson("other", "secret12", "other@example.test"), null).statusCode());
    }

    @Test
    void theAddressIsNeverShownToOthers() throws Exception {
        registerSecondUser();
        for (String path : new String[] {"/accounts", "/accounts/2", "/messages", "/accounts/2/messages"}) {
            assertFalse(get(path).body().contains("example.test"), path + " must not show an email address");
        }
    }

    @Test
    void untilTheAddressIsConfirmedAMemberCanReadButNotPost() throws Exception {
        assertEquals(200, send("POST", "/register", registrationJson("newbie", "secret12", "newbie@example.test"), null).statusCode());
        String token = loginToken("newbie", "secret12");

        JsonNode me = mapper.readTree(send("GET", "/me", null, token).body());
        assertEquals("newbie@example.test", me.get("email").asText());
        assertFalse(me.get("email_verified").asBoolean());
        assertFalse(me.get("can_post").asBoolean());

        assertEquals(200, send("GET", "/messages", null, token).statusCode(), "reading works");
        assertEquals(403, send("POST", "/messages", messageJson(2, "too early", 1L), token).statusCode());
        assertEquals(1, mapper.readTree(get("/messages").body()).size(), "nothing was posted");

        confirmEmail("newbie@example.test");

        me = mapper.readTree(send("GET", "/me", null, token).body());
        assertTrue(me.get("email_verified").asBoolean());
        assertTrue(me.get("can_post").asBoolean());
        assertEquals(200, send("POST", "/messages", messageJson(2, "now I can", 1L), token).statusCode(),
                "the same login token works: confirming does not need a new sign-in");
        assertEquals(200, send("PATCH", "/messages/2", textJson("and edit"), token).statusCode());
    }

    @Test
    void theConfirmationLinkWorksOnceAndOnlyTheNewestOneWorks() throws Exception {
        assertEquals(200, send("POST", "/register", registrationJson("newbie", "secret12", "newbie@example.test"), null).statusCode());
        String first = secretMailedTo("newbie@example.test", "verify");
        assertEquals(43, first.length(), "32 random bytes, written as 43 characters");

        assertEquals(400, send("POST", "/email/verify", tokenJson("not-a-real-secret"), null).statusCode());
        assertEquals(400, send("POST", "/email/verify", tokenJson(first + "x"), null).statusCode());
        assertEquals(400, send("POST", "/email/verify", "{}", null).statusCode(), "no secret at all");
        assertEquals(400, send("POST", "/password/reset", "{\"token\":\"" + first + "\",\"password\":\"another12\"}", null).statusCode(),
                "a confirmation link cannot be used to set a password");

        assertEquals(200, send("POST", "/email/verify", tokenJson(first), null).statusCode());
        assertEquals(400, send("POST", "/email/verify", tokenJson(first), null).statusCode(), "a link works once");
    }

    @Test
    void aMemberCanCorrectAnUnconfirmedAddressAndAskForTheMailAgain() throws Exception {
        assertEquals(200, send("POST", "/register", registrationJson("newbie", "secret12", "typo@example.test"), null).statusCode());
        String token = loginToken("newbie", "secret12");
        String oldSecret = secretMailedTo("typo@example.test", "verify");

        // asking again within a minute is refused, with the time to wait
        HttpResponse<String> tooSoon = send("POST", "/email/verification", null, token);
        assertEquals(429, tooSoon.statusCode());
        long wait = Long.parseLong(tooSoon.headers().firstValue("Retry-After").orElseThrow());
        assertTrue(wait > 0 && wait <= 60, "Retry-After was " + wait);

        // correcting the address sends a new link to the new address and ends the old link
        HttpResponse<String> corrected = send("POST", "/accounts/2/email", emailJson("right@example.test"), token);
        assertEquals(200, corrected.statusCode());
        assertEquals("right@example.test", mapper.readTree(corrected.body()).get("email").asText());
        assertEquals(400, send("POST", "/email/verify", tokenJson(oldSecret), null).statusCode(), "the link to the wrong address is dead");
        confirmEmail("right@example.test");

        // once confirmed, there is nothing left to resend, and the address cannot be changed in 2.4
        assertEquals(400, send("POST", "/email/verification", null, token).statusCode());
        assertEquals(400, send("POST", "/accounts/2/email", emailJson("third@example.test"), token).statusCode());
    }

    @Test
    void anAccountFromBefore24KeepsPostingAndCanAddAnAddress() throws Exception {
        String token = loginToken("testuser1", "password"); // the seed account has no address
        JsonNode me = mapper.readTree(send("GET", "/me", null, token).body());
        assertTrue(me.get("email").isNull());
        assertFalse(me.get("email_verified").asBoolean());
        assertTrue(me.get("can_post").asBoolean(), "an account without an address is not waiting for anything");
        assertEquals(200, send("POST", "/messages", messageJson(1, "as before", 1L), token).statusCode());
        assertEquals(400, send("POST", "/email/verification", null, token).statusCode(), "there is nothing to confirm");

        assertEquals(400, send("POST", "/accounts/1/email", emailJson("not an address"), token).statusCode());
        assertEquals(200, send("POST", "/accounts/1/email", emailJson("Old.Member@example.test"), token).statusCode());
        assertEquals(403, send("POST", "/messages", messageJson(1, "address given, not confirmed", 1L), token).statusCode());
        // Editing is writing too: the member's own earlier post (message 1, from the seed data) waits as well.
        assertEquals(403, send("PATCH", "/messages/1", "{\"message_text\":\"edited while waiting\"}", token).statusCode());
        assertSeedMessage(mapper.readTree(get("/messages/1").body()));
        confirmEmail("old.member@example.test");
        assertEquals(200, send("POST", "/messages", messageJson(1, "confirmed", 1L), token).statusCode());
        assertEquals(200, send("PATCH", "/messages/1", "{\"message_text\":\"edited after confirming\"}", token).statusCode());
    }

    @Test
    void theAccountEndpointsAreForTheOwnerOnly() throws Exception {
        String second = registerSecondUser();
        assertEquals(401, get("/me").statusCode());
        assertEquals(401, send("GET", "/me", null, "made-up-token").statusCode());
        assertEquals(401, send("POST", "/email/verification", null, null).statusCode());
        assertEquals(401, send("POST", "/accounts/1/email", emailJson("thief@example.test"), null).statusCode());
        assertEquals(403, send("POST", "/accounts/1/email", emailJson("thief@example.test"), second).statusCode(),
                "account 2 may not give account 1 an address");
        assertEquals(400, send("POST", "/accounts/1/email", emailJson("second@example.test"),
                loginToken("testuser1", "password")).statusCode(), "the address of another account is taken");
        assertEquals(2, mapper.readTree(send("GET", "/me", null, second).body()).get("account_id").asInt());
    }

    // ---- the forgotten password (2.4) ----

    private static String resetJson(String secret, String password) {
        return "{\"token\":\"" + secret + "\",\"password\":\"" + password + "\"}";
    }

    @Test
    void forgotPasswordAnswersTheSameWhetherOrNotTheAddressIsKnown() throws Exception {
        registerSecondUser();
        HttpResponse<String> known = send("POST", "/password/forgot", emailJson("second@example.test"), null);
        HttpResponse<String> unknown = send("POST", "/password/forgot", emailJson("nobody@example.test"), null);
        HttpResponse<String> malformed = send("POST", "/password/forgot", emailJson("not an address"), null);
        for (HttpResponse<String> response : java.util.List.of(known, unknown, malformed)) {
            assertEquals(200, response.statusCode());
            assertEquals("", response.body());
        }
        assertNotNull(mailbox().lastTo("second@example.test"));
        assertNull(mailbox().lastTo("nobody@example.test"), "no mail goes to an address that has no account");
    }

    @Test
    void aNewPasswordSignsTheAccountOutEverywhereAndTheOldOneStopsWorking() throws Exception {
        String before = registerSecondUser();
        String alsoBefore = loginToken("second", "secret12");
        assertEquals(200, send("POST", "/messages", messageJson(2, "before", 1L), before).statusCode());
        // No pause here: the sessions end because the account's token generation moves on,
        // not because time has passed, so "immediately" is exactly the case to test.
        assertEquals(200, send("POST", "/password/forgot", emailJson("SECOND@example.test"), null).statusCode());
        String secret = secretMailedTo("second@example.test", "reset");
        assertEquals(400, send("POST", "/email/verify", tokenJson(secret), null).statusCode(),
                "a password link cannot be used as a confirmation link");
        assertEquals(200, send("POST", "/password/reset", resetJson(secret, "brandnew34"), null).statusCode());

        assertEquals(401, send("POST", "/messages", messageJson(2, "old session", 1L), before).statusCode());
        assertEquals(401, send("GET", "/me", null, alsoBefore).statusCode(), "every earlier session is ended");
        assertEquals(401, send("POST", "/login", accountJson("second", "secret12"), null).statusCode(), "the old password is gone");
        String after = loginToken("second", "brandnew34");
        assertEquals(200, send("POST", "/messages", messageJson(2, "new session", 1L), after).statusCode());
        assertEquals(200, send("POST", "/messages", messageJson(1, "someone else is not affected", 1L),
                loginToken("testuser1", "password")).statusCode());
    }

    @Test
    void thePasswordLinkWorksOnceAndARefusedPasswordDoesNotUseItUp() throws Exception {
        registerSecondUser();
        send("POST", "/password/forgot", emailJson("second@example.test"), null);
        String secret = secretMailedTo("second@example.test", "reset");

        assertEquals(400, send("POST", "/password/reset", resetJson("not-a-real-secret", "brandnew34"), null).statusCode());
        assertEquals(400, send("POST", "/password/reset", resetJson(secret, "weak"), null).statusCode(), "too short");
        assertEquals(400, send("POST", "/password/reset", resetJson(secret, "nodigitshere"), null).statusCode(), "no digit");
        assertEquals(400, send("POST", "/password/reset", resetJson(secret, "second"), null).statusCode());
        assertNotNull(loginToken("second", "secret12"), "the password is unchanged after three refusals");

        assertEquals(200, send("POST", "/password/reset", resetJson(secret, "brandnew34"), null).statusCode(),
                "the link survived the refusals");
        assertEquals(400, send("POST", "/password/reset", resetJson(secret, "another567"), null).statusCode(), "a link works once");
        assertNotNull(loginToken("second", "brandnew34"));
    }

    @Test
    void noPasswordLinkGoesToAnAddressThatWasNeverConfirmed() throws Exception {
        assertEquals(200, send("POST", "/register", registrationJson("newbie", "secret12", "typo@example.test"), null).statusCode());
        mailbox().clear();
        assertEquals(200, send("POST", "/password/forgot", emailJson("typo@example.test"), null).statusCode());
        assertNull(mailbox().lastTo("typo@example.test"),
                "whoever owns a mistyped address must not be handed the account");
    }

    @Test
    void askingForPasswordLinksIsLimitedPerVisitorAndASecondClickSendsNoSecondMail() throws Exception {
        registerSecondUser();
        mailbox().clear();
        for (int request = 1; request <= 5; request++) {
            assertEquals(200, send("POST", "/password/forgot", emailJson("second@example.test"), null).statusCode());
        }
        assertEquals(1, mailbox().recent().size(), "five clicks within a minute, one mail");
        HttpResponse<String> sixth = send("POST", "/password/forgot", emailJson("second@example.test"), null);
        assertEquals(429, sixth.statusCode());
        assertTrue(sixth.headers().firstValue("Retry-After").isPresent());
        assertEquals(200, send("POST", "/password/forgot", emailJson("second@example.test"), null,
                Map.of(PROXY_HEADER, "203.0.113.50")).statusCode(), "another visitor is not affected");
    }

    @Test
    void aLoginTokenOfAnAccountThatNoLongerExistsIsRefused() throws Exception {
        String second = registerSecondUser();
        assertEquals(200, send("GET", "/me", null, second).statusCode());
        jdbc.update("delete from account where account_id = ?", 2);
        assertEquals(401, send("GET", "/me", null, second).statusCode());
        assertEquals(401, send("POST", "/messages", messageJson(2, "from nobody", 1L), second).statusCode());
        assertEquals(0, jdbc.queryForObject("select count(*) from account_token where account_id = 2", Integer.class),
                "the links of a deleted account go with it (foreign key, on delete cascade)");
    }

    // ---- 2.5: deleting an account, and invitations on an open board ----

    private static String passwordJson(String password) {
        return "{\"password\":\"" + password + "\"}";
    }

    @Test
    void aMemberDeletesTheirAccountAndEverythingOfItGoes() throws Exception {
        String second = registerSecondUser();
        assertEquals(200, send("POST", "/messages", messageJson(2, "a post by second", 1L), second).statusCode());
        String first = loginToken("testuser1", "password");
        assertEquals(200, send("POST", "/messages", messageJson(1, "thanks second", 2L), first).statusCode());
        assertEquals(1, mapper.readTree(get("/accounts/2/mentions").body()).size());

        assertEquals(401, send("POST", "/accounts/2/deletion", passwordJson("secret12"), null).statusCode());
        assertEquals(403, send("POST", "/accounts/2/deletion", passwordJson("secret12"), first).statusCode(), "someone else's account");
        assertEquals(403, send("POST", "/accounts/2/deletion", passwordJson("wrong-password1"), second).statusCode(),
                "a wrong password is 403, not 401: the web app must not take it for an ended session");
        assertEquals(400, send("POST", "/accounts/2/deletion", "{}", second).statusCode());

        assertEquals(200, send("POST", "/accounts/2/deletion", passwordJson("secret12"), second).statusCode());

        assertEquals(401, send("GET", "/me", null, second).statusCode(), "the session ended with the account");
        assertEquals(404, get("/accounts/2").statusCode());
        assertEquals("[]", get("/accounts/2/messages").body());
        assertEquals(2, mapper.readTree(get("/messages").body()).size(), "the seed post and testuser1's reply remain");
        assertTrue(get("/messages").body().contains("thanks second"), "posts by others keep their text");
        assertEquals(0, jdbc.queryForObject("select count(*) from mention where account_id = 2", Integer.class));
        assertEquals(0, jdbc.queryForObject("select count(*) from message where posted_by = 2", Integer.class));
        // the name and the address are free again
        assertNotNull(registerConfirmed("second", "secret12"));
    }

    @Test
    void fiveWrongPasswordsWhenDeletingCountLikeFiveWrongSignIns() throws Exception {
        String second = registerSecondUser();
        for (int attempt = 1; attempt <= 5; attempt++) {
            assertEquals(403, send("POST", "/accounts/2/deletion", passwordJson("wrong-" + attempt + "x"), second).statusCode());
        }
        assertEquals(429, send("POST", "/accounts/2/deletion", passwordJson("secret12"), second).statusCode());
        assertEquals(429, send("POST", "/login", accountJson("second", "secret12"), null).statusCode(), "the same count as sign-ins");
    }

    @Test
    void anOpenBoardWithoutAnOwnerHasNoInvitations() throws Exception {
        String token = loginToken("testuser1", "password");
        assertEquals(403, send("POST", "/invitations", emailJson("x@example.test"), token).statusCode());
        assertEquals(403, send("POST", "/invitations/link", null, token).statusCode());
        assertFalse(mapper.readTree(send("GET", "/me", null, token).body()).get("is_owner").asBoolean());
        // A made-up invitation is refused even where none is needed: a link that does not work must not look as if it did.
        assertEquals(403, send("POST", "/register", registrationJson("guest", "secret12", "guest@example.test", "made-up"), null).statusCode());
        assertEquals(200, send("POST", "/register", registrationJson("guest", "secret12", "guest@example.test"), null).statusCode());
    }

    // ------------------------------------------------------------------ 2.7

    @Test
    void withoutAWebhookSecretTheWebhookAddressDoesNotExist() throws Exception {
        assertEquals(404, send("POST", "/webhooks/resend", "{\"type\":\"email.bounced\"}", null).statusCode());
        assertFalse(mapper.readTree(send("GET", "/me", null, registerConfirmed("Ada", "secret12")).body()).get("email_bounced").asBoolean());
    }

    @Test
    void onePasswordLinkSentFromTwoPlacesAtOnceSetsOnePassword() throws Exception {
        // 2.7: the same once-only rule as invitations (review of 2.5.0). Before, both could pass.
        registerSecondUser();
        send("POST", "/password/forgot", emailJson("second@example.test"), null);
        String secret = secretMailedTo("second@example.test", "reset");
        int tries = 4;
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(tries);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.List<java.util.concurrent.Future<Integer>> answers = new java.util.ArrayList<>();
        try {
            for (int i = 0; i < tries; i++) {
                String password = "brandnew" + (10 + i);
                answers.add(pool.submit(() -> {
                    start.await();
                    return send("POST", "/password/reset", resetJson(secret, password), null).statusCode();
                }));
            }
            start.countDown();
            int accepted = 0;
            for (java.util.concurrent.Future<Integer> answer : answers) {
                int status = answer.get();
                if (status == 200) {
                    accepted++;
                } else {
                    assertTrue(status == 400 || status == 409, "refused, not broken: " + status);
                }
            }
            assertEquals(1, accepted, "one link, one new password");
        } finally {
            pool.shutdownNow();
        }
    }
}
