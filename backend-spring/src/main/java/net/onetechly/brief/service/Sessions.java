package net.onetechly.brief.service;

import net.onetechly.brief.entity.Account;
import net.onetechly.brief.entity.AccountSession;
import net.onetechly.brief.exception.UnauthorizedException;
import net.onetechly.brief.repository.AccountRepository;
import net.onetechly.brief.repository.AccountSessionRepository;
import net.onetechly.brief.security.DeviceLabel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Sign-in sessions, kept in the database (2.8).
 *
 * Until 2.7 a login token was valid for 12 hours from signing in, whatever the member chose, and
 * signing out put the token on a list kept in memory. Render restarts the service from time to
 * time, and a restart forgot that list: a token that had been signed out worked again until its
 * 12 hours were up.
 *
 * Now every sign-in is a row in account_session, and the token carries the row id. A token is
 * accepted only while its row exists and has not expired (JwtAuthenticationFilter), so:
 *
 *   signing out              deletes the row: permanent, on every server, after any restart
 *   "Stay signed in" ticked  the session lasts 30 days from the last use, renewed as it is used,
 *                            for at most a year from signing in; a member who opens Brief!
 *                            regularly does not have to sign in again
 *   not ticked               12 hours from signing in, as before (brief.jwt.ttl-minutes)
 *   a new password           deletes every session of the account (AccountEmailService.resetPassword)
 *   "Sign out everywhere     deletes every session of the account but the one asking
 *    else"
 *   deleting the account     deletes its sessions (on delete cascade, V6)
 *
 * Deleting a session also deletes the push subscription it turned on (on delete cascade, V6): a
 * device that was signed out receives no more notifications.
 *
 * The cost: one look-up by primary key per request that carries a token (the filter already
 * looked up the account row; this is a second one), and for "stay" sessions one write an hour.
 *
 * Every method that writes is @Transactional: Spring Data runs a derived delete or a @Modifying
 * query only inside a transaction.
 */
@Service
public class Sessions {

    /** "Stay signed in": how long a session lasts after its last use. */
    public static final long STAY_SECONDS = 30L * 24 * 60 * 60;
    /** "Stay signed in": no session lasts longer than this from signing in, however often it is used. */
    public static final long MAX_AGE_SECONDS = 365L * 24 * 60 * 60;
    /** A session's last use is written at most this often. */
    static final long TOUCH_INTERVAL_SECONDS = 60L * 60;
    /** Sessions beyond this many per account (the oldest) are ended when a new one starts. */
    public static final int MAX_PER_ACCOUNT = 20;
    /** Ended sessions are deleted at most this often. */
    static final long PURGE_INTERVAL_SECONDS = 60L * 60;

    private final AccountSessionRepository sessions;
    private final AccountRepository accounts;
    private final long shortSeconds;
    private final LongSupplier clockSeconds;
    private final AtomicLong lastPurge = new AtomicLong(Long.MIN_VALUE / 2);

    /** The constructor Spring uses. A session without "stay" lasts as long as a login did before 2.8. */
    @Autowired
    public Sessions(AccountSessionRepository sessions, AccountRepository accounts,
                    @Value("${brief.jwt.ttl-minutes:720}") long ttlMinutes) {
        this(sessions, accounts, ttlMinutes * 60, () -> System.currentTimeMillis() / 1000);
    }

    /** The clock is injectable so tests can move time forward without sleeping. */
    public Sessions(AccountSessionRepository sessions, AccountRepository accounts, long shortSeconds,
                    LongSupplier clockSeconds) {
        if (shortSeconds <= 0) {
            throw new IllegalStateException("The session lifetime must be positive");
        }
        this.sessions = sessions;
        this.accounts = accounts;
        this.shortSeconds = shortSeconds;
        this.clockSeconds = clockSeconds;
    }

    /** Starts a session for a member who just proved who they are (sign-in). */
    @Transactional
    public AccountSession open(int accountId, boolean stay, String userAgent) {
        purgeEnded();
        long now = clockSeconds.getAsLong();
        AccountSession session = sessions.save(new AccountSession(accountId, stay, DeviceLabel.of(userAgent),
                now, now + (stay ? STAY_SECONDS : shortSeconds)));
        // A script that signs in in a loop must not fill the table: keep the newest MAX_PER_ACCOUNT.
        List<AccountSession> all = sessions.findByAccountIdOrderBySessionIdDesc(accountId);
        if (all.size() > MAX_PER_ACCOUNT) {
            sessions.deleteAll(all.subList(MAX_PER_ACCOUNT, all.size()));
        }
        return session;
    }

    /**
     * True if this session exists, belongs to this account, and has not ended. For a "stay"
     * session the end moves forward as it is used (at most one write an hour).
     */
    @Transactional
    public boolean isLive(int sessionId, int accountId) {
        Optional<AccountSession> found = sessions.findById(sessionId);
        long now = clockSeconds.getAsLong();
        if (found.isEmpty() || found.get().getAccountId() != accountId || !found.get().isLiveAt(now)) {
            return false;
        }
        AccountSession session = found.get();
        if (session.isStay() && session.getLastUsedAt() <= now - TOUCH_INTERVAL_SECONDS) {
            long newEnd = Math.min(now + STAY_SECONDS, session.getCreatedAt() + MAX_AGE_SECONDS);
            sessions.touch(sessionId, now, newEnd, now - TOUCH_INTERVAL_SECONDS + 1);
        }
        return true;
    }

    /** The second at which the login token of this session should stop being accepted at the latest. */
    public long tokenExpiry(AccountSession session) {
        return session.isStay() ? session.getCreatedAt() + MAX_AGE_SECONDS : session.getExpiresAt();
    }

    /** Ends one session (sign-out). Ending a session that is already gone is not an error. */
    @Transactional
    public void end(int sessionId) {
        sessions.findById(sessionId).ifPresent(sessions::delete);
    }

    /**
     * Ends one of this member's sessions, chosen in "Your account". Returns false if there is no
     * such session, or it is someone else's; the caller answers 404 in both cases, so the answer
     * does not tell which session ids exist.
     */
    @Transactional
    public boolean end(int sessionId, int accountId) {
        Optional<AccountSession> found = sessions.findById(sessionId);
        if (found.isEmpty() || found.get().getAccountId() != accountId) {
            return false;
        }
        sessions.delete(found.get());
        return true;
    }

    /** What "Sign out everywhere else" did: how many sessions ended, and the account's new token generation. */
    public record OthersEnded(int ended, int tokenVersion) {
    }

    /**
     * "Sign out everywhere else": ends every session of this member except the one asking, and
     * also every login token from before 2.8 (which belongs to no session), by moving the
     * account's token generation on. That makes the asking session's own token worthless too,
     * so the caller issues it a new one with the returned generation.
     */
    @Transactional
    public OthersEnded endOthersEverywhere(int accountId, int keepSessionId) {
        Account account = accounts.findById(accountId).orElseThrow(UnauthorizedException::new);
        int ended = endOthers(accountId, keepSessionId);
        account.endAllSessions();
        accounts.save(account);
        return new OthersEnded(ended, account.getTokenVersion());
    }

    /** Ends every session of this member except one. Returns how many ended. */
    @Transactional
    public int endOthers(int accountId, int keepSessionId) {
        List<AccountSession> others = new ArrayList<>();
        for (AccountSession session : sessions.findByAccountIdOrderBySessionIdDesc(accountId)) {
            if (session.getSessionId() != keepSessionId) {
                others.add(session);
            }
        }
        sessions.deleteAll(others);
        return others.size();
    }

    /** Ends every session of this member (a new password). */
    @Transactional
    public void endAll(int accountId) {
        sessions.deleteAll(sessions.findByAccountIdOrderBySessionIdDesc(accountId));
    }

    /** This member's sessions that have not ended, newest first. */
    @Transactional
    public List<AccountSession> list(int accountId) {
        long now = clockSeconds.getAsLong();
        List<AccountSession> live = new ArrayList<>();
        for (AccountSession session : sessions.findByAccountIdOrderBySessionIdDesc(accountId)) {
            if (session.isLiveAt(now)) {
                live.add(session);
            }
        }
        return live;
    }

    /** Deletes sessions that have ended, at most once an hour. */
    private void purgeEnded() {
        long now = clockSeconds.getAsLong();
        long last = lastPurge.get();
        if (now - last >= PURGE_INTERVAL_SECONDS && lastPurge.compareAndSet(last, now)) {
            sessions.deleteByExpiresAtLessThanEqual(now);
        }
    }
}
