package net.onetechly.brief.security;

import net.onetechly.brief.exception.TooManyRequestsException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Slows down password guessing.
 *
 * Every login attempt is counted BEFORE the password is checked. Once the limit is reached
 * for one username from one address, further attempts are refused with 429 (Too Many
 * Requests) for the lock-out time, even if the password is then right. A successful login
 * clears the count.
 *
 * Why count before checking: if the count only went up after a wrong password was found,
 * fifty guesses sent at the same instant would all pass the "is it locked?" question before
 * any of them had been counted. Counting first closes that gap.
 *
 * Counting per (address + username) means a stranger hammering on your name locks out
 * only their own address, not you. "Address" means the group AddressGroup puts it in: an
 * IPv4 address by itself, an IPv6 address together with the rest of its /64 block (2.3).
 *
 * Limits of this version, stated plainly:
 *   - The counts live in this server's memory. They are lost on restart and are not shared
 *     between several servers. A system on more than one machine keeps such counters in a
 *     shared store such as Redis, or applies the limit at the gateway in front of it.
 *   - "The address" is whatever the caller of keyFor passes in. AccountController asks
 *     ClientAddress for it, which knows whether to use the connection's address or the header
 *     a hosting platform's proxy sets (2.3).
 *
 * Only active in strict mode.
 */
@Component
public class LoginRateLimiter {

    /** Above this many tracked keys, finished entries are swept out so the map cannot grow without bound. */
    private static final int SWEEP_THRESHOLD = 10_000;

    /**
     * A ceiling that holds even if the sweep finds nothing to remove, which is the case when
     * someone sends a flood of attempts under ever-new names: all of those entries are still
     * running. Beyond this many, everything is forgotten and counting starts again. A flood
     * that large buys the sender five more guesses; without the ceiling it would buy all of
     * the server's memory.
     */
    static final int HARD_LIMIT = 50_000;

    /** No more than this much of a username goes into a key, so one attempt cannot store a very long text. */
    static final int NAME_LENGTH_IN_KEY = 64;

    private final Map<String, Attempts> attemptsByKey = new ConcurrentHashMap<>();
    private final boolean enabled;
    private final int maxAttempts;
    private final long lockoutSeconds;
    private final LongSupplier clockSeconds;

    /** The constructor Spring uses. The values come from application.properties. */
    @Autowired
    public LoginRateLimiter(AuthMode mode,
                            @Value("${brief.login.max-failures:5}") int maxFailures,
                            @Value("${brief.login.lockout-minutes:15}") long lockoutMinutes) {
        this(mode.isStrict(), maxFailures, lockoutMinutes * 60, () -> System.currentTimeMillis() / 1000);
    }

    /** The clock is injectable so tests can move time forward without sleeping. */
    public LoginRateLimiter(boolean enabled, int maxAttempts, long lockoutSeconds, LongSupplier clockSeconds) {
        this.enabled = enabled && maxAttempts > 0 && lockoutSeconds > 0;
        this.maxAttempts = maxAttempts;
        this.lockoutSeconds = lockoutSeconds;
        this.clockSeconds = clockSeconds;
    }

    /**
     * One counter per address group and username. Usernames are compared without regard to
     * case, exactly as Account.keyOf compares them, and only their first 64 characters count.
     */
    public static String keyFor(String remoteAddress, String username) {
        String name = username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
        if (name.length() > NAME_LENGTH_IN_KEY) {
            name = name.substring(0, NAME_LENGTH_IN_KEY);
        }
        return AddressGroup.of(remoteAddress) + "|" + name;
    }

    /**
     * Call this before checking a password. It counts the attempt, or throws
     * TooManyRequestsException (429) if this key is locked out.
     *
     * The attempt that reaches the limit is itself still allowed; it starts the lock-out for
     * the ones after it. So with a limit of 5: five tries, and the sixth is refused.
     */
    public void acquire(String key) {
        if (!enabled) {
            return;
        }
        long now = clockSeconds.getAsLong();
        if (attemptsByKey.size() > SWEEP_THRESHOLD) {
            attemptsByKey.values().removeIf(attempts -> attempts.isOver(now, lockoutSeconds));
            if (attemptsByKey.size() > HARD_LIMIT) {
                attemptsByKey.clear();
            }
        }
        long[] wait = {0}; // a one-element array, because a lambda may not assign to a local variable
        // compute runs atomically per key: "is it locked?" and "count it" happen as one step,
        // so attempts arriving at the same instant cannot all slip past the question.
        attemptsByKey.compute(key, (k, current) -> {
            if (current != null && current.lockedUntil() > now) {
                wait[0] = current.lockedUntil() - now;
                return current; // locked: refuse, and do not let refused attempts extend the lock
            }
            int count = current == null || current.isOver(now, lockoutSeconds) ? 1 : current.count() + 1;
            long windowStart = count == 1 ? now : current.windowStart();
            long lockedUntil = count >= maxAttempts ? now + lockoutSeconds : 0;
            return new Attempts(count, windowStart, lockedUntil);
        });
        if (wait[0] > 0) {
            throw new TooManyRequestsException(wait[0]);
        }
    }

    /** The password was right: forget the earlier attempts for this key. */
    public void recordSuccess(String key) {
        attemptsByKey.remove(key);
    }

    /** 0 if a login may be attempted now, otherwise how many seconds remain. Does not count as an attempt. */
    public long secondsUntilAllowed(String key) {
        Attempts attempts = attemptsByKey.get(key);
        return attempts == null ? 0 : Math.max(0, attempts.lockedUntil() - clockSeconds.getAsLong());
    }

    /** Forgets everything. Used by the tests between cases. */
    public void reset() {
        attemptsByKey.clear();
    }

    /** How many keys are being tracked right now. Used by the tests. */
    public int trackedKeys() {
        return attemptsByKey.size();
    }

    /**
     * The attempts for one key: how many, counted from when, and locked until when (0 = not locked).
     * Immutable: each change replaces the object with a new one.
     */
    private record Attempts(int count, long windowStart, long lockedUntil) {

        /** True once neither the counting window nor a lock-out is still running. */
        boolean isOver(long now, long windowSeconds) {
            return now >= windowStart + windowSeconds && now >= lockedUntil;
        }
    }
}
