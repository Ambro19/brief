package net.onetechly.brief.push;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.interfaces.ECPrivateKey;
import java.util.Arrays;

/**
 * What a browser does with a notification: the decryption of RFC 8291, for the tests. It plays
 * the browser in the end-to-end tests (to read what Brief! sent), and checks WebPushCrypto from
 * the other side. The steps mirror WebPushCrypto.encrypt; WebPushCryptoTest also checks that
 * encryption against Mozilla's reference implementation, which does not depend on this class.
 */
public final class WebPushDecrypt {

    private WebPushDecrypt() {
    }

    /** The text of an aes128gcm body, for the browser holding this private key and auth secret. */
    public static String decrypt(byte[] body, ECPrivateKey browserPrivate, byte[] browserPublic, byte[] authSecret) throws Exception {
        ByteBuffer header = ByteBuffer.wrap(body);
        byte[] salt = new byte[16];
        header.get(salt);
        int recordSize = header.getInt();
        int idLength = header.get() & 0xff;
        byte[] senderPublic = new byte[idLength];
        header.get(senderPublic);
        byte[] record = Arrays.copyOfRange(body, 21 + idLength, body.length);
        if (record.length > recordSize) {
            throw new IllegalArgumentException("More than one record");
        }

        KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
        agreement.init(browserPrivate);
        agreement.doPhase(WebPushCrypto.publicKey(senderPublic), true);
        byte[] shared = agreement.generateSecret();

        byte[] ikm = WebPushCrypto.hmac(WebPushCrypto.hmac(authSecret, shared), WebPushCrypto.concat(
                "WebPush: info\0".getBytes(StandardCharsets.US_ASCII), browserPublic, senderPublic, new byte[] {1}));
        byte[] prk = WebPushCrypto.hmac(salt, ikm);
        byte[] cek = Arrays.copyOf(WebPushCrypto.hmac(prk, WebPushCrypto.concat("Content-Encoding: aes128gcm\0".getBytes(StandardCharsets.US_ASCII), new byte[] {1})), 16);
        byte[] nonce = Arrays.copyOf(WebPushCrypto.hmac(prk, WebPushCrypto.concat("Content-Encoding: nonce\0".getBytes(StandardCharsets.US_ASCII), new byte[] {1})), 12);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(cek, "AES"), new GCMParameterSpec(128, nonce));
        byte[] padded = cipher.doFinal(record);
        int end = padded.length - 1;
        while (end >= 0 && padded[end] == 0) {
            end--; // padding zeros
        }
        if (end < 0 || padded[end] != 2) {
            throw new IllegalArgumentException("No last-record delimiter");
        }
        return new String(padded, 0, end, StandardCharsets.UTF_8);
    }
}
