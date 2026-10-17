package net.onetechly.brief.repository;

import net.onetechly.brief.entity.Invitation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * Data access for invitations (2.5). Most methods are derived queries: Spring builds the SQL from
 * the name. {@link #claim} is written out, because it must be one single UPDATE (see there).
 */
public interface InvitationRepository extends JpaRepository<Invitation, Integer> {

    /** The invitation with this hash. Uses the unique index on token_hash. */
    Optional<Invitation> findByTokenHash(String tokenHash);

    /** Everything one member sent, newest first. Uses the index (inviter_id, invitation_id). */
    List<Invitation> findByInviterIdOrderByInvitationIdDesc(Integer inviterId);

    /** The invitations to one address that have not been used yet. A new one replaces them. */
    List<Invitation> findByEmailAndUsedAtIsNull(String email);

    /** How many invitations one member created since a moment: the daily limit. */
    long countByInviterIdAndCreatedAtGreaterThan(Integer inviterId, Long since);

    /**
     * Uses an invitation up, in one statement that checks and changes the row together:
     *
     *     update invitation set used_at = ?, used_by = ?
     *      where invitation_id = ? and used_at is null and expires_at > ?
     *
     * Returns 1 if this call used it, 0 if it was already used, withdrawn or expired.
     *
     * Why not "read the row, check it, save it"? Two registrations with the same link can run at
     * the same moment. Both would read "unused", both would save, and two accounts would join with
     * one invitation (found by the independent review of 2.5.0). With this UPDATE the database
     * decides: PostgreSQL makes the second one wait for the first, then checks "used_at is null"
     * again on the row as the first left it, and changes nothing.
     *
     * {@code @Modifying}: Spring Data runs a statement that changes rows only when told so. It needs
     * a transaction, which AccountService.register provides.
     */
    @Modifying
    @Query("update Invitation i set i.usedAt = :now, i.usedBy = :accountId"
            + " where i.invitationId = :id and i.usedAt is null and i.expiresAt > :now")
    int claim(@Param("id") Integer invitationId, @Param("accountId") Integer accountId, @Param("now") Long now);

    /**
     * Deletes the invitations an account was created with: called when that account is deleted,
     * so the address the invitation was mailed to goes too (the Privacy notice promises it).
     */
    void deleteByUsedBy(Integer accountId);

    /** Deletes the unused invitations that expired before this moment: they hold addresses of people who never joined. */
    void deleteByUsedAtIsNullAndExpiresAtLessThan(Long before);
}
