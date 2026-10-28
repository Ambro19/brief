package net.onetechly.brief.push;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.interfaces.ECPrivateKey;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The encryption of notifications (RFC 8291), checked against Mozilla's reference implementation.
 *
 * The vectors below were made with the http_ece package (version 1.2.0, by the author of RFC 8188
 * and RFC 8291): a sender key pair, a browser key pair, an auth secret, a salt, and the body
 * http_ece produced from them. Given the same inputs, WebPushCrypto must produce the same bytes.
 * The reverse was checked as well while building 2.8: http_ece decrypted a body made by this
 * class (see CHANGELOG, "Verification").
 */
class WebPushCryptoTest {

    record Vector(String text, String senderPublic, String senderPrivate, String browserPublic,
                  String auth, String salt, String body) {
    }

    private static final List<Vector> REFERENCE = List.of(
            new Vector("When I grow up, I want to be a watermelon",
                    "BKGN-As5B5S9_oPTdZbnNZUq52YKx6amXtXXeoPrf4oswXsGylTRLEgOOoYMO8hr-0u8mAOA-W-lwkTw3oLDD0k", "Ut1QNB7WeNmA8IFFIZ27DqVx5QtmElNBwh5sOZvEIMw",
                    "BCzgsGu4G_C270GNTB91eLiAMdKG7TgkYS6Zjvx47JZlSR06zYydxDjpHNr_kjQYvY22ypQj4NgRRJI9CvMEl-0",
                    "VReMiY2nxF-to2VZAcuC_Q", "BIBmcHKwZE5BdudTObKxAw",
                    "BIBmcHKwZE5BdudTObKxAwAAEABBBKGN-As5B5S9_oPTdZbnNZUq52YKx6amXtXXeoPrf4oswXsGylTRLEgOOoYMO8hr-0u8mAOA-W-lwkTw3oLDD0lFQXdJU36coaSthLR2DL32MNiUCR1k63VyovE0xjnIp-GEcug8U1h49086MwQoe-9WsrsGieNnG8Z9"),
            new Vector("",
                    "BEJLXQZT1jrRmVfh4jLddE1jxZfpgQ4MYTV54ZYv5WysIFOSKg3bTioTZlNsvAuFV7qKfWiVfBMkvV7vsSdP2KM", "33nGr44KdFGyKAIbqx4LXj4-J0bhlOdlZNOws6CFLi8",
                    "BEpsgKoVIiVN65KaklJgqYbrXElL4fh3Cz0flDf1aW72zyv-0XoywY3ii-dDwwMQmEKrGziVx2DOwOLM0hBzX_s",
                    "Wk1A-ZefgvedpkWlieAJSQ", "R3iT6mcGvhYtsZn3BBS4OA",
                    "R3iT6mcGvhYtsZn3BBS4OAAAEABBBEJLXQZT1jrRmVfh4jLddE1jxZfpgQ4MYTV54ZYv5WysIFOSKg3bTioTZlNsvAuFV7qKfWiVfBMkvV7vsSdP2KOwmH36iNVXkUnuCWHS_R_MKw"),
            new Vector("Gilberto mentioned you: \u00fcn\u00efc\u00f6d\u00e9 \u2713 xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx",
                    "BK5KQ4EmJFlGJaWpFHEq6EU9zHlP0RbkoxD48qrsKmjTvQD3ox0Z_8CpRz4Mciz5xScewcNrU-vzASsuXmgFmC8", "7ZIE1FMpjv1hwrKc4mfE4UarvlRJ4NO14uUGoTqUW8s",
                    "BD_vZ7TukrasgD1aIPr8wfk8GD-mJ3b36p8WgZzt5-GdrRunVT0gr0dMED8sbbQSQ0zc8MoLr2Pcd0D1bIJaOF4",
                    "p4q2WC2uetH8piTMnkQ4Qg", "McwKbUvVPDVRzOOmOoiP9w",
                    "McwKbUvVPDVRzOOmOoiP9wAAEABBBK5KQ4EmJFlGJaWpFHEq6EU9zHlP0RbkoxD48qrsKmjTvQD3ox0Z_8CpRz4Mciz5xScewcNrU-vzASsuXmgFmC_Uj2HHqij4nZPtFgCqW6gsJrwKAg8kxaCMCM6w1jeAxYPe0z0AonfEFXrk5Dscwbd2smGrtXBUrk3Z6lpGqAsctrJGbaUWL9jSQK2HIPfoyZanSKKG1wb3JhVXiMqZUhXAdOF_L_-Dy9EDlH_a_BaNyEDwk9s")
    );

    @Test
    void producesExactlyWhatTheReferenceImplementationProduces() {
        for (Vector v : REFERENCE) {
            KeyPair sender = new KeyPair(WebPushCrypto.publicKey(WebPushCrypto.unbase64(v.senderPublic())),
                    WebPushCrypto.privateKey(padded(WebPushCrypto.unbase64(v.senderPrivate()))));
            byte[] body = WebPushCrypto.encrypt(v.text().getBytes(StandardCharsets.UTF_8),
                    WebPushCrypto.unbase64(v.browserPublic()), WebPushCrypto.unbase64(v.auth()), sender,
                    WebPushCrypto.unbase64(v.salt()));
            assertEquals(v.body(), WebPushCrypto.base64(body), "vector: " + v.text());
        }
    }

    @Test
    void theBrowserCanReadWhatWasSentAndEveryMessageLooksDifferent() throws Exception {
        KeyPair browser = WebPushCrypto.newKeyPair();
        byte[] browserPublic = WebPushCrypto.encode((java.security.interfaces.ECPublicKey) browser.getPublic());
        byte[] auth = new byte[16];
        new java.security.SecureRandom().nextBytes(auth);
        String text = "{\"title\":\"Gilberto mentioned you\",\"body\":\"Hi Mayayo \u2713\"}";

        byte[] first = WebPushCrypto.encrypt(text.getBytes(StandardCharsets.UTF_8), browserPublic, auth);
        byte[] second = WebPushCrypto.encrypt(text.getBytes(StandardCharsets.UTF_8), browserPublic, auth);

        assertEquals(text, WebPushDecrypt.decrypt(first, (ECPrivateKey) browser.getPrivate(), browserPublic, auth));
        assertEquals(text, WebPushDecrypt.decrypt(second, (ECPrivateKey) browser.getPrivate(), browserPublic, auth));
        assertNotEquals(WebPushCrypto.base64(first), WebPushCrypto.base64(second), "a fresh key pair and salt every time");
        assertFalse(new String(first, StandardCharsets.ISO_8859_1).contains("Gilberto"), "nothing readable on the way");

        byte[] otherAuth = auth.clone();
        otherAuth[0] ^= 1;
        assertThrows(Exception.class, () -> WebPushDecrypt.decrypt(first, (ECPrivateKey) browser.getPrivate(), browserPublic, otherAuth),
                "the auth secret is part of the key: without it, nothing can be read");
    }

    @Test
    void aBrowserKeyMustBeAPointOnTheCurve() {
        byte[] good = WebPushCrypto.encode((java.security.interfaces.ECPublicKey) WebPushCrypto.newKeyPair().getPublic());
        byte[] offCurve = good.clone();
        offCurve[64] ^= 1; // y changed by one bit: no longer on P-256 (the invalid-curve attack sends such points)
        assertThrows(IllegalArgumentException.class, () -> WebPushCrypto.publicKey(offCurve));
        assertThrows(IllegalArgumentException.class, () -> WebPushCrypto.publicKey(java.util.Arrays.copyOf(good, 64)), "too short");
        byte[] compressed = good.clone();
        compressed[0] = 2;
        assertThrows(IllegalArgumentException.class, () -> WebPushCrypto.publicKey(compressed), "only the uncompressed form");
        assertThrows(IllegalArgumentException.class, () -> WebPushCrypto.privateKey(new byte[32]), "zero is not a key");
    }

    @Test
    void aMessageMustFitInOneRecordAndTheSecretIsSixteenBytes() {
        byte[] browser = WebPushCrypto.encode((java.security.interfaces.ECPublicKey) WebPushCrypto.newKeyPair().getPublic());
        assertDoesNotThrow(() -> WebPushCrypto.encrypt(new byte[WebPushCrypto.MAX_PLAINTEXT], browser, new byte[16]));
        assertThrows(IllegalArgumentException.class, () -> WebPushCrypto.encrypt(new byte[WebPushCrypto.MAX_PLAINTEXT + 1], browser, new byte[16]));
        assertThrows(IllegalArgumentException.class, () -> WebPushCrypto.encrypt(new byte[1], browser, new byte[15]));
    }

    @Test
    void base64UrlIsReadWithOrWithoutPadding() {
        assertArrayEquals(new byte[] {(byte) 0xfb, (byte) 0xff}, WebPushCrypto.unbase64("-_8"));
        assertArrayEquals(new byte[] {(byte) 0xfb, (byte) 0xff}, WebPushCrypto.unbase64("-_8="));
        assertThrows(IllegalArgumentException.class, () -> WebPushCrypto.unbase64("not base64!"));
    }

    /** Node gives a private key without its leading zero bytes; P-256 keys are 32 bytes. */
    private static byte[] padded(byte[] key) {
        byte[] out = new byte[32];
        System.arraycopy(key, 0, out, 32 - key.length, key.length);
        return out;
    }
}
