package net.onetechly.brief.push;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.Signature;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/** Brief!'s signature towards the push services (VAPID, RFC 8292). */
class VapidTest {

    /** A pair printed by the web-push package (generateVAPIDKeys), the tool the deploy notes name. */
    static final String PUBLIC = "BDL88VG70KjjiTwbkEkX6f8ZWr0lf0GtDVQ0s9IcLYlqDnUOOuo0GpjLpghek6AhlrtzMCqZ-3nsMVx7ZYYvrRQ";
    static final String PRIVATE = "mX7F7jgLiYtSeUul7787pXC4K8Wu7af0ETxjduTOVkE";

    private final AtomicLong now = new AtomicLong(1_800_000_000L);
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void withNoKeysNotificationsAreOff() {
        Vapid off = new Vapid("", " ", "https://briefmessage.net", now::get);
        assertFalse(off.isConfigured());
        assertNull(off.publicKey());
        assertThrows(IllegalStateException.class, () -> off.authorization(URI.create("https://fcm.googleapis.com/x")));
    }

    @Test
    void halfAConfigurationOrAPairThatDoesNotMatchStopsTheStart() {
        assertThrows(IllegalStateException.class, () -> new Vapid(PUBLIC, "", "https://b.example", now::get), "one of two");
        assertThrows(IllegalStateException.class, () -> new Vapid("", PRIVATE, "https://b.example", now::get), "the other one");
        String otherPublic = WebPushCrypto.base64(WebPushCrypto.encode(
                (java.security.interfaces.ECPublicKey) WebPushCrypto.newKeyPair().getPublic()));
        IllegalStateException mismatch = assertThrows(IllegalStateException.class,
                () -> new Vapid(otherPublic, PRIVATE, "https://b.example", now::get));
        assertTrue(mismatch.getMessage().contains("not one pair"));
        assertThrows(IllegalStateException.class, () -> new Vapid("not-a-key", PRIVATE, "https://b.example", now::get));
        assertFalse(mismatch.getMessage().contains(PRIVATE), "the private key is never printed");
    }

    @Test
    void theHeaderIsAnEs256TokenForThePushServiceSignedWithThePrivateKey() throws Exception {
        Vapid vapid = new Vapid(PUBLIC, PRIVATE, "https://briefmessage.net", now::get);
        assertTrue(vapid.isConfigured());
        assertEquals(PUBLIC, vapid.publicKey());

        String header = vapid.authorization(URI.create("https://fcm.googleapis.com/fcm/send/abc:def"));
        assertTrue(header.startsWith("vapid t="));
        assertTrue(header.endsWith(", k=" + PUBLIC));
        String jwt = header.substring("vapid t=".length(), header.indexOf(", k="));
        String[] parts = jwt.split("\\.");
        assertEquals(3, parts.length);

        JsonNode head = mapper.readTree(WebPushCrypto.unbase64(parts[0]));
        assertEquals("ES256", head.get("alg").asText());
        JsonNode claims = mapper.readTree(WebPushCrypto.unbase64(parts[1]));
        assertEquals("https://fcm.googleapis.com", claims.get("aud").asText(), "the origin only, no path");
        assertEquals(now.get() + 12 * 60 * 60, claims.get("exp").asLong(), "at most 24 hours ahead: 12");
        assertEquals("https://briefmessage.net", claims.get("sub").asText());

        byte[] signature = WebPushCrypto.unbase64(parts[2]);
        assertEquals(64, signature.length, "r and s, 32 bytes each, as JWT wants (not DER)");
        Signature verifier = Signature.getInstance("SHA256withECDSAinP1363Format");
        verifier.initVerify(WebPushCrypto.publicKey(WebPushCrypto.unbase64(PUBLIC)));
        verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        assertTrue(verifier.verify(signature));
    }

    @Test
    void theAudienceKeepsAPortThatIsNotTheDefault() throws Exception {
        Vapid vapid = new Vapid(PUBLIC, PRIVATE, "https://briefmessage.net", now::get);
        String header = vapid.authorization(URI.create("http://localhost:18200/push/1"));
        String claims = new String(WebPushCrypto.unbase64(header.split("\\.")[1]), StandardCharsets.UTF_8);
        assertTrue(claims.contains("\"aud\":\"http://localhost:18200\""), claims);
    }
}
