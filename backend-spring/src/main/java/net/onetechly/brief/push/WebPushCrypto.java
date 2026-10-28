package net.onetechly.brief.push;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECFieldFp;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPrivateKeySpec;
import java.security.spec.ECPublicKeySpec;
import java.util.Arrays;
import java.util.Base64;

/**
 * The encryption of a Web Push message (RFC 8291), with nothing but the JDK.
 *
 * A notification travels through the push service of the member's browser maker (Google for
 * Chrome and Edge on Android, Mozilla for Firefox, Apple for Safari, Microsoft for Edge on
 * Windows). That service must not be able to read it, so it is encrypted for the browser alone:
 *
 *   1. The browser made a P-256 key pair and a 16-byte secret ("auth") when notifications were
 *      turned on, and gave Brief! the public key ("p256dh") and the secret.
 *   2. For every message Brief! makes a fresh key pair of its own, and agrees a shared secret
 *      with the browser's public key (ECDH). Only the browser can agree the same secret.
 *   3. From that secret, the auth secret and a random 16-byte salt, HKDF (HMAC-SHA256) derives a
 *      16-byte content key and a 12-byte nonce.
 *   4. The text is encrypted with AES-128-GCM, in the "aes128gcm" format of RFC 8188: a header
 *      (salt, record size, Brief!'s public key for this message) followed by one record.
 *
 * Every step is in this file so it can be read and checked. The output was compared byte for
 * byte with Mozilla's reference implementation (the http_ece package), and decrypted by it: see
 * WebPushCryptoTest. A library (nl.martijndwars:web-push with BouncyCastle) does the same job and
 * is the usual choice; this one costs no dependency and keeps the security-relevant code in view.
 */
public final class WebPushCrypto {

    /** The record size written in the header. One record holds the whole message. */
    static final int RECORD_SIZE = 4096;
    /** AES-GCM adds a 16-byte tag; one byte is the padding delimiter. */
    public static final int MAX_PLAINTEXT = RECORD_SIZE - 16 - 1;

    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64_DECODE = Base64.getUrlDecoder();
    private static final ECParameterSpec P256 = p256();
    private static final SecureRandom RANDOM = new SecureRandom();

    private WebPushCrypto() {
    }

    /** Encrypts for one browser, with a fresh key pair and salt: what is sent. */
    public static byte[] encrypt(byte[] plaintext, byte[] browserPublicKey, byte[] authSecret) {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        return encrypt(plaintext, browserPublicKey, authSecret, newKeyPair(), salt);
    }

    /**
     * As above, with the key pair and salt given. Only tests call this directly: reusing a key
     * pair or a salt for two messages would weaken the encryption.
     */
    static byte[] encrypt(byte[] plaintext, byte[] browserPublicKey, byte[] authSecret, KeyPair ownKeys, byte[] salt) {
        if (plaintext.length > MAX_PLAINTEXT) {
            throw new IllegalArgumentException("A push message holds at most " + MAX_PLAINTEXT + " bytes");
        }
        if (authSecret == null || authSecret.length != 16 || salt.length != 16) {
            throw new IllegalArgumentException("The auth secret and the salt are 16 bytes each");
        }
        try {
            ECPublicKey browserKey = publicKey(browserPublicKey);
            byte[] ownPublic = encode((ECPublicKey) ownKeys.getPublic());

            KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
            agreement.init(ownKeys.getPrivate());
            agreement.doPhase(browserKey, true);
            byte[] sharedSecret = agreement.generateSecret();

            // RFC 8291 section 3.4: mix in the auth secret and both public keys.
            byte[] prkKey = hmac(authSecret, sharedSecret);
            byte[] keyInfo = concat("WebPush: info\0".getBytes(StandardCharsets.US_ASCII), browserPublicKey, ownPublic, new byte[] {1});
            byte[] ikm = hmac(prkKey, keyInfo);

            // RFC 8188 section 2.2 and 2.3: the content key and the nonce.
            byte[] prk = hmac(salt, ikm);
            byte[] cek = Arrays.copyOf(hmac(prk, concat("Content-Encoding: aes128gcm\0".getBytes(StandardCharsets.US_ASCII), new byte[] {1})), 16);
            byte[] nonce = Arrays.copyOf(hmac(prk, concat("Content-Encoding: nonce\0".getBytes(StandardCharsets.US_ASCII), new byte[] {1})), 12);

            // One record, so it is the last one: the text is followed by the delimiter 0x02, without padding.
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(cek, "AES"), new GCMParameterSpec(128, nonce));
            byte[] record = cipher.doFinal(concat(plaintext, new byte[] {2}));

            // The header: salt (16), record size (4), key id length (1), key id = our public key (65).
            ByteBuffer header = ByteBuffer.allocate(16 + 4 + 1 + ownPublic.length);
            header.put(salt).putInt(RECORD_SIZE).put((byte) ownPublic.length).put(ownPublic);
            return concat(header.array(), record);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Web Push encryption failed", e);
        }
    }

    // ------------------------------------------------------------------ keys

    /** A new P-256 key pair. */
    public static KeyPair newKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"), RANDOM);
            return generator.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("P-256 is not available", e);
        }
    }

    /**
     * A public key from its uncompressed form: 0x04, then x and y, 32 bytes each. That is how
     * browsers hand it over (p256dh) and how VAPID public keys are written. Refuses anything that
     * is not a point on the curve: agreeing a secret with such a "key" can leak the private key
     * (an invalid-curve attack), and the browser's key comes from outside.
     */
    public static ECPublicKey publicKey(byte[] uncompressed) {
        if (uncompressed == null || uncompressed.length != 65 || uncompressed[0] != 4) {
            throw new IllegalArgumentException("Not an uncompressed P-256 public key");
        }
        BigInteger x = new BigInteger(1, Arrays.copyOfRange(uncompressed, 1, 33));
        BigInteger y = new BigInteger(1, Arrays.copyOfRange(uncompressed, 33, 65));
        if (!onCurve(x, y)) {
            throw new IllegalArgumentException("The point is not on the P-256 curve");
        }
        try {
            return (ECPublicKey) KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(new ECPoint(x, y), P256));
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("Not a usable P-256 public key", e);
        }
    }

    /** A private key from its 32-byte scalar, the form in which VAPID private keys are written. */
    public static ECPrivateKey privateKey(byte[] scalar) {
        if (scalar == null || scalar.length != 32) {
            throw new IllegalArgumentException("A P-256 private key is 32 bytes");
        }
        BigInteger d = new BigInteger(1, scalar);
        if (d.signum() == 0 || d.compareTo(P256.getOrder()) >= 0) {
            throw new IllegalArgumentException("Not a valid P-256 private key");
        }
        try {
            return (ECPrivateKey) KeyFactory.getInstance("EC").generatePrivate(new ECPrivateKeySpec(d, P256));
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("Not a usable P-256 private key", e);
        }
    }

    /** The uncompressed form of a public key: 0x04, x, y. */
    public static byte[] encode(ECPublicKey key) {
        return concat(new byte[] {4}, fixed32(key.getW().getAffineX()), fixed32(key.getW().getAffineY()));
    }

    /** The 32-byte form of a private key. */
    static byte[] encode(ECPrivateKey key) {
        return fixed32(key.getS());
    }

    public static String base64(byte[] bytes) {
        return B64.encodeToString(bytes);
    }

    /** Base64url, with or without padding (browsers and tools write it without). Throws IllegalArgumentException if it is not. */
    public static byte[] unbase64(String text) {
        if (text == null) {
            throw new IllegalArgumentException("Missing value");
        }
        String trimmed = text.trim();
        while (trimmed.endsWith("=")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return B64_DECODE.decode(trimmed);
    }

    // ------------------------------------------------------------------ helpers

    private static boolean onCurve(BigInteger x, BigInteger y) {
        BigInteger p = ((ECFieldFp) P256.getCurve().getField()).getP();
        if (x.compareTo(p) >= 0 || y.compareTo(p) >= 0) {
            return false;
        }
        BigInteger left = y.multiply(y).mod(p);
        BigInteger right = x.pow(3).add(P256.getCurve().getA().multiply(x)).add(P256.getCurve().getB()).mod(p);
        return left.equals(right);
    }

    private static byte[] fixed32(BigInteger value) {
        byte[] raw = value.toByteArray(); // big-endian, possibly with a leading 0 or shorter than 32
        byte[] out = new byte[32];
        int copy = Math.min(raw.length, 32);
        System.arraycopy(raw, raw.length - copy, out, 32 - copy, copy);
        return out;
    }

    static byte[] hmac(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 is not available", e);
        }
    }

    static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }

    private static ECParameterSpec p256() {
        try {
            AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
            parameters.init(new ECGenParameterSpec("secp256r1"));
            return parameters.getParameterSpec(ECParameterSpec.class);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("P-256 is not available", e);
        }
    }
}
