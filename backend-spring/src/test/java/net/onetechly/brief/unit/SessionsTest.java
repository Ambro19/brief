package net.onetechly.brief.unit;

import net.onetechly.brief.entity.Account;
import net.onetechly.brief.entity.AccountSession;
import net.onetechly.brief.repository.AccountRepository;
import net.onetechly.brief.service.Sessions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Sign-in sessions kept in the database (2.8), with the table played by a list and a clock the test moves. */
class SessionsTest {

    private static final long HOUR = 60L * 60;
    private static final long DAY = 24 * HOUR;

    private final AtomicLong now = new AtomicLong(1_800_000_000L);
    private final InMemorySessions table = new InMemorySessions();
    private final AccountRepository accounts = mock(AccountRepository.class);
    private final Sessions sessions = new Sessions(table.repository, accounts, 12 * HOUR, now::get);
    private final Account seven = new Account("seven", "hash");

    @BeforeEach
    void accountSeven() {
        seven.setAccountId(7);
        when(accounts.findById(7)).thenReturn(Optional.of(seven));
        when(accounts.save(any(Account.class))).thenAnswer(call -> call.getArgument(0));
    }

    @Test
    void withoutStayASessionLastsTwelveHoursFromSigningInHoweverItIsUsed() {
        AccountSession session = sessions.open(7, false, "Mozilla/5.0 (Windows NT 10.0) Chrome/130.0");
        assertEquals("Chrome on Windows", session.getDevice());
        assertEquals(now.get() + 12 * HOUR, session.getExpiresAt());
        assertEquals(session.getExpiresAt(), sessions.tokenExpiry(session));

        now.addAndGet(11 * HOUR);
        assertTrue(sessions.isLive(1, 7));
        now.addAndGet(HOUR);
        assertFalse(sessions.isLive(1, 7), "use does not extend it");
        assertEquals(0, table.touches);
    }

    @Test
    void withStayItLastsThirtyDaysFromTheLastUseWrittenAtMostOnceAnHour() {
        AccountSession session = sessions.open(7, true, null);
        assertEquals(now.get() + 30 * DAY, session.getExpiresAt());

        for (int i = 0; i < 59; i++) {           // a request a minute for the first hour
            now.addAndGet(60);
            assertTrue(sessions.isLive(1, 7));
        }
        assertEquals(0, table.touches, "not one write in the first hour");
        now.addAndGet(60);
        assertTrue(sessions.isLive(1, 7));
        assertEquals(1, table.touches, "one write after an hour");
        assertEquals(now.get() + 30 * DAY, table.byId(1).getExpiresAt(), "the end moved forward to 30 days from now");
    }

    @Test
    void aStaySessionEndsAfterThirtyDaysUnusedAndAfterAYearInAnyCase() {
        sessions.open(7, true, null);
        now.addAndGet(30 * DAY);
        assertFalse(sessions.isLive(1, 7), "30 days without use");

        AccountSession daily = sessions.open(7, true, null);
        assertEquals(now.get() + 365 * DAY, sessions.tokenExpiry(daily), "the token is cut off at a year at the latest");
        for (int day = 0; day < 364; day++) {
            now.addAndGet(DAY);
            assertTrue(sessions.isLive(daily.getSessionId(), 7), "day " + day);
        }
        now.addAndGet(DAY);
        assertFalse(sessions.isLive(daily.getSessionId(), 7), "used every day, it still ends a year after signing in");
    }

    @Test
    void aSessionCountsOnlyForItsOwnAccount() {
        sessions.open(7, true, null);
        assertTrue(sessions.isLive(1, 7));
        assertFalse(sessions.isLive(1, 8));
        assertFalse(sessions.isLive(2, 7), "no such session");
    }

    @Test
    void endingOneSessionLeavesTheOthersAndOnlyItsMemberMayEndIt() {
        sessions.open(7, true, null);
        sessions.open(7, false, null);
        sessions.open(8, true, null);

        assertFalse(sessions.end(3, 7), "member 7 cannot end member 8's session");
        assertTrue(sessions.isLive(3, 8));
        assertTrue(sessions.end(1, 7));
        assertFalse(sessions.isLive(1, 7));
        assertTrue(sessions.isLive(2, 7));
        assertFalse(sessions.end(1, 7), "already gone");
    }

    @Test
    void signingOutEverywhereElseKeepsTheAskingSessionAndEndsOldTokens() {
        sessions.open(7, true, null);
        sessions.open(7, true, null);
        sessions.open(7, false, null);
        sessions.open(8, true, null);
        int generation = seven.getTokenVersion();

        Sessions.OthersEnded result = sessions.endOthersEverywhere(7, 2);

        assertEquals(2, result.ended());
        assertEquals(generation + 1, result.tokenVersion(), "tokens from before 2.8, which have no session, end too");
        assertEquals(1, sessions.list(7).size());
        assertEquals(2, sessions.list(7).get(0).getSessionId());
        assertTrue(sessions.isLive(4, 8), "nobody else is touched");
    }

    @Test
    void aNewPasswordEndsEverySession() {
        sessions.open(7, true, null);
        sessions.open(7, false, null);
        sessions.endAll(7);
        assertTrue(sessions.list(7).isEmpty());
    }

    @Test
    void theListShowsOnlySessionsThatHaveNotEndedNewestFirst() {
        sessions.open(7, false, null);
        now.addAndGet(HOUR);
        sessions.open(7, true, null);
        now.addAndGet(12 * HOUR);
        assertEquals(1, sessions.list(7).size(), "the short one is over");
        assertEquals(2, sessions.list(7).get(0).getSessionId());
    }

    @Test
    void signingInOverAndOverKeepsOnlyTheNewestTwenty() {
        for (int i = 0; i < Sessions.MAX_PER_ACCOUNT + 5; i++) {
            sessions.open(7, true, null);
        }
        assertEquals(Sessions.MAX_PER_ACCOUNT, sessions.list(7).size());
        assertFalse(sessions.isLive(5, 7), "the oldest went");
        assertTrue(sessions.isLive(6, 7));
    }

    @Test
    void endedSessionsAreDeletedAtMostOnceAnHour() {
        sessions.open(7, false, null);       // session 1, ends 12 hours from now; this sign-in also purges
        now.addAndGet(11 * HOUR + 30 * 60);
        sessions.open(8, false, null);       // purges (more than an hour since the last): session 1 is still live
        assertNotNull(table.byId(1));
        now.addAndGet(31 * 60);              // session 1 has ended, 31 minutes after the last purge
        sessions.open(9, false, null);
        assertNotNull(table.byId(1), "within the hour after a purge, nothing is looked for");
        assertFalse(sessions.isLive(1, 7), "but an ended session does not count, purged or not");
        now.addAndGet(30 * 60);
        sessions.open(9, false, null);       // more than an hour after the last purge
        assertNull(table.byId(1), "now it is deleted");
    }
}
