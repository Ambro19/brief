package net.onetechly.brief.unit;

import net.onetechly.brief.mail.ResendWebhookVerifier;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The check that a webhook call comes from Resend (2.7).
 *
 * The first test uses the example Svix (the service Resend sends webhooks through) publishes in
 * its documentation: a secret, a message, and the signature it must produce. It was not made with
 * this code, so it shows the code agrees with Svix, not only with itself.
 */
class ResendWebhookVerifierTest {

    private static final String SVIX_SECRET = "whsec_MfKQ9r8GKYqrTwjUPD8ILPZIo2LaLaSw";
    private static final String SVIX_ID = "msg_p5jXN8AQM9LWM0D4loKWxJek";
    private static final String SVIX_TIMESTAMP = "1614265330";
    private static final String SVIX_BODY = "{\"test\": 2432232314}";
    private static final String SVIX_SIGNATURE = "v1,g0hM9SsE+OTPJTGt/tmIKtSyZlE3uFJELVlNIOLJ1OE=";

    private static ResendWebhookVerifier at(long now) {
        return new ResendWebhookVerifier(SVIX_SECRET, () -> now);
    }

    @Test
    void agreesWithTheExampleInSvixsDocumentation() {
        ResendWebhookVerifier verifier = at(1614265330L);
        assertEquals(SVIX_SIGNATURE, verifier.signatureFor(SVIX_ID, SVIX_TIMESTAMP, SVIX_BODY));
        assertTrue(verifier.verify(SVIX_ID, SVIX_TIMESTAMP, SVIX_SIGNATURE, SVIX_BODY));
    }

    @Test
    void oneMatchingSignatureAmongSeveralIsEnough() {
        // Svix sends several when a secret is being replaced: the old and the new one.
        assertTrue(at(1614265330L).verify(SVIX_ID, SVIX_TIMESTAMP, "v1,AAAAold v1bad " + SVIX_SIGNATURE, SVIX_BODY));
        assertFalse(at(1614265330L).verify(SVIX_ID, SVIX_TIMESTAMP, "v1,AAAAold v2," + SVIX_SIGNATURE.substring(3), SVIX_BODY),
                "only v1 signatures count");
    }

    @Test
    void anyChangeBreaksTheSignature() {
        ResendWebhookVerifier verifier = at(1614265330L);
        assertFalse(verifier.verify(SVIX_ID, SVIX_TIMESTAMP, SVIX_SIGNATURE, "{\"test\": 2432232315}"), "another body");
        assertFalse(verifier.verify(SVIX_ID, SVIX_TIMESTAMP, SVIX_SIGNATURE, SVIX_BODY + " "), "even one space");
        assertFalse(verifier.verify("msg_other", SVIX_TIMESTAMP, SVIX_SIGNATURE, SVIX_BODY), "another message id");
        assertFalse(new ResendWebhookVerifier("whsec_" + "QW5vdGhlclNlY3JldEFub3RoZXJTZWNyZXQ=", () -> 1614265330L)
                .verify(SVIX_ID, SVIX_TIMESTAMP, SVIX_SIGNATURE, SVIX_BODY), "another secret");
    }

    @Test
    void aCallOlderOrNewerThanFiveMinutesIsRefused() {
        long sent = 1614265330L;
        assertTrue(at(sent + 300).verify(SVIX_ID, SVIX_TIMESTAMP, SVIX_SIGNATURE, SVIX_BODY));
        assertFalse(at(sent + 301).verify(SVIX_ID, SVIX_TIMESTAMP, SVIX_SIGNATURE, SVIX_BODY), "replayed later");
        assertFalse(at(sent - 301).verify(SVIX_ID, SVIX_TIMESTAMP, SVIX_SIGNATURE, SVIX_BODY), "dated in the future");
        assertFalse(at(sent).verify(SVIX_ID, "yesterday", SVIX_SIGNATURE, SVIX_BODY), "not a number");
    }

    @Test
    void missingPartsAreRefusedNotErrors() {
        ResendWebhookVerifier verifier = at(1614265330L);
        assertFalse(verifier.verify(null, SVIX_TIMESTAMP, SVIX_SIGNATURE, SVIX_BODY));
        assertFalse(verifier.verify(SVIX_ID, null, SVIX_SIGNATURE, SVIX_BODY));
        assertFalse(verifier.verify(SVIX_ID, SVIX_TIMESTAMP, null, SVIX_BODY));
        assertFalse(verifier.verify(SVIX_ID, SVIX_TIMESTAMP, SVIX_SIGNATURE, null));
        assertFalse(verifier.verify(SVIX_ID, SVIX_TIMESTAMP, "", SVIX_BODY));
    }

    @Test
    void theSecretIsReadAsResendShowsIt() {
        assertTrue(new ResendWebhookVerifier("MfKQ9r8GKYqrTwjUPD8ILPZIo2LaLaSw", () -> 1614265330L)
                .verify(SVIX_ID, SVIX_TIMESTAMP, SVIX_SIGNATURE, SVIX_BODY), "without the whsec_ prefix too");
        assertTrue(new ResendWebhookVerifier("  " + SVIX_SECRET + " ", () -> 1614265330L)
                .verify(SVIX_ID, SVIX_TIMESTAMP, SVIX_SIGNATURE, SVIX_BODY), "spaces from copying are ignored");
        ResendWebhookVerifier none = new ResendWebhookVerifier("", () -> 1614265330L);
        assertFalse(none.isConfigured());
        assertFalse(none.verify(SVIX_ID, SVIX_TIMESTAMP, SVIX_SIGNATURE, SVIX_BODY), "no secret, nothing verifies");
        IllegalStateException wrong = assertThrows(IllegalStateException.class, () -> new ResendWebhookVerifier("whsec_not base64!", () -> 0L));
        assertTrue(wrong.getMessage().contains("BRIEF_RESEND_WEBHOOK_SECRET"), "the start stops with a message that names the setting");
    }
}
