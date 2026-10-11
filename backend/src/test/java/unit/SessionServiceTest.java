package unit;

import Service.SessionService;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.*;

public class SessionServiceTest {

    @Test
    public void issuedTokenResolvesToItsAccount() {
        SessionService sessions = new SessionService();
        String token = sessions.issue(7);
        assertEquals(Integer.valueOf(7), sessions.resolve(token));
        assertEquals(Integer.valueOf(7), sessions.resolveAuthorizationHeader("Bearer " + token));
    }

    @Test
    public void tokensAreUniqueAndLong() {
        SessionService sessions = new SessionService();
        String a = sessions.issue(1);
        String b = sessions.issue(1);
        assertNotEquals(a, b);
        assertTrue(a.length() >= 40);
    }

    @Test
    public void unknownOrMissingTokenResolvesToNull() {
        SessionService sessions = new SessionService();
        assertNull(sessions.resolve("not-a-token"));
        assertNull(sessions.resolve(null));
        assertNull(sessions.resolveAuthorizationHeader(null));
        assertNull(sessions.resolveAuthorizationHeader("Basic abc"));
    }

    @Test
    public void tokenExpiresAfterItsLifetime() {
        AtomicLong now = new AtomicLong(1_000);
        SessionService sessions = new SessionService(500, now::get);
        String token = sessions.issue(3);

        now.set(1_499);
        assertEquals(Integer.valueOf(3), sessions.resolve(token));

        now.set(1_500);
        assertNull(sessions.resolve(token));
    }

    @Test
    public void revokeEndsTheSessionAndIsSafeToRepeat() {
        SessionService sessions = new SessionService();
        String token = sessions.issue(9);
        sessions.revoke(token);
        sessions.revoke(token);
        sessions.revoke(null);
        assertNull(sessions.resolve(token));
    }

    @Test
    public void bearerTokenIsExtractedFromTheHeader() {
        assertEquals("abc", SessionService.extractBearerToken("Bearer abc"));
        assertEquals("abc", SessionService.extractBearerToken("  bearer   abc  "));
        assertNull(SessionService.extractBearerToken("Bearer "));
        assertNull(SessionService.extractBearerToken("abc"));
    }
}
