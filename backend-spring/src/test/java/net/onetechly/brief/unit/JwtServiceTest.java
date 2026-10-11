package net.onetechly.brief.unit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.onetechly.brief.security.JwtService;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class JwtServiceTest {

    private static final byte[] KEY = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);       // 32 bytes
    private static final byte[] OTHER_KEY = "fedcba9876543210fedcba9876543210".getBytes(StandardCharsets.UTF_8);

    private final AtomicLong now = new AtomicLong(1_000_000);
    private final JwtService jwt = new JwtService(KEY, 600, now::get);

    private static String decode(String part) {
        return new String(Base64.getUrlDecoder().decode(part), StandardCharsets.UTF_8);
    }

    private static String encode(String text) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void issuedTokenResolvesToItsAccount() {
        assertEquals(Integer.valueOf(7), jwt.resolve(jwt.issue(7)));
    }

    @Test
    void tokenHasThreePartsAndAReadablePayload() throws Exception {
        String[] parts = jwt.issue(7).split("\\.");
        assertEquals(3, parts.length);
        assertEquals("{\"alg\":\"HS256\",\"typ\":\"JWT\"}", decode(parts[0]));

        JsonNode payload = new ObjectMapper().readTree(decode(parts[1]));
        assertEquals("7", payload.get("sub").asText());
        assertEquals(1_000_000L, payload.get("iat").asLong());
        assertEquals(1_000_600L, payload.get("exp").asLong());
        assertFalse(payload.get("jti").asText().isEmpty());
    }

    @Test
    void twoTokensForTheSameAccountAreDifferent() {
        assertNotEquals(jwt.issue(1), jwt.issue(1));
    }

    @Test
    void missingOrMalformedTokenResolvesToNull() {
        assertNull(jwt.resolve(null));
        assertNull(jwt.resolve(""));
        assertNull(jwt.resolve("not-a-token"));
        assertNull(jwt.resolve("a.b.c"));
        assertNull(jwt.resolve("a.b"));
        assertNull(jwt.resolve(jwt.issue(7) + ".extra"));
    }

    @Test
    void changingThePayloadBreaksTheSignature() {
        String[] parts = jwt.issue(7).split("\\.");
        String forgedPayload = encode(decode(parts[1]).replace("\"sub\":\"7\"", "\"sub\":\"8\""));
        assertNotEquals(parts[1], forgedPayload, "the test should really have changed the payload");

        assertNull(jwt.resolve(parts[0] + "." + forgedPayload + "." + parts[2]));
    }

    @Test
    void tokenSignedWithAnotherKeyIsRejected() {
        JwtService stranger = new JwtService(OTHER_KEY, 600, now::get);
        assertNull(jwt.resolve(stranger.issue(7)));
    }

    @Test
    void unsignedTokenClaimingAlgNoneIsRejected() {
        String payload = jwt.issue(7).split("\\.")[1];
        String noneHeader = encode("{\"alg\":\"none\",\"typ\":\"JWT\"}");
        assertNull(jwt.resolve(noneHeader + "." + payload + "."));
        assertNull(jwt.resolve(noneHeader + "." + payload + "." + jwt.issue(7).split("\\.")[2]));
    }

    @Test
    void tokenExpiresAfterItsLifetime() {
        String token = jwt.issue(3);

        now.set(1_000_599);
        assertEquals(Integer.valueOf(3), jwt.resolve(token));

        now.set(1_000_600);
        assertNull(jwt.resolve(token));
    }

    @Test
    void revokeEndsOneTokenAndIsSafeToRepeat() {
        String first = jwt.issue(9);
        String second = jwt.issue(9);

        jwt.revoke(first);
        jwt.revoke(first);
        jwt.revoke(null);
        jwt.revoke("not-a-token");

        assertNull(jwt.resolve(first));
        assertEquals(Integer.valueOf(9), jwt.resolve(second), "signing out one device leaves the other signed in");
    }

    @Test
    void aKeyShorterThan32BytesIsRefused() {
        assertThrows(IllegalStateException.class, () -> new JwtService(new byte[31], 600, now::get));
        assertThrows(IllegalStateException.class, () -> new JwtService("too-short", 720));
    }

    @Test
    void withNoConfiguredSecretARandomKeyIsUsed() {
        JwtService a = new JwtService("", 720);
        JwtService b = new JwtService(null, 720);
        assertEquals(Integer.valueOf(5), a.resolve(a.issue(5)));
        assertNull(b.resolve(a.issue(5)), "two servers with random keys do not accept each other's tokens");
    }

    @Test
    void bearerTokenIsExtractedFromTheHeader() {
        assertEquals("abc", JwtService.extractBearerToken("Bearer abc"));
        assertEquals("abc", JwtService.extractBearerToken("  bearer   abc  "));
        assertNull(JwtService.extractBearerToken("Bearer "));
        assertNull(JwtService.extractBearerToken("Basic abc"));
        assertNull(JwtService.extractBearerToken("abc"));
        assertNull(JwtService.extractBearerToken(null));
    }
}
