package net.onetechly.brief.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Issues and checks login tokens in the JWT format (JSON Web Token, RFC 7519).
 *
 * A JWT is three pieces of text joined by dots:
 *
 *     header . payload . signature
 *
 *   header     {"alg":"HS256","typ":"JWT"}                 how the token is signed
 *   payload    {"sub":"7","jti":"...","iat":...,"exp":...}  who it is for and until when
 *   signature  HMAC-SHA256(header + "." + payload, secret key)
 *
 * Each piece is Base64URL encoded. The payload is NOT encrypted: anyone can read it, so
 * nothing secret goes in it. What the signature gives is integrity: only the server knows
 * the key, so only the server can produce a signature that matches. Change one character
 * of the payload and the signature no longer fits.
 *
 * Compared with 2.0 (random tokens kept in a map), the server no longer has to remember
 * every token: it can check one using only the key. That is what "stateless" means.
 *
 * The one thing a stateless token cannot do by itself is end early. So sign-out keeps a
 * small list of revoked token ids until those tokens would have expired anyway. That list
 * is in memory: with a fixed BRIEF_JWT_SECRET, a restart forgets it and a signed-out token
 * works again until it expires. A larger system keeps the list in the database or in Redis.
 *
 * The signing uses only the JDK (javax.crypto), so every step is visible in this file. A
 * library such as jjwt or Spring's oauth2-resource-server does the same job and is the usual
 * choice in a larger system.
 */
@Service
public class JwtService {

    private static final Logger log = LoggerFactory.getLogger(JwtService.class);

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    /** HS256 needs a key at least as long as its output: 256 bits = 32 bytes. */
    private static final int MIN_KEY_BYTES = 32;

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();
    /** Every token this server issues starts with exactly this header. */
    private static final String HEADER = ENCODER.encodeToString(
            "{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));

    private final ObjectMapper mapper = new ObjectMapper();
    private final SecureRandom random = new SecureRandom();
    /** Token id -> the second at which that token expires. Only tokens that were signed out. */
    private final Map<String, Long> revoked = new ConcurrentHashMap<>();

    private final byte[] key;
    private final long ttlSeconds;
    private final LongSupplier clockSeconds;

    /** The constructor Spring uses. The values come from application.properties. */
    @Autowired
    public JwtService(@Value("${brief.jwt.secret:}") String secret,
                      @Value("${brief.jwt.ttl-minutes:720}") long ttlMinutes) {
        this(keyFrom(secret), ttlMinutes * 60, () -> System.currentTimeMillis() / 1000);
    }

    /** The clock is injectable so tests can move time forward without sleeping. */
    public JwtService(byte[] key, long ttlSeconds, LongSupplier clockSeconds) {
        if (key == null || key.length < MIN_KEY_BYTES) {
            throw new IllegalStateException("The JWT signing key must be at least " + MIN_KEY_BYTES + " bytes");
        }
        if (ttlSeconds <= 0) {
            throw new IllegalStateException("The JWT lifetime must be positive");
        }
        this.key = key.clone();
        this.ttlSeconds = ttlSeconds;
        this.clockSeconds = clockSeconds;
    }

    private static byte[] keyFrom(String secret) {
        if (secret == null || secret.isBlank()) {
            // No key configured: make one for this run. Nothing secret is ever written in the source code.
            byte[] generated = new byte[MIN_KEY_BYTES];
            new SecureRandom().nextBytes(generated);
            log.info("BRIEF_JWT_SECRET is not set: using a random signing key for this run. "
                    + "Members sign in again after a restart.");
            return generated;
        }
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < MIN_KEY_BYTES) {
            throw new IllegalStateException("BRIEF_JWT_SECRET (brief.jwt.secret) must be at least "
                    + MIN_KEY_BYTES + " characters long");
        }
        return bytes;
    }

    /** Creates a signed token for this account. */
    public String issue(int accountId) {
        long now = clockSeconds.getAsLong();
        byte[] id = new byte[16];
        random.nextBytes(id);

        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("sub", String.valueOf(accountId));       // subject: who the token is for
        claims.put("jti", ENCODER.encodeToString(id));       // token id: lets one token be revoked
        claims.put("iat", now);                              // issued at
        claims.put("exp", now + ttlSeconds);                 // expires at
        String payload;
        try {
            payload = ENCODER.encodeToString(mapper.writeValueAsBytes(claims));
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Could not write the token payload", e);
        }
        String signedPart = HEADER + "." + payload;
        return signedPart + "." + ENCODER.encodeToString(sign(signedPart));
    }

    /**
     * Returns the account id the token belongs to, or null if the token is missing, malformed,
     * signed with another key, altered, expired, or signed out.
     */
    public Integer resolve(String token) {
        JsonNode claims = verifiedClaims(token);
        if (claims == null || revoked.containsKey(claims.path("jti").asText())) {
            return null;
        }
        try {
            return Integer.valueOf(claims.path("sub").asText());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Ends a token early (sign-out). Unknown, invalid or already revoked tokens are ignored (idempotent). */
    public void revoke(String token) {
        JsonNode claims = verifiedClaims(token);
        if (claims == null) {
            return; // not a token of ours, or already expired: nothing to remember
        }
        long now = clockSeconds.getAsLong();
        revoked.values().removeIf(expiresAt -> expiresAt <= now); // forget tokens that have expired anyway
        revoked.put(claims.path("jti").asText(), claims.path("exp").asLong());
    }

    /** Reads the token out of an "Authorization: Bearer <token>" header value. */
    public static String extractBearerToken(String headerValue) {
        if (headerValue == null) {
            return null;
        }
        String value = headerValue.trim();
        if (value.length() > 7 && value.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return value.substring(7).trim();
        }
        return null;
    }

    /** The payload of a token whose signature is correct and which has not expired; otherwise null. */
    private JsonNode verifiedClaims(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        String[] parts = token.split("\\.", -1);
        // The header must be exactly ours. This also refuses tokens that claim another
        // algorithm, including the classic attack of sending "alg":"none".
        if (parts.length != 3 || !HEADER.equals(parts[0])) {
            return null;
        }
        try {
            byte[] expected = sign(parts[0] + "." + parts[1]);
            byte[] actual = DECODER.decode(parts[2]);
            // MessageDigest.isEqual takes the same time whether the first or the last byte differs,
            // so the response time does not leak how much of a forged signature was right.
            if (!MessageDigest.isEqual(expected, actual)) {
                return null;
            }
            // Only now, with the signature verified, is the payload trusted enough to read.
            JsonNode claims = mapper.readTree(DECODER.decode(parts[1]));
            if (claims == null || !claims.path("exp").canConvertToLong()
                    || clockSeconds.getAsLong() >= claims.path("exp").asLong()) {
                return null;
            }
            return claims;
        } catch (IllegalArgumentException | java.io.IOException e) {
            return null; // not Base64URL, or not JSON
        }
    }

    private byte[] sign(String text) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM); // a Mac object is not thread-safe: one per call
            mac.init(new SecretKeySpec(key, HMAC_ALGORITHM));
            return mac.doFinal(text.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 is not available", e);
        }
    }
}
