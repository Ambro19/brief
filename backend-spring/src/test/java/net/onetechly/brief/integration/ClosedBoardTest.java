package net.onetechly.brief.integration;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A closed board, as on briefmessage.net: the application is started with brief.board=closed
 * and an owner. Newcomers join through invitation links the owner sends (2.5); reading needs a
 * sign-in. The web app itself, the API documentation, /health and the mailed links stay public.
 *
 * The owner here is the seed account, testuser1.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "brief.auth.mode=strict",
                "brief.board=closed",
                "brief.owner-username=TestUser1",
                "brief.invite-code=",
                "brief.mail.resend-api-key=",   // never real mail from a test, whatever the machine has set
                "brief.client-ip.header=" + ClosedBoardTest.PROXY_HEADER,
                "spring.datasource.url=${BRIEF_TEST_DB_URL:jdbc:h2:mem:brief-closed;DB_CLOSE_DELAY=-1}",
                "spring.datasource.username=${BRIEF_TEST_DB_USER:sa}",
                "spring.datasource.password=${BRIEF_TEST_DB_PASSWORD:sa}"})
class ClosedBoardTest extends ApiTestBase {

    static final String PROXY_HEADER = "X-Test-Client-Ip";

    /** Everything a signed-out visitor could read on an open board. */
    private static final String[] MEMBER_ONLY = {
            "/messages", "/messages?limit=5", "/messages/1",
            "/accounts", "/accounts/1", "/accounts/1/messages", "/accounts/1/mentions", "/accounts/1/mentions?limit=5"};

    private String ownerToken() throws Exception {
        return loginToken("testuser1", "password");
    }

    /** The owner mails an invitation to this address; returns the secret of the link in that mail. */
    private String invite(String email) throws Exception {
        assertEquals(200, send("POST", "/invitations", emailJson(email), ownerToken()).statusCode());
        return secretMailedTo(email, "invite");
    }

    /** The owner makes a link to pass on; returns its secret. */
    private String linkToPassOn() throws Exception {
        HttpResponse<String> made = send("POST", "/invitations/link", null, ownerToken());
        assertEquals(200, made.statusCode());
        String link = mapper.readTree(made.body()).get("link").asText();
        assertTrue(link.contains("/?invite="), link);
        return link.substring(link.indexOf("/?invite=") + "/?invite=".length());
    }

    @Test
    void healthTellsTheWebAppThatTheBoardIsClosed() throws Exception {
        HttpResponse<String> response = get("/health");
        assertEquals(200, response.statusCode(), "a host must be able to check that the application is up");
        JsonNode health = mapper.readTree(response.body());
        assertEquals("closed", health.get("board").asText());
        assertEquals("required", health.get("email").asText());
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
        String token = ownerToken();
        for (String path : MEMBER_ONLY) {
            assertEquals(200, send("GET", path, null, token).statusCode(), path);
        }
        assertSeedMessage(mapper.readTree(send("GET", "/messages/1", null, token).body()));
    }

    @Test
    void theWebAppTheDocumentationAndTheTermsStayPublic() throws Exception {
        for (String path : new String[] {"/", "/index.html", "/manifest.json", "/sw.js", "/docs/index.html", "/docs/openapi.json",
                "/terms.html", "/privacy.html", "/legal.css", "/help/index.html"}) {
            assertEquals(200, get(path).statusCode(), path);
        }
    }

    @Test
    void theHelpCenterIsPublicAndEveryArticleItListsExists() throws Exception {
        // 2.6: the Help Center is for visitors too (what Brief! is, pricing), so a closed board
        // must not hide it. Every article linked from its first page must answer, so a renamed
        // or missing page is caught here and not by a visitor.
        HttpResponse<String> index = get("/help/index.html");
        assertEquals(200, index.statusCode());
        assertTrue(index.headers().firstValue("Content-Type").orElse("").startsWith("text/html"));
        java.util.regex.Matcher links = java.util.regex.Pattern.compile("href=\"(/help/[a-z-]+\\.html)\"").matcher(index.body());
        List<String> articles = new ArrayList<>();
        while (links.find()) {
            articles.add(links.group(1));
        }
        assertEquals(List.of("/help/what-is-brief.html", "/help/who-its-for.html", "/help/getting-started.html", "/help/faq.html",
                "/help/pricing.html", "/help/refunds.html"), articles);
        for (String article : articles) {
            HttpResponse<String> page = get(article);
            assertEquals(200, page.statusCode(), article);
            assertTrue(page.body().contains("support@briefmessage.net"), article + " tells where to write");
            assertTrue(page.body().contains("href=\"/help/index.html\""), article + " links back to the Help Center");
        }
        assertTrue(get("/help/pricing.html").body().contains("Free during early access"));
    }

    @Test
    void creatingAnAccountNeedsAnInvitation() throws Exception {
        assertEquals(403, send("POST", "/register", registrationJson("guest", "secret12", "guest@example.test"), null).statusCode(),
                "no invitation");
        assertEquals(403, send("POST", "/register", registrationJson("guest", "secret12", "guest@example.test", "made-up"), null).statusCode());
        // The shared code of 2.4 is not asked for any more, and does not open the door either.
        assertEquals(403, send("POST", "/register",
                "{\"username\":\"guest\",\"password\":\"secret12\",\"email\":\"guest@example.test\",\"invite_code\":\"spring-tulip-2026\"}",
                null).statusCode());
        assertNull(mailbox().lastTo("guest@example.test"), "no mail for a refused registration");
    }

    @Test
    void aMailedInvitationLetsTheNewMemberPostAtOnce() throws Exception {
        String secret = invite("Maya@Example.test");

        // Opening the link: who sent it, to which address. No sign-in needed, on a closed board too.
        HttpResponse<String> lookup = send("POST", "/invitations/lookup", tokenJson(secret), null);
        assertEquals(200, lookup.statusCode());
        JsonNode info = mapper.readTree(lookup.body());
        assertEquals("testuser1", info.get("inviter").asText());
        assertEquals("maya@example.test", info.get("email").asText());

        // A different address is refused: only the invited inbox is proven.
        assertEquals(400, send("POST", "/register", registrationJson("Maya", "secret12", "other@example.test", secret), null).statusCode());
        HttpResponse<String> joined = send("POST", "/register", registrationJson("Maya", "secret12", null, secret), null);
        assertEquals(200, joined.statusCode());
        assertAccount(joined.body(), 2, "Maya", "secret12");

        String token = loginToken("maya", "secret12");
        JsonNode me = mapper.readTree(send("GET", "/me", null, token).body());
        assertEquals("maya@example.test", me.get("email").asText());
        assertTrue(me.get("email_verified").asBoolean(), "opening the mailed link proved the inbox");
        assertTrue(me.get("can_post").asBoolean());
        assertFalse(me.get("is_owner").asBoolean());
        assertFalse(mailbox().lastTo("maya@example.test").text().contains("?verify="), "no confirmation mail: nothing to confirm");
        assertEquals(200, send("POST", "/messages", messageJson(2, "hello team", 1L), token).statusCode());

        // The invitation is used up.
        assertEquals(403, send("POST", "/register", registrationJson("Maya2", "secret12", null, secret), null).statusCode());
        assertEquals(400, send("POST", "/invitations/lookup", tokenJson(secret), null).statusCode());
        JsonNode sent = mapper.readTree(send("GET", "/invitations", null, ownerToken()).body());
        assertEquals("used", sent.get(0).get("status").asText());
        assertEquals("Maya", sent.get(0).get("joined_as").asText());
        assertFalse(sent.toString().contains(secret), "the secret is never shown again");
    }

    @Test
    void aLinkPassedOnStillNeedsTheAddressConfirmed() throws Exception {
        String secret = linkToPassOn();
        assertTrue(mapper.readTree(send("POST", "/invitations/lookup", tokenJson(secret), null).body()).get("email").isNull());
        assertEquals(400, send("POST", "/register", registrationJson("ben", "secret12", null, secret), null).statusCode(),
                "the newcomer must give an address");
        assertEquals(200, send("POST", "/register", registrationJson("ben", "secret12", "ben@example.test", secret), null).statusCode());

        String token = loginToken("ben", "secret12");
        assertEquals(200, send("GET", "/messages", null, token).statusCode(), "signed in: may read before confirming");
        assertEquals(403, send("POST", "/messages", messageJson(2, "not yet", 1L), token).statusCode());
        // The emailed links are opened by someone who is not signed in: they must work on a closed board.
        confirmEmail("ben@example.test");
        assertEquals(200, send("POST", "/messages", messageJson(2, "hello team", 1L), token).statusCode());
    }

    @Test
    void onlyTheOwnerInvitesAndCanWithdrawAnInvitation() throws Exception {
        assertEquals(401, send("POST", "/invitations", emailJson("x@example.test"), null).statusCode());
        assertEquals(401, send("GET", "/invitations", null, null).statusCode());
        String secret = invite("ben@example.test");
        assertEquals(200, send("POST", "/register", registrationJson("ben", "secret12", null, secret), null).statusCode());
        String member = loginToken("ben", "secret12");
        assertEquals(403, send("POST", "/invitations", emailJson("x@example.test"), member).statusCode());
        assertEquals(403, send("POST", "/invitations/link", null, member).statusCode());
        assertEquals(403, send("GET", "/invitations", null, member).statusCode());
        assertTrue(mapper.readTree(send("GET", "/me", null, ownerToken()).body()).get("is_owner").asBoolean());

        String pending = invite("cy@example.test");
        assertEquals(400, send("POST", "/invitations", emailJson("BEN@example.test"), ownerToken()).statusCode(),
                "Ben is already a member");
        int id = mapper.readTree(send("GET", "/invitations", null, ownerToken()).body()).get(0).get("invitation_id").asInt();
        assertEquals(403, send("DELETE", "/invitations/" + id, null, member).statusCode());
        assertEquals(200, send("DELETE", "/invitations/" + id, null, ownerToken()).statusCode());
        assertEquals(403, send("POST", "/register", registrationJson("cy", "secret12", null, pending), null).statusCode(), "withdrawn");
        assertEquals(404, send("DELETE", "/invitations/" + id, null, ownerToken()).statusCode());
    }

    @Test
    void aForgottenPasswordCanBeResetWithoutBeingSignedIn() throws Exception {
        assertEquals(200, send("POST", "/register", registrationJson("guest", "secret12", null, invite("guest@example.test")), null).statusCode());
        assertEquals(200, send("POST", "/password/forgot", emailJson("guest@example.test"), null).statusCode());
        String secret = secretMailedTo("guest@example.test", "reset");
        assertEquals(200, send("POST", "/password/reset",
                "{\"token\":\"" + secret + "\",\"password\":\"brandnew34\"}", null).statusCode());
        assertNotNull(loginToken("guest", "brandnew34"));
    }

    @Test
    void invitationsCannotBeFoundByTrying() throws Exception {
        String secret = invite("guest@example.test");
        Map<String, String> visitor = Map.of(PROXY_HEADER, "198.51.100.77");
        for (int attempt = 1; attempt <= 5; attempt++) {
            assertEquals(403, send("POST", "/register",
                    registrationJson("guest", "secret12", null, "guess-number-" + attempt), null, visitor).statusCode());
        }
        HttpResponse<String> locked = send("POST", "/register", registrationJson("guest", "secret12", null, secret), null, visitor);
        assertEquals(429, locked.statusCode(), "after five bad invitations even a good one is refused for a while");
        assertTrue(locked.headers().firstValue("Retry-After").isPresent());
        assertEquals(429, send("POST", "/invitations/lookup", tokenJson(secret), null, visitor).statusCode(), "looking up counts too");

        assertEquals(200, send("POST", "/register", registrationJson("guest", "secret12", null, secret), null,
                Map.of(PROXY_HEADER, "203.0.113.9")).statusCode(), "another visitor, with a good invitation, is not affected");
        // The count of bad invitations is its own count. Signing in and password links are counted separately.
        assertEquals(200, send("POST", "/login", accountJson("testuser1", "password"), null, visitor).statusCode());
        assertEquals(200, send("POST", "/password/forgot", emailJson("guest@example.test"), null, visitor).statusCode());
    }

    @Test
    void aGoodInvitationClearsTheCountOfBadOnes() throws Exception {
        Map<String, String> visitor = Map.of(PROXY_HEADER, "198.51.100.78");
        for (int round = 1; round <= 3; round++) {
            String secret = invite("m" + round + "@example.test");
            for (int attempt = 1; attempt <= 4; attempt++) {
                assertEquals(403, send("POST", "/register",
                        registrationJson("member" + round, "secret12", null, "wrong-" + attempt), null, visitor).statusCode());
            }
            assertEquals(200, send("POST", "/register", registrationJson("member" + round, "secret12", null, secret), null, visitor).statusCode());
        }
    }

    @Test
    void oneLinkSentFromManyPlacesAtOnceAdmitsOnePerson() throws Exception {
        // Review of 2.5.0: a link passed on, used by several registrations at the same moment,
        // let every one of them in. Each request comes "from" its own address, so the limit per
        // address does not hide the problem; they start together on a signal.
        String secret = linkToPassOn();
        int tries = 6;
        ExecutorService pool = Executors.newFixedThreadPool(tries);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> answers = new ArrayList<>();
        try {
            for (int i = 0; i < tries; i++) {
                String name = "racer" + i;
                Map<String, String> from = Map.of(PROXY_HEADER, "198.51.100." + (10 + i));
                Callable<Integer> attempt = () -> {
                    start.await();
                    return send("POST", "/register", registrationJson(name, "secret12", name + "@example.test", secret), null, from)
                            .statusCode();
                };
                answers.add(pool.submit(attempt));
            }
            start.countDown();
            int admitted = 0;
            for (Future<Integer> answer : answers) {
                int status = answer.get();
                if (status == 200) {
                    admitted++;
                } else {
                    assertTrue(status == 403 || status == 409, "refused, not broken: " + status);
                }
            }
            assertEquals(1, admitted, "one invitation, one new member");
        } finally {
            pool.shutdownNow();
        }
        Integer racers = jdbc.queryForObject("select count(*) from account where username like 'racer%'", Integer.class);
        assertEquals(1, racers, "the refused ones left no account behind");
    }

    @Test
    void deletingAnAccountAlsoDeletesTheInvitationThatHoldsItsAddress() throws Exception {
        // Review of 2.5.0: the Privacy notice says deleting an account removes its address.
        String secret = invite("maya@example.test");
        assertEquals(200, send("POST", "/register", registrationJson("Maya", "secret12", null, secret), null).statusCode());
        String token = loginToken("maya", "secret12");
        assertEquals(200, send("POST", "/accounts/2/deletion", "{\"password\":\"secret12\"}", token).statusCode());

        String sent = send("GET", "/invitations", null, ownerToken()).body();
        assertFalse(sent.contains("maya@example.test"), sent);
        Integer rows = jdbc.queryForObject("select count(*) from invitation where email = 'maya@example.test'", Integer.class);
        assertEquals(0, rows);
    }

    @Test
    void theOwnerCannotDeleteTheirAccount() throws Exception {
        assertEquals(400, send("POST", "/accounts/1/deletion", "{\"password\":\"password\"}", ownerToken()).statusCode(),
                "a closed board without its owner could never invite anyone again");
        assertEquals(200, get("/health").statusCode());
        assertNotNull(ownerToken());
    }

    @Test
    void writingStillFollowsTheRulesOfStrictMode() throws Exception {
        assertEquals(401, send("POST", "/messages", messageJson(1, "hello", 1L), null).statusCode());
        String token = ownerToken();
        assertEquals(200, send("POST", "/messages", messageJson(1, "mine", 1L), token).statusCode());
        assertEquals(200, send("POST", "/logout", null, token).statusCode());
        assertEquals(401, send("GET", "/messages", null, token).statusCode(), "signed out: no more reading either");
    }
}
