package net.onetechly.brief.repository;

import net.onetechly.brief.entity.AccountToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/** Data access for one-time links (2.4). The two finders are derived queries; {@link #claim} is written out (2.7). */
public interface AccountTokenRepository extends JpaRepository<AccountToken, Integer> {

    /** The link with this hash. The look-up uses the unique index on token_hash. */
    Optional<AccountToken> findByTokenHash(String tokenHash);

    /** Every link of one account for one purpose, used or not. Uses the index (account_id, purpose). */
    List<AccountToken> findByAccountIdAndPurpose(Integer accountId, String purpose);

    /**
     * Uses a link up in one statement that checks and changes the row together (2.7), as
     * InvitationRepository.claim does for invitations since the review of 2.5.0. Returns 1 if
     * this call used it, 0 if it was used a moment earlier or has expired. Two clicks on the same
     * password link at the same instant can no longer both set a password.
     */
    @Modifying
    @Query("update AccountToken t set t.usedAt = :now where t.tokenId = :id and t.usedAt is null and t.expiresAt > :now")
    int claim(@Param("id") Integer tokenId, @Param("now") Long now);
}
