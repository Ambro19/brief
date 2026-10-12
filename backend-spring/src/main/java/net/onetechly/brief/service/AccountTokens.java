package net.onetechly.brief.service;

import net.onetechly.brief.entity.AccountToken;
import net.onetechly.brief.repository.AccountTokenRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.function.LongSupplier;

/**
 * Makes and checks the secrets of emailed links (2.4).
 *
 * A link such as https://briefmessage.net/?reset=Zx3...Qk carries 32 random bytes written as
 * 43 letters and digits. Whoever holds the link holds the right it gives, so it is treated like
 * a password:
 *
 *   - it is made by SecureRandom, the generator meant for secrets;
 *   - only its SHA-256 hash is stored. A plain hash is enough here, where a password needs a
 *     slow one (PBKDF2): a password is short and guessable, 32 random bytes are not;
 *   - it works once, and only for a limited time;
 *   - making a new link for the same purpose removes the older ones.
 */
@Service
public class AccountTokens {

    /** A confirmation link is good for a day: mail is not always read at once. */
    public static final long VERIFY_SECONDS = 24 * 60 * 60;
    /** A password link is good for an hour: it is asked for by someone who is waiting for it. */
    public static final long RESET_SECONDS = 60 * 60;

    private static final int SECRET_BYTES = 32;
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    private final AccountTokenRepository tokens;
    private final LongSupplier clockSeconds;
    private final SecureRandom random = new SecureRandom();

    /** The constructor Spring uses. */
    @Autowired
    public AccountTokens(AccountTokenRepository tokens) {
        this(tokens, () -> System.currentTimeMillis() / 1000);
    }

    /** The clock is injectable so tests can move time forward without sleeping. */
    public AccountTokens(AccountTokenRepository tokens, LongSupplier clockSeconds) {
        this.tokens = tokens;
        this.clockSeconds = clockSeconds;
    }

    /**
     * Makes a new link secret for this account and purpose, and removes the earlier ones for
     * the same purpose, so that only the newest mail in the inbox works.
     *
     * @return the secret to put into the link. It is not stored and cannot be read back.
     */
    public String issue(int accountId, String purpose) {
        long now = clockSeconds.getAsLong();
        List<AccountToken> earlier = tokens.findByAccountIdAndPurpose(accountId, purpose);
        if (!earlier.isEmpty()) {
            tokens.deleteAll(earlier);
        }
        byte[] secret = new byte[SECRET_BYTES];
        random.nextBytes(secret);
        String text = ENCODER.encodeToString(secret);
        long lifetime = AccountToken.RESET.equals(purpose) ? RESET_SECONDS : VERIFY_SECONDS;
        tokens.save(new AccountToken(accountId, purpose, hash(text), now, now + lifetime));
        return text;
    }

    /**
     * Looks a link up without using it: the link of this purpose that the secret belongs to,
     * if it is still good. Otherwise empty, without saying why: unknown, of the other purpose,
     * used before and too old all look the same from outside.
     */
    public Optional<AccountToken> find(String secret, String purpose) {
        if (secret == null || secret.isBlank()) {
            return Optional.empty();
        }
        Optional<AccountToken> found = tokens.findByTokenHash(hash(secret.trim()));
        if (found.isEmpty() || !found.get().getPurpose().equals(purpose)
                || !found.get().isUsableAt(clockSeconds.getAsLong())) {
            return Optional.empty();
        }
        return found;
    }

    /** Uses a link: as find, and marks the link used, so that it does not work a second time. */
    public Optional<AccountToken> redeem(String secret, String purpose) {
        Optional<AccountToken> found = find(secret, purpose);
        if (found.isPresent()) {
            found.get().setUsedAt(clockSeconds.getAsLong());
            tokens.save(found.get());
        }
        return found;
    }

    /**
     * How many seconds ago the newest link of this purpose was made for this account, or -1 if
     * there is none. Used to avoid sending mail after mail to one address.
     */
    public long secondsSinceLastIssue(int accountId, String purpose) {
        long newest = -1;
        for (AccountToken token : tokens.findByAccountIdAndPurpose(accountId, purpose)) {
            newest = Math.max(newest, token.getCreatedAt());
        }
        return newest < 0 ? -1 : Math.max(0, clockSeconds.getAsLong() - newest);
    }

    /** SHA-256 of the secret, as 64 hexadecimal characters. */
    static String hash(String secret) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available on this JVM", e);
        }
    }
}
