package net.onetechly.brief.mail;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.function.LongSupplier;

/**
 * Checks that a webhook call really comes from Resend (2.7).
 *
 * The address /webhooks/resend is public: Resend's servers must reach it without signing in.
 * So anyone could call it and claim "this address bounced". What stops them is the signature.
 * Resend sends its webhooks through Svix, which signs every call with a secret only Resend and
 * Brief! know (BRIEF_RESEND_WEBHOOK_SECRET, shown once in Resend's dashboard as "whsec_...").
 *
 * The check, as Svix documents it:
 *   1. Three headers: svix-id (one per message), svix-timestamp (seconds since 1970) and
 *      svix-signature (one or more "v1,<base64>" separated by spaces).
 *   2. The signed text is  svix-id + "." + svix-timestamp + "." + the raw body, exactly as sent.
 *   3. The key is the secret after "whsec_", decoded from base64.
 *   4. HMAC-SHA256 of the signed text with that key, in base64, must equal one of the v1 values.
 *   5. The timestamp must be within five minutes of now, so a recorded call cannot be replayed later.
 *
 * Comparison uses MessageDigest.isEqual, which takes the same time whether the first or the last
 * byte differs: the time of the answer reveals nothing about the right signature.
 */
@Component
public class ResendWebhookVerifier {

    /** Calls more than this many seconds old, or from the future, are refused. */
    public static final long TOLERANCE_SECONDS = 5 * 60;

    private final byte[] key;
    private final LongSupplier clockSeconds;

    /** The constructor Spring uses. */
    @Autowired
    public ResendWebhookVerifier(@Value("${brief.mail.resend-webhook-secret:}") String secret) {
        this(secret, () -> System.currentTimeMillis() / 1000);
    }

    public ResendWebhookVerifier(String secret, LongSupplier clockSeconds) {
        this.key = decode(secret);
        this.clockSeconds = clockSeconds;
    }

    /** "whsec_" + base64 (the form Resend shows), or the base64 part alone. Blank: no key. */
    private static byte[] decode(String secret) {
        if (secret == null || secret.isBlank()) {
            return null;
        }
        String text = secret.trim();
        if (text.startsWith("whsec_")) {
            text = text.substring("whsec_".length());
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(text);
            if (decoded.length == 0) {
                throw new IllegalStateException("BRIEF_RESEND_WEBHOOK_SECRET is empty after \"whsec_\".");
            }
            return decoded;
        } catch (IllegalArgumentException e) {
            // Stop the start, as with other wrong settings: a typo here would silently refuse every report.
            throw new IllegalStateException("BRIEF_RESEND_WEBHOOK_SECRET must be the signing secret Resend shows, "
                    + "starting with \"whsec_\". The value set is not one.");
        }
    }

    /** False when BRIEF_RESEND_WEBHOOK_SECRET is not set: the webhook address then answers 404. */
    public boolean isConfigured() {
        return key != null;
    }

    /** True only if the three headers are present, recent, and one signature matches the body. */
    public boolean verify(String id, String timestamp, String signatures, String body) {
        if (key == null || id == null || timestamp == null || signatures == null || body == null) {
            return false;
        }
        long sent;
        try {
            sent = Long.parseLong(timestamp.trim());
        } catch (NumberFormatException e) {
            return false;
        }
        if (Math.abs(clockSeconds.getAsLong() - sent) > TOLERANCE_SECONDS) {
            return false;
        }
        byte[] expected = Base64.getEncoder().encode(sign(id + "." + timestamp.trim() + "." + body));
        boolean matched = false;
        for (String part : signatures.trim().split(" +")) {
            if (part.startsWith("v1,")) {
                byte[] given = part.substring(3).getBytes(StandardCharsets.US_ASCII);
                // Look at every signature, without stopping at the first match: same time either way.
                matched |= MessageDigest.isEqual(expected, given);
            }
        }
        return matched;
    }

    /** The signature Resend would send for this message. Public for the tests, which act as Resend. */
    public String signatureFor(String id, String timestamp, String body) {
        return "v1," + Base64.getEncoder().encodeToString(sign(id + "." + timestamp + "." + body));
    }

    private byte[] sign(String text) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(text.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is not available on this JVM", e);
        }
    }
}
