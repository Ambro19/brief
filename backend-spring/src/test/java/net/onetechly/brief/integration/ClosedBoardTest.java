package net.onetechly.brief.integration;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.net.http.HttpResponse;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A closed board (2.4): the application is started with an invitation code, as it is on
 * briefmessage.net. Creating an account needs the code, and reading needs a sign-in. The web
 * app itself, the API documentation and /health stay public.
 *
 * This class starts the application a third time, with its own settings. Spring keeps one
 * running application per distinct set of properties and reuses it for every test of the class.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "brief.auth.mode=strict",
                "brief.invite-code=" + ClosedBoardTest.CODE,
                "brief.mail.resend-api-key=",   // never real mail from a test, whatever the machine has set
                "brief.client-ip.header=" + ClosedBoardTest.PROXY_HEADER,
                "spring.datasource.url=${BRIEF_TEST_DB_URL:jdbc:h2:mem:brief-closed;DB_CLOSE_DELAY=-1}",
                "spring.datasource.username=${BRIEF_TEST_DB_USER:sa}",
                "spring.datasource.password=${BRIEF_TEST_DB_PASSWORD:sa}"})
class ClosedBoardTest extends ApiTestBase {

    static final String CODE = "spring-tulip-2026";
    static final String PROXY_HEADER = "X-Test-Client-Ip";

    /** Everything a signed-out visitor could read on an open board. */
    private static final String[] MEMBER_ONLY = {
            "/messages", "/messages?limit=5", "/messages/1",
            "/accounts", "/accounts/1", "/accounts/1/messages", "/accounts/1/mentions", "/accounts/1/mentions?limit=5"};

    @Test
    void healthTellsTheWebAppThatTheBoardIsClosed() throws Exception {
        HttpResponse<String> response = get("/health");
        assertEquals(200, response.statusCode(), "a host must be able to check that the application is up");
        JsonNode health = mapper.readTree(response.body());
        assertEquals("closed", health.get("board").asText());
        assertEquals("required", health.get("email").asText());
        assertFalse(response.body().contains(CODE), "the code itself is never sent to anyone");
    }

    @Test
    void readingNeedsASignIn() throws Exception {
        for (String path : MEMBER_ONLY) {
            HttpResponse<String> signedOut = get(path);
            assertEquals(401, signedOut.statusCode(), path);
            assertEquals("", signedOut.body(), path + " must not leak anything in the refusal");
            assertEquals(401, send("GET", path, null, "made-up-token").statusCode(), path);
            // HEAD asks for the headers of the GET without its body; those say how long the list is.
            assertEquals(401, send("HEAD", path, null, null).statusCode(), "HEAD " + path);
        }
        String token = loginToken("testuser1", "password");
        for (String path : MEMBER_ONLY) {
            assertEquals(200, send("GET", path, null, token).statusCode(), path);
        }
        assertSeedMessage(mapper.readTree(send("GET", "/messages/1", null, token).body()));
    }

    @Test
    void theWebAppAndTheDocumentationStayPublic() throws Exception {
        for (String path : new String[] {"/", "/index.html", "/manifest.json", "/sw.js", "/docs/index.html", "/docs/openapi.json"}) {
            assertEquals(200, get(path).statusCode(), path);
        }
    }

    @Test
    void creatingAnAccountNeedsTheInvitationCode() throws Exception {
        assertEquals(403, send("POST", "/register", registrationJson("guest", "secret12", "guest@example.test"), null).statusCode(),
                "no code");
        assertEquals(403, send("POST", "/register", registrationJson("guest", "secret12", "guest@example.test", ""), null).statusCode());
        assertEquals(403, send("POST", "/register", registrationJson("guest", "secret12", "guest@example.test", CODE + "x"), null).statusCode());
        // Without the code even a request that is wrong in other ways learns nothing more than "no".
        // (Three refused tries so far and this is the fourth. A fifth would make this address wait; see the test below.)
        assertEquals(403, send("POST", "/register", registrationJson("testuser1", "secret12", "not an address", "nope-nope"), null).statusCode());
        assertNull(mailbox().lastTo("guest@example.test"), "no mail for a refused registration");

        HttpResponse<String> invited = send("POST", "/register", registrationJson("guest", "secret12", "guest@example.test", CODE), null);
        assertEquals(200, invited.statusCode());
        assertAccount(invited.body(), 2, "guest", "secret12");
        // With the code, the ordinary rules apply and are reported as before.
        assertEquals(400, send("POST", "/register", registrationJson("guest", "secret12", "other@example.test", CODE), null).statusCode(),
                "the name is taken");
    }

    @Test
    void anInvitedMemberConfirmsTheAddressAndPosts() throws Exception {
        assertEquals(200, send("POST", "/register", registrationJson("guest", "secret12", "guest@example.test", CODE), null).statusCode());
        String token = loginToken("guest", "secret12");
        assertEquals(200, send("GET", "/messages", null, token).statusCode(), "signed in: may read before confirming");
        assertEquals(403, send("POST", "/messages", messageJson(2, "not yet", 1L), token).statusCode());
        // The emailed links are opened by someone who is not signed in: they must work on a closed board.
        confirmEmail("guest@example.test");
        assertEquals(200, send("POST", "/messages", messageJson(2, "hello team", 1L), token).statusCode());
    }

    @Test
    void aForgottenPasswordCanBeResetWithoutBeingSignedIn() throws Exception {
        assertEquals(200, send("POST", "/register", registrationJson("guest", "secret12", "guest@example.test", CODE), null).statusCode());
        confirmEmail("guest@example.test");
        assertEquals(200, send("POST", "/password/forgot", emailJson("guest@example.test"), null).statusCode());
        String secret = secretMailedTo("guest@example.test", "reset");
        assertEquals(200, send("POST", "/password/reset",
                "{\"token\":\"" + secret + "\",\"password\":\"brandnew34\"}", null).statusCode());
        assertNotNull(loginToken("guest", "brandnew34"));
    }

    @Test
    void theCodeCannotBeFoundByTrying() throws Exception {
        Map<String, String> visitor = Map.of(PROXY_HEADER, "198.51.100.77");
        for (int attempt = 1; attempt <= 5; attempt++) {
            assertEquals(403, send("POST", "/register",
                    registrationJson("guest", "secret12", "guest@example.test", "guess-number-" + attempt), null, visitor).statusCode());
        }
        HttpResponse<String> locked = send("POST", "/register",
                registrationJson("guest", "secret12", "guest@example.test", CODE), null, visitor);
        assertEquals(429, locked.statusCode(), "after five wrong codes even the right one is refused for a while");
        assertTrue(locked.headers().firstValue("Retry-After").isPresent());

        assertEquals(200, send("POST", "/register", registrationJson("guest", "secret12", "guest@example.test", CODE), null,
                Map.of(PROXY_HEADER, "203.0.113.9")).statusCode(), "another visitor, with the right code, is not affected");
        // The count of wrong codes is its own count. A member behind the locked-out address can still
        // sign in and ask for a password link: those are counted separately.
        assertEquals(200, send("POST", "/login", accountJson("testuser1", "password"), null, visitor).statusCode());
        assertEquals(200, send("POST", "/password/forgot", emailJson("guest@example.test"), null, visitor).statusCode());
    }

    @Test
    void aRightCodeClearsTheCountOfWrongOnes() throws Exception {
        Map<String, String> visitor = Map.of(PROXY_HEADER, "198.51.100.78");
        for (int round = 1; round <= 3; round++) {
            for (int attempt = 1; attempt <= 4; attempt++) {
                assertEquals(403, send("POST", "/register",
                        registrationJson("member" + round, "secret12", "m" + round + "@example.test", "wrong-" + attempt), null, visitor).statusCode());
            }
            assertEquals(200, send("POST", "/register",
                    registrationJson("member" + round, "secret12", "m" + round + "@example.test", CODE), null, visitor).statusCode());
        }
    }

    @Test
    void writingStillFollowsTheRulesOfStrictMode() throws Exception {
        assertEquals(401, send("POST", "/messages", messageJson(1, "hello", 1L), null).statusCode());
        String token = loginToken("testuser1", "password");
        assertEquals(200, send("POST", "/messages", messageJson(1, "mine", 1L), token).statusCode());
        assertEquals(200, send("POST", "/logout", null, token).statusCode());
        assertEquals(401, send("GET", "/messages", null, token).statusCode(), "signed out: no more reading either");
    }
}
