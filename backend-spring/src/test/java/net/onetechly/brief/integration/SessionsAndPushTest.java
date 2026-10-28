package net.onetechly.brief.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import net.onetechly.brief.controller.AccountController;
import net.onetechly.brief.push.WebPushCrypto;
import net.onetechly.brief.push.WebPushDecrypt;
import net.onetechly.brief.security.JwtService;
import net.onetechly.brief.service.PushNotifications;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.net.InetSocketAddress;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 2.8 end to end: sessions kept in the database, and notifications for mentions.
 *
 * The application runs with a VAPID key pair, and with "http://localhost" added to the push
 * services it may reach. A stand-in push service runs inside this test (the JDK's own HTTP
 * server): it records what Brief! sends, and the test then reads it as the member's browser would,
 * with the browser's private key. So these tests see exactly what Google, Mozilla or Apple would
 * receive, and that only the browser can read it.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "brief.auth.mode=strict",
                "brief.invite-code=",
                "brief.board=open",
                "brief.owner-username=",
                "brief.mail.resend-api-key=",
                "brief.public-url=https://brief.example",
                // A pair printed by "npx web-push generate-vapid-keys". For tests only: never use it on a server.
                "brief.push.vapid-public-key=" + SessionsAndPushTest.VAPID_PUBLIC,
                "brief.push.vapid-private-key=mX7F7jgLiYtSeUul7787pXC4K8Wu7af0ETxjduTOVkE",
                "brief.push.allowed-hosts=http://localhost",
                "spring.datasource.url=${BRIEF_TEST_DB_URL:jdbc:h2:mem:brief-push;DB_CLOSE_DELAY=-1}",
                "spring.datasource.username=${BRIEF_TEST_DB_USER:sa}",
                "spring.datasource.password=${BRIEF_TEST_DB_PASSWORD:sa}"})
class SessionsAndPushTest extends ApiTestBase {

    static final String VAPID_PUBLIC = "BDL88VG70KjjiTwbkEkX6f8ZWr0lf0GtDVQ0s9IcLYlqDnUOOuo0GpjLpghek6AhlrtzMCqZ-3nsMVx7ZYYvrRQ";
    private static final String CHROME = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
            + "Chrome/130.0.0.0 Safari/537.36 Edg/130.0.0.0";
    private static final String IPHONE = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_6 like Mac OS X) AppleWebKit/605.1.15 "
            + "(KHTML, like Gecko) Version/17.6 Mobile/15E148 Safari/604.1";

    /** One request that reached the stand-in push service. */
    record Pushed(String path, Map<String, String> headers, byte[] body) {
    }

    private static HttpServer pushService;
    private static final List<Pushed> received = new CopyOnWriteArrayList<>();
    /** Path -> the status the stand-in answers with (201 if not listed). */
    private static final Map<String, Integer> answers = new ConcurrentHashMap<>();

    @Autowired
    private JwtService jwtService;

    @Autowired
    private PushNotifications push;

    /** The member's browser: its key pair and auth secret, as PushManager.subscribe makes them. */
    private final KeyPair browser = WebPushCrypto.newKeyPair();
    private final byte[] browserPublic = WebPushCrypto.encode((ECPublicKey) browser.getPublic());
    private final byte[] auth = "sixteen-byte-key".getBytes(StandardCharsets.US_ASCII);

    @BeforeAll
    static void startPushService() throws Exception {
        pushService = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        pushService.createContext("/push/", exchange -> {
            Map<String, String> headers = new java.util.HashMap<>();
            exchange.getRequestHeaders().forEach((name, values) -> headers.put(name.toLowerCase(java.util.Locale.ROOT), values.get(0)));
            byte[] body = exchange.getRequestBody().readAllBytes();
            String path = exchange.getRequestURI().getPath();
            received.add(new Pushed(path, headers, body));
            exchange.sendResponseHeaders(answers.getOrDefault(path, 201), -1);
            exchange.close();
        });
        pushService.start();
    }

    @AfterAll
    static void stopPushService() {
        pushService.stop(0);
    }

    @BeforeEach
    void clean() {
        received.clear();
        answers.clear();
        push.resetLimits();
    }

    // ------------------------------------------------------------------ helpers

    private String endpoint(String device) {
        return "http://localhost:" + pushService.getAddress().getPort() + "/push/" + device;
    }

    private String subscriptionJson(String device) {
        return "{\"endpoint\":\"" + endpoint(device) + "\",\"expirationTime\":null,\"keys\":{\"p256dh\":\""
                + WebPushCrypto.base64(browserPublic) + "\",\"auth\":\"" + WebPushCrypto.base64(auth) + "\"}}";
    }

    private HttpResponse<String> login(String username, boolean stay, String userAgent) throws Exception {
        return send("POST", "/login", "{\"username\":\"" + username + "\",\"password\":\"secret12\",\"stay\":" + stay + "}",
                null, Map.of("User-Agent", userAgent));
    }

    /** Registers a member with a confirmed address and signs them in with "Stay signed in", as notifications need. */
    private String stayMember(String username) throws Exception {
        registerConfirmed(username, "secret12");
        return tokenOf(login(username, true, IPHONE));
    }

    private String tokenOf(HttpResponse<String> response) {
        assertEquals(200, response.statusCode());
        return response.headers().firstValue(AccountController.TOKEN_HEADER).orElseThrow();
    }

    private JsonNode sessions(String token) throws Exception {
        HttpResponse<String> response = send("GET", "/sessions", null, token);
        assertEquals(200, response.statusCode());
        return mapper.readTree(response.body());
    }

    private int status(String method, String path, String body, String token) throws Exception {
        return send(method, path, body, token).statusCode();
    }

    /** Waits until the stand-in push service has received this many requests (sending runs on its own thread). */
    private void awaitPushes(int count) throws InterruptedException {
        long until = System.currentTimeMillis() + 10_000;
        while (received.size() < count && System.currentTimeMillis() < until) {
            Thread.sleep(20);
        }
        assertEquals(count, received.size(), "requests at the push service");
    }

    /** What the browser reads out of a notification. */
    private JsonNode read(Pushed pushed) throws Exception {
        return mapper.readTree(WebPushDecrypt.decrypt(pushed.body(), (ECPrivateKey) browser.getPrivate(), browserPublic, auth));
    }

    private int rows(String table) {
        Integer count = jdbc.queryForObject("select count(*) from " + table, Integer.class);
        return count == null ? 0 : count;
    }

    // ------------------------------------------------------------------ sessions

    @Test
    void stayingSignedInMakesALongSessionThatTheMemberCanSeeWithItsDevice() throws Exception {
        registerConfirmed("gilberto", "secret12");            // account 2; this also signs in once
        String phone = tokenOf(login("gilberto", true, IPHONE));
        tokenOf(login("gilberto", false, CHROME));

        JsonNode list = sessions(phone);
        assertEquals(3, list.size(), "the sign-in of registerConfirmed, the phone and the computer");
        JsonNode newest = list.get(0);
        assertEquals("Edge on Windows", newest.get("device").asText());
        assertFalse(newest.get("stay").asBoolean());
        assertFalse(newest.get("current").asBoolean());
        JsonNode mine = list.get(1);
        assertEquals("Safari on iPhone", mine.get("device").asText());
        assertTrue(mine.get("stay").asBoolean());
        assertTrue(mine.get("current").asBoolean(), "the list says which one is asking");
        long days = (mine.get("expires_at").asLong() - mine.get("created_at").asLong()) / 86_400;
        assertEquals(30, days, "stay signed in: 30 days");
        assertEquals(12 * 3600, newest.get("expires_at").asLong() - newest.get("created_at").asLong(), "without: 12 hours");
        assertEquals(7, mine.size(), "id, device, stay, three times, current");
    }

    @Test
    void signingOutIsForGoodAndOnlyForThatDevice() throws Exception {
        registerConfirmed("gilberto", "secret12");
        String phone = tokenOf(login("gilberto", true, IPHONE));
        String laptop = tokenOf(login("gilberto", true, CHROME));

        assertEquals(200, status("POST", "/logout", null, phone));
        assertEquals(401, status("GET", "/me", null, phone), "the signed-out token is refused");
        assertEquals(200, status("GET", "/me", null, laptop), "the other device is still signed in");
        assertEquals(2, sessions(laptop).size(), "registerConfirmed's and the laptop's");
        assertEquals(200, status("POST", "/logout", null, phone), "signing out twice is not an error");
    }

    @Test
    void aMemberCanEndAnotherDeviceButNotSomeoneElses() throws Exception {
        registerConfirmed("gilberto", "secret12");
        registerConfirmed("mayayo", "secret12");               // her sign-in is session 2
        String phone = tokenOf(login("gilberto", true, IPHONE));
        String laptop = tokenOf(login("gilberto", true, CHROME));
        int laptopSession = sessions(laptop).get(0).get("session_id").asInt();

        assertEquals(404, status("DELETE", "/sessions/2", null, phone), "Mayayo's session: as if it did not exist");
        assertEquals(404, status("DELETE", "/sessions/999", null, phone));
        assertEquals(401, status("DELETE", "/sessions/" + laptopSession, null, null));
        assertEquals(200, status("DELETE", "/sessions/" + laptopSession, null, phone));
        assertEquals(401, status("GET", "/me", null, laptop));
        assertEquals(200, status("GET", "/me", null, phone));
        assertEquals(401, status("GET", "/sessions", null, null));
    }

    @Test
    void signingOutEverywhereElseKeepsThisDeviceWithANewToken() throws Exception {
        registerConfirmed("gilberto", "secret12");
        String phone = tokenOf(login("gilberto", true, IPHONE));
        String laptop = tokenOf(login("gilberto", true, CHROME));
        String from27 = jwtService.issue(2, 0);                // a token issued by 2.7, before sessions

        HttpResponse<String> response = send("POST", "/sessions/sign-out-others", null, phone);
        assertEquals(200, response.statusCode());
        assertEquals(2, mapper.readTree(response.body()).get("ended").asInt(), "registerConfirmed's and the laptop's");
        String newPhone = tokenOf(response);

        assertEquals(401, status("GET", "/me", null, laptop));
        assertEquals(401, status("GET", "/me", null, from27), "tokens from before 2.8 end too");
        assertEquals(401, status("GET", "/me", null, phone), "this device's old token is replaced...");
        assertEquals(200, status("GET", "/me", null, newPhone), "...by the new one, for the same session");
        assertEquals(1, sessions(newPhone).size());
    }

    @Test
    void aTokenFromBeforeSessionsKeepsWorkingButCannotDoWhatNeedsASession() throws Exception {
        registerConfirmed("gilberto", "secret12");
        String from27 = jwtService.issue(2, 0);

        assertEquals(200, status("GET", "/me", null, from27), "members stay signed in across the upgrade");
        assertEquals(200, status("POST", "/messages", messageJson(2, "still here", 1L), from27));
        assertFalse(sessions(from27).get(0).get("current").asBoolean(), "no session is this token's");
        assertEquals(409, status("POST", "/sessions/sign-out-others", null, from27), "sign in again first");
        assertEquals(409, status("POST", "/push/subscription", subscriptionJson("old"), from27));
        assertEquals(200, status("POST", "/logout", null, from27));
        assertEquals(401, status("GET", "/me", null, from27), "and signing it out still works");
    }

    @Test
    void aNewPasswordEndsEverySessionAndItsNotifications() throws Exception {
        registerConfirmed("gilberto", "secret12");
        String phone = tokenOf(login("gilberto", true, IPHONE));
        assertEquals(200, status("POST", "/push/subscription", subscriptionJson("phone"), phone));

        assertEquals(200, status("POST", "/password/forgot", emailJson("gilberto@example.test"), null));
        String secret = secretMailedTo("gilberto@example.test", "reset");
        assertEquals(200, status("POST", "/password/reset", "{\"token\":\"" + secret + "\",\"password\":\"another-secret-1\"}", null));

        assertEquals(401, status("GET", "/me", null, phone));
        assertEquals(0, rows("account_session"));
        assertEquals(0, rows("push_subscription"), "a device that was signed out receives nothing more");
    }

    // ------------------------------------------------------------------ notifications

    @Test
    void healthAndTheKeyTellTheWebAppThatNotificationsAreOn() throws Exception {
        JsonNode health = mapper.readTree(get("/health").body());
        assertEquals("on", health.get("push").asText());
        assertEquals("kept", health.get("sessions").asText());
        HttpResponse<String> key = get("/push/key");
        assertEquals(200, key.statusCode(), "public: the key is meant to be handed out");
        assertEquals(VAPID_PUBLIC, mapper.readTree(key.body()).get("public_key").asText());
    }

    @Test
    void aMentionReachesTheMembersDeviceEncryptedAndSignedAndOpensMentions() throws Exception {
        String gilberto = registerConfirmed("gilberto", "secret12");
        registerConfirmed("mayayo", "secret12");
        String mayayo = tokenOf(login("mayayo", true, IPHONE));
        assertEquals(200, status("POST", "/push/subscription", subscriptionJson("mayayo-phone"), mayayo));
        assertTrue(mapper.readTree(send("POST", "/push/subscription/check", "{\"endpoint\":\"" + endpoint("mayayo-phone") + "\"}", mayayo)
                .body()).get("on").asBoolean());

        assertEquals(200, status("POST", "/messages", messageJson(2, "Welcome @Mayayo, glad you made it", 1L), gilberto));
        awaitPushes(1);

        Pushed pushed = received.get(0);
        assertEquals("/push/mayayo-phone", pushed.path());
        assertEquals("aes128gcm", pushed.headers().get("content-encoding"));
        assertEquals("86400", pushed.headers().get("ttl"));
        assertTrue(pushed.headers().get("authorization").startsWith("vapid t="));
        assertTrue(pushed.headers().get("authorization").endsWith(", k=" + VAPID_PUBLIC));
        assertFalse(new String(pushed.body(), StandardCharsets.ISO_8859_1).contains("Mayayo"), "the push service cannot read it");

        JsonNode notification = read(pushed);
        assertEquals("gilberto mentioned you", notification.get("title").asText());
        assertEquals("Welcome @Mayayo, glad you made it", notification.get("body").asText());
        assertEquals("/#/mentions", notification.get("url").asText());
        assertEquals(2, notification.get("message_id").asInt());
    }

    @Test
    void onlyNewMentionsOfOtherPeopleNotify() throws Exception {
        String gilberto = registerConfirmed("gilberto", "secret12");
        String mayayo = stayMember("mayayo");
        assertEquals(200, status("POST", "/push/subscription", subscriptionJson("mayayo"), mayayo));

        assertEquals(200, status("POST", "/messages", messageJson(3, "mayayo here, writing my own name", 1L), mayayo));
        assertEquals(200, status("POST", "/messages", messageJson(2, "nobody named", 1L), gilberto));
        assertEquals(200, status("POST", "/messages", messageJson(2, "hi mayayo", 1L), gilberto));     // message 4
        awaitPushes(1);
        assertEquals(200, status("PATCH", "/messages/4", textJson("hi mayayo!"), gilberto));         // the same name again
        assertEquals(200, status("PATCH", "/messages/3", textJson("now naming mayayo"), gilberto));  // a new mention by edit
        awaitPushes(2);
        assertEquals("now naming mayayo", read(received.get(1)).get("body").asText(),
                "nothing for her own post, for a post without her, or for an edit that kept her name");
    }

    @Test
    void aSubscriptionThePushServiceNoLongerKnowsIsDeleted() throws Exception {
        String gilberto = registerConfirmed("gilberto", "secret12");
        String mayayo = stayMember("mayayo");
        assertEquals(200, status("POST", "/push/subscription", subscriptionJson("uninstalled"), mayayo));
        answers.put("/push/uninstalled", 410);

        assertEquals(200, status("POST", "/messages", messageJson(2, "mayayo?", 1L), gilberto));
        awaitPushes(1);
        long until = System.currentTimeMillis() + 5_000;
        while (rows("push_subscription") > 0 && System.currentTimeMillis() < until) {
            Thread.sleep(20);
        }
        assertEquals(0, rows("push_subscription"));
    }

    @Test
    void signingOutStopsTheNotificationsOfThatDevice() throws Exception {
        String gilberto = registerConfirmed("gilberto", "secret12");
        registerConfirmed("mayayo", "secret12");
        String phone = tokenOf(login("mayayo", true, IPHONE));
        String laptop = tokenOf(login("mayayo", true, CHROME));
        assertEquals(200, status("POST", "/push/subscription", subscriptionJson("phone"), phone));
        assertEquals(200, status("POST", "/push/subscription", subscriptionJson("laptop"), laptop));

        assertEquals(200, status("POST", "/logout", null, phone));
        assertEquals(200, status("POST", "/messages", messageJson(2, "mayayo, are you there?", 1L), gilberto));
        awaitPushes(1);
        assertEquals("/push/laptop", received.get(0).path(), "only the device that is still signed in");
    }

    @Test
    void turningOffAndCheckingAreForTheMembersOwnDevice() throws Exception {
        String gilberto = registerConfirmed("gilberto", "secret12");
        String mayayo = stayMember("mayayo");
        assertEquals(200, status("POST", "/push/subscription", subscriptionJson("phone"), mayayo));
        String which = "{\"endpoint\":\"" + endpoint("phone") + "\"}";

        assertFalse(mapper.readTree(send("POST", "/push/subscription/check", which, gilberto).body()).get("on").asBoolean());
        assertEquals(200, status("POST", "/push/subscription/deletion", which, gilberto), "not his: nothing happens");
        assertEquals(1, rows("push_subscription"));
        assertEquals(200, status("POST", "/push/subscription/deletion", which, mayayo));
        assertEquals(0, rows("push_subscription"));
        assertEquals(401, status("POST", "/push/subscription/deletion", which, null));
    }

    @Test
    void aSubscriptionMustPointAtAPushServiceAndCarryRealKeys() throws Exception {
        String mayayo = stayMember("mayayo");
        String keys = ",\"keys\":{\"p256dh\":\"" + WebPushCrypto.base64(browserPublic) + "\",\"auth\":\"" + WebPushCrypto.base64(auth) + "\"}}";

        assertEquals(401, status("POST", "/push/subscription", subscriptionJson("x"), null));
        assertEquals(400, status("POST", "/push/subscription", "{\"endpoint\":\"https://evil.example/collect\"" + keys, mayayo));
        assertEquals(400, status("POST", "/push/subscription", "{\"endpoint\":\"http://127.0.0.1:5432/\"" + keys, mayayo));
        assertEquals(400, status("POST", "/push/subscription", "{\"endpoint\":\"" + endpoint("x") + "\"}", mayayo), "no keys");
        assertEquals(400, status("POST", "/push/subscription", "{\"endpoint\":\"" + endpoint("x")
                + "\",\"keys\":{\"p256dh\":\"BAAA\",\"auth\":\"" + WebPushCrypto.base64(auth) + "\"}}", mayayo), "not a key");
        assertEquals(400, status("POST", "/push/subscription", "not json", mayayo));
        assertEquals(0, rows("push_subscription"));
    }

    @Test
    void aTestNotificationGoesToThisDeviceAtMostOnceAMinute() throws Exception {
        String mayayo = stayMember("mayayo");
        assertEquals(200, status("POST", "/push/subscription", subscriptionJson("phone"), mayayo));

        HttpResponse<String> first = send("POST", "/push/test", null, mayayo);
        assertEquals(200, first.statusCode());
        assertEquals(1, mapper.readTree(first.body()).get("sent").asInt());
        awaitPushes(1);
        assertEquals("Brief!", read(received.get(0)).get("title").asText());

        HttpResponse<String> again = send("POST", "/push/test", null, mayayo);
        assertEquals(429, again.statusCode());
        assertTrue(again.headers().firstValue("Retry-After").isPresent());
    }

    @Test
    void deletingTheAccountDeletesItsSessionsAndDevices() throws Exception {
        registerConfirmed("mayayo", "secret12");
        String phone = tokenOf(login("mayayo", true, IPHONE));
        assertEquals(200, status("POST", "/push/subscription", subscriptionJson("phone"), phone));

        assertEquals(200, status("POST", "/accounts/2/deletion", "{\"password\":\"secret12\"}", phone));
        assertEquals(0, rows("account_session"));
        assertEquals(0, rows("push_subscription"));
    }

    // ---- independent review of 2.8.0 ----

    @Test
    void notificationsNeedStaySignedIn() throws Exception {
        String shortSession = registerConfirmed("mayayo", "secret12"); // signed in without "Stay signed in"
        assertEquals(409, status("POST", "/push/subscription", subscriptionJson("library-pc"), shortSession),
                "a 12-hour session, as on a shared computer, cannot turn them on");
        assertEquals(0, rows("push_subscription"));
    }

    @Test
    void anotherMemberCannotTakeOverAKnownEndpoint() throws Exception {
        String mayayo = stayMember("mayayo");
        String gilberto = stayMember("gilberto");
        assertEquals(200, status("POST", "/push/subscription", subscriptionJson("mayayo-phone"), mayayo));
        String sameEndpointOtherSecret = "{\"endpoint\":\"" + endpoint("mayayo-phone") + "\",\"keys\":{\"p256dh\":\""
                + WebPushCrypto.base64(browserPublic) + "\",\"auth\":\"" + WebPushCrypto.base64("guessed-secret!!".getBytes(StandardCharsets.US_ASCII)) + "\"}}";
        assertEquals(400, status("POST", "/push/subscription", sameEndpointOtherSecret, gilberto));
        assertTrue(mapper.readTree(send("POST", "/push/subscription/check", "{\"endpoint\":\"" + endpoint("mayayo-phone") + "\"}", mayayo)
                .body()).get("on").asBoolean(), "still Mayayo's");
    }

    @Test
    void anOldTokenReplacedBySignOutEverywhereElseCannotEndTheRenewedSession() throws Exception {
        registerConfirmed("gilberto", "secret12");
        String tabA = tokenOf(login("gilberto", true, CHROME));
        String renewed = tokenOf(send("POST", "/sessions/sign-out-others", null, tabA));
        // Another tab still holds the old token, and its member presses Sign out there.
        assertEquals(200, status("POST", "/logout", null, tabA));
        assertEquals(200, status("GET", "/me", null, renewed), "the session that replaced it is still signed in");
    }
}
