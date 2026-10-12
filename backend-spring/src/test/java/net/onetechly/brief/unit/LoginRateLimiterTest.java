package net.onetechly.brief.unit;

import net.onetechly.brief.exception.TooManyRequestsException;
import net.onetechly.brief.security.LoginRateLimiter;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class LoginRateLimiterTest {

    private final AtomicLong now = new AtomicLong(10_000);
    /** 3 attempts allowed, then a 60 second lock-out. */
    private final LoginRateLimiter limiter = new LoginRateLimiter(true, 3, 60, now::get);
    private final String key = LoginRateLimiter.keyFor("10.0.0.1", "ambroise");

    /** Makes this many login attempts that never succeed. */
    private void attempt(String k, int times) {
        for (int i = 0; i < times; i++) {
            limiter.acquire(k);
        }
    }

    @Test
    void attemptsAreAllowedUpToTheLimitAndTheNextOneIsRefused() {
        attempt(key, 3); // none of these throws

        TooManyRequestsException refused = assertThrows(TooManyRequestsException.class, () -> limiter.acquire(key));
        assertEquals(60, refused.getRetryAfterSeconds());
        assertEquals(60, limiter.secondsUntilAllowed(key));
    }

    @Test
    void theLockOutEndsAfterItsTimeAndTheCountStartsAgain() {
        attempt(key, 3);

        now.addAndGet(59);
        assertEquals(1, limiter.secondsUntilAllowed(key));
        assertThrows(TooManyRequestsException.class, () -> limiter.acquire(key));

        now.addAndGet(1);
        assertEquals(0, limiter.secondsUntilAllowed(key));
        attempt(key, 3); // a full new set of attempts, not just one
        assertThrows(TooManyRequestsException.class, () -> limiter.acquire(key));
    }

    @Test
    void theLockOutLastsItsFullTimeFromTheAttemptThatReachedTheLimit() {
        attempt(key, 2);
        now.addAndGet(40);
        attempt(key, 1); // the third attempt, 40 seconds after the first
        assertEquals(60, limiter.secondsUntilAllowed(key));
    }

    @Test
    void refusedAttemptsDoNotExtendTheLockOut() {
        attempt(key, 3);
        now.addAndGet(30);
        assertThrows(TooManyRequestsException.class, () -> limiter.acquire(key));
        assertEquals(30, limiter.secondsUntilAllowed(key));
    }

    @Test
    void attemptsOlderThanTheWindowAreForgotten() {
        attempt(key, 2);
        now.addAndGet(60);
        attempt(key, 2); // a new window: these are attempts 1 and 2, not 3 and 4
        assertEquals(0, limiter.secondsUntilAllowed(key));
    }

    @Test
    void aSuccessfulLoginClearsTheCount() {
        attempt(key, 2);
        limiter.recordSuccess(key);
        attempt(key, 2);
        assertEquals(0, limiter.secondsUntilAllowed(key));
    }

    @Test
    void theAttemptThatReachesTheLimitMayStillSucceed() {
        attempt(key, 3);             // the third is allowed through to the password check...
        limiter.recordSuccess(key);  // ...and if the password was right, nothing stays locked
        assertEquals(0, limiter.secondsUntilAllowed(key));
        limiter.acquire(key);
    }

    @Test
    void countersAreSeparatePerAddressAndUsername() {
        attempt(key, 3);
        assertTrue(limiter.secondsUntilAllowed(key) > 0);
        assertEquals(0, limiter.secondsUntilAllowed(LoginRateLimiter.keyFor("10.0.0.2", "ambroise")), "another address");
        assertEquals(0, limiter.secondsUntilAllowed(LoginRateLimiter.keyFor("10.0.0.1", "john")), "another username");
    }

    @Test
    void usernamesAreCountedWithoutRegardToCaseOrSpaces() {
        assertEquals(key, LoginRateLimiter.keyFor("10.0.0.1", " Ambroise "));
        assertEquals("10.0.0.1|", LoginRateLimiter.keyFor("10.0.0.1", null));
    }

    @Test
    void aDisabledLimiterNeverRefuses() {
        LoginRateLimiter off = new LoginRateLimiter(false, 3, 60, now::get);
        for (int i = 0; i < 10; i++) {
            off.acquire(key);
        }
        assertEquals(0, off.secondsUntilAllowed(key));
    }

    @Test
    void attemptsArrivingAtTheSameInstantCannotAllSlipThrough() throws Exception {
        // 40 threads try at once. However they interleave, exactly 3 may pass.
        java.util.concurrent.atomic.AtomicInteger allowed = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        Thread[] threads = new Thread[40];
        for (int i = 0; i < threads.length; i++) {
            threads[i] = new Thread(() -> {
                try {
                    start.await();
                    limiter.acquire(key);
                    allowed.incrementAndGet();
                } catch (TooManyRequestsException | InterruptedException refused) {
                    // refused: not counted as allowed
                }
            });
            threads[i].start();
        }
        start.countDown();
        for (Thread thread : threads) {
            thread.join();
        }
        assertEquals(3, allowed.get());
    }

    @Test
    void resetForgetsEverything() {
        attempt(key, 3);
        limiter.reset();
        assertEquals(0, limiter.secondsUntilAllowed(key));
    }

    // ---- what counts as the same visitor, and how much is remembered (2.3) ----

    @Test
    void ipv6AddressesFromTheSameBlockShareOneCount() {
        // One line has a whole /64 block of addresses. A new address for every guess must not help.
        for (int n = 1; n <= 3; n++) { // the limit in this test class is three attempts
            limiter.acquire(LoginRateLimiter.keyFor("2001:db8:12:34::" + n, "ambroise"));
        }
        assertThrows(TooManyRequestsException.class,
                () -> limiter.acquire(LoginRateLimiter.keyFor("2001:db8:12:34:ffff:ffff:ffff:ffff", "ambroise")));
        // A different block is a different visitor.
        limiter.acquire(LoginRateLimiter.keyFor("2001:db8:12:35::1", "ambroise"));
    }

    @Test
    void onlyTheFirstPartOfAVeryLongUsernameGoesIntoTheKey() {
        String longName = "x".repeat(5000);
        String keyOfLongName = LoginRateLimiter.keyFor("10.0.0.1", longName);
        assertTrue(keyOfLongName.length() < 100, "a key must stay short however long the name is");
        assertEquals(keyOfLongName, LoginRateLimiter.keyFor("10.0.0.1", "x".repeat(6000)));
        assertNotEquals(keyOfLongName, LoginRateLimiter.keyFor("10.0.0.1", "y".repeat(5000)));
    }

    @Test
    void aFloodOfNewNamesCannotMakeTheLimiterRememberWithoutBound() {
        // Every attempt uses a new name, so none of the entries is ever finished and the sweep removes nothing.
        for (int i = 0; i < 60_000; i++) {
            limiter.acquire(LoginRateLimiter.keyFor("10.0.0.9", "name" + i));
        }
        assertTrue(limiter.trackedKeys() <= 50_001, "tracked: " + limiter.trackedKeys());
        // And it still works afterwards.
        limiter.reset();
        attempt(key, 3);
        assertThrows(TooManyRequestsException.class, () -> limiter.acquire(key));
    }

    // ---- the clean-up (2.4) ----

    @Test
    void theCleanUpRunsAtMostOncePerIntervalHoweverManyAttemptsArrive() {
        // In 2.3 every attempt above 10,000 remembered keys looked at all of them. 40,000 more
        // attempts meant hundreds of millions of looks, and this loop took many seconds.
        for (int i = 0; i < 50_000; i++) {
            limiter.acquire(LoginRateLimiter.keyFor("10.0.0.9", "name" + i));
        }
        assertEquals(1, limiter.sweeps(), "40,000 attempts above the threshold, within one minute: one clean-up");

        now.addAndGet(59);
        limiter.acquire(LoginRateLimiter.keyFor("10.0.0.9", "one more"));
        assertEquals(1, limiter.sweeps(), "still inside the interval");

        now.addAndGet(1);
        limiter.acquire(LoginRateLimiter.keyFor("10.0.0.9", "and another"));
        assertEquals(2, limiter.sweeps(), "a minute has passed: the next attempt cleans up");
        assertEquals(2, limiter.trackedKeys(), "it removed the 50,000 attempts whose minute is over; the last two remain");
    }

    @Test
    void countersForOtherActionsCannotCollideWithLoginCounters() {
        String forgot = LoginRateLimiter.keyForAction("forgot", "10.0.0.1");
        assertEquals("#forgot|10.0.0.1", forgot);
        // Whatever someone types as a username, a login key starts with the address. An address
        // never starts with "#": ClientAddress lets only digits, a-f, dots and colons through.
        assertNotEquals(forgot, LoginRateLimiter.keyFor("10.0.0.1", "forgot"));
        assertNotEquals(forgot, LoginRateLimiter.keyFor("10.0.0.1", "#forgot|10.0.0.1"));
        // one block of IPv6 addresses is one visitor here too
        assertEquals(LoginRateLimiter.keyForAction("invite", "2001:db8:1:2::1"),
                LoginRateLimiter.keyForAction("invite", "2001:db8:1:2:ffff::9"));
    }
}
