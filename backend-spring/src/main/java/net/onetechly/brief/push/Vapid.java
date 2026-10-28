package net.onetechly.brief.push;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Brief!'s identity towards the push services (VAPID, RFC 8292).
 *
 * When a browser turns notifications on, it is given Brief!'s public key, and its push service
 * then accepts messages for that browser only when they are signed with the matching private
 * key. So nobody else can send notifications in Brief!'s name, even with a subscription in hand.
 *
 * The signature is a small JWT in the Authorization header of every push request:
 *
 *     Authorization: vapid t=<JWT>, k=<public key>
 *
 *   header   {"typ":"JWT","alg":"ES256"}            ECDSA with P-256 and SHA-256
 *   payload  {"aud":"https://fcm.googleapis.com",   the push service's origin
 *             "exp":<now + 12 hours>,               at most 24 hours ahead (RFC 8292)
 *             "sub":"https://briefmessage.net"}     who to contact about these messages
 *
 * The key pair comes from two environment variables, BRIEF_VAPID_PUBLIC_KEY and
 * BRIEF_VAPID_PRIVATE_KEY, in the base64url form that "npx web-push generate-vapid-keys" prints.
 * The private key is a secret, like BRIEF_JWT_SECRET. With neither set, notifications are off
 * and the app works as in 2.7. With one set, or a pair that does not match, the application
 * refuses to start and says why: half a configuration would fail silently at every send.
 *
 * Changing the key pair later makes every existing subscription useless (the push services
 * refuse it, and Brief! deletes it): members turn notifications on again.
 */
@Component
public class Vapid {

    /** How long a signature is valid. RFC 8292 allows at most 24 hours. */
    static final long LIFETIME_SECONDS = 12L * 60 * 60;

    private static final String HEADER = WebPushCrypto.base64("{\"typ\":\"JWT\",\"alg\":\"ES256\"}".getBytes(StandardCharsets.UTF_8));

    private final ObjectMapper mapper = new ObjectMapper();
    private final ECPublicKey publicKey;
    private final ECPrivateKey privateKey;
    private final String publicKeyText;
    private final String subject;
    private final LongSupplier clockSeconds;

    @Autowired
    public Vapid(@Value("${brief.push.vapid-public-key:}") String publicKey,
                 @Value("${brief.push.vapid-private-key:}") String privateKey,
                 @Value("${brief.push.vapid-subject:}") String subject,
                 @Value("${brief.public-url:http://localhost:8080}") String publicUrl) {
        this(publicKey, privateKey, subject == null || subject.isBlank() ? publicUrl : subject,
                () -> System.currentTimeMillis() / 1000);
    }

    public Vapid(String publicKey, String privateKey, String subject, LongSupplier clockSeconds) {
        boolean hasPublic = publicKey != null && !publicKey.isBlank();
        boolean hasPrivate = privateKey != null && !privateKey.isBlank();
        this.clockSeconds = clockSeconds;
        this.subject = subject == null ? "" : subject.trim();
        if (!hasPublic && !hasPrivate) {
            this.publicKey = null;
            this.privateKey = null;
            this.publicKeyText = null;
            return;
        }
        if (!hasPublic || !hasPrivate) {
            throw new IllegalStateException("Push notifications need both BRIEF_VAPID_PUBLIC_KEY and BRIEF_VAPID_PRIVATE_KEY "
                    + "(npx web-push generate-vapid-keys prints a pair). Set both, or neither to leave notifications off.");
        }
        try {
            this.publicKey = WebPushCrypto.publicKey(WebPushCrypto.unbase64(publicKey));
            this.privateKey = WebPushCrypto.privateKey(WebPushCrypto.unbase64(privateKey));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("BRIEF_VAPID_PUBLIC_KEY or BRIEF_VAPID_PRIVATE_KEY is not a P-256 key in base64url "
                    + "(as printed by npx web-push generate-vapid-keys): " + e.getMessage());
        }
        this.publicKeyText = WebPushCrypto.base64(WebPushCrypto.encode(this.publicKey));
        if (!pairMatches()) {
            throw new IllegalStateException("BRIEF_VAPID_PUBLIC_KEY and BRIEF_VAPID_PRIVATE_KEY are not one pair: "
                    + "copy both from the same run of npx web-push generate-vapid-keys.");
        }
        if (!this.subject.startsWith("https://") && !this.subject.startsWith("mailto:")) {
            // Apple's push service refuses anything else. Locally (http://localhost) that does not matter.
            org.slf4j.LoggerFactory.getLogger(Vapid.class).warn("The VAPID subject \"{}\" is neither https:// nor mailto:. "
                    + "Apple's push service will refuse notifications. Set BRIEF_PUBLIC_URL to the https address of the site.", this.subject);
        }
    }

    /** True when both keys are set: notifications are on. */
    public boolean isConfigured() {
        return publicKey != null;
    }

    /** The public key in base64url, for the browser (applicationServerKey). Null when notifications are off. */
    public String publicKey() {
        return publicKeyText;
    }

    /** The Authorization header for a request to this push service endpoint. */
    public String authorization(URI endpoint) {
        if (!isConfigured()) {
            throw new IllegalStateException("Push notifications are off");
        }
        String audience = endpoint.getScheme() + "://" + endpoint.getHost() + (endpoint.getPort() == -1 ? "" : ":" + endpoint.getPort());
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("aud", audience);
        claims.put("exp", clockSeconds.getAsLong() + LIFETIME_SECONDS);
        claims.put("sub", subject);
        try {
            String signedPart = HEADER + "." + WebPushCrypto.base64(mapper.writeValueAsBytes(claims));
            return "vapid t=" + signedPart + "." + WebPushCrypto.base64(sign(signedPart.getBytes(StandardCharsets.US_ASCII)))
                    + ", k=" + publicKeyText;
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Could not write the VAPID claims", e);
        }
    }

    /** ES256 as JWT wants it: r and s, 32 bytes each (not the DER form Java signs in by default). */
    private byte[] sign(byte[] data) {
        try {
            Signature signature = Signature.getInstance("SHA256withECDSAinP1363Format");
            signature.initSign(privateKey);
            signature.update(data);
            return signature.sign();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("ES256 signing is not available", e);
        }
    }

    /** Signs a test text with the private key and checks it with the public one. */
    private boolean pairMatches() {
        try {
            byte[] probe = "brief-vapid-pair-check".getBytes(StandardCharsets.US_ASCII);
            Signature verifier = Signature.getInstance("SHA256withECDSAinP1363Format");
            verifier.initVerify(publicKey);
            verifier.update(probe);
            return verifier.verify(sign(probe));
        } catch (GeneralSecurityException e) {
            return false;
        }
    }
}
