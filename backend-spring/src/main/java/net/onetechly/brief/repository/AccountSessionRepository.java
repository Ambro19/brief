package net.onetechly.brief.repository;

import net.onetechly.brief.entity.AccountSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/** Data access for sign-in sessions (2.8). Callers run inside a transaction (service/Sessions). */
public interface AccountSessionRepository extends JpaRepository<AccountSession, Integer> {

    /** One member's sessions, newest first. Uses the index on account_id. */
    List<AccountSession> findByAccountIdOrderBySessionIdDesc(Integer accountId);

    /** Forgets every session that has ended (expired). Uses the index on expires_at. */
    void deleteByExpiresAtLessThanEqual(Long now);

    /**
     * Marks a "stay signed in" session as used now, moving its end forward. One statement, and
     * only when the last mark is older than {@code notAfter}: a member who makes a request every
     * few seconds costs one write an hour, not one per request.
     */
    @Modifying
    @Query("update AccountSession s set s.lastUsedAt = :now, s.expiresAt = :expiresAt "
            + "where s.sessionId = :id and s.lastUsedAt < :notAfter and s.expiresAt > :now")
    int touch(@Param("id") Integer sessionId, @Param("now") Long now, @Param("expiresAt") Long expiresAt,
              @Param("notAfter") Long notAfter);
}
