package Service;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Issues and checks login tokens.
 *
 * The training spec says: "In the future, this action may generate a Session token."
 * This is that future. A successful login gets a random token; the client sends it
 * back as  Authorization: Bearer <token>  and the server knows who is calling.
 *
 * Tokens live in memory, so they are lost when the server restarts (users sign in
 * again). That is a deliberate v1.0 trade-off - see the status report for the roadmap.
 */
public class SessionService {

    public static final long DEFAULT_TTL_MILLIS = 12L * 60 * 60 * 1000; // 12 hours

    private static final int TOKEN_BYTES = 32;

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final long ttlMillis;
    private final LongSupplier clock;

    public SessionService() {
        this(DEFAULT_TTL_MILLIS, System::currentTimeMillis);
    }

    /** The clock is injectable so tests can move time forward without sleeping. */
    public SessionService(long ttlMillis, LongSupplier clock) {
        this.ttlMillis = ttlMillis;
        this.clock = clock;
    }

    /** Creates a new token for this account. */
    public String issue(int accountId) {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        sessions.put(token, new Session(accountId, clock.getAsLong() + ttlMillis));
        return token;
    }

    /** Returns the account id the token belongs to, or null if the token is unknown or expired. */
    public Integer resolve(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        Session session = sessions.get(token);
        if (session == null) {
            return null;
        }
        if (clock.getAsLong() >= session.expiresAtMillis) {
            sessions.remove(token);
            return null;
        }
        return session.accountId;
    }

    /** Reads the token out of an "Authorization: Bearer <token>" header and resolves it. */
    public Integer resolveAuthorizationHeader(String headerValue) {
        return resolve(extractBearerToken(headerValue));
    }

    /** Ends a session. Revoking an unknown token is not an error (idempotent). */
    public void revoke(String token) {
        if (token != null) {
            sessions.remove(token);
        }
    }

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

    private static final class Session {
        final int accountId;
        final long expiresAtMillis;

        Session(int accountId, long expiresAtMillis) {
            this.accountId = accountId;
            this.expiresAtMillis = expiresAtMillis;
        }
    }
}
