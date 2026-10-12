package net.onetechly.brief.repository;

import net.onetechly.brief.entity.AccountToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** Data access for one-time links (2.4). Both methods are derived queries: Spring builds the SQL from the name. */
public interface AccountTokenRepository extends JpaRepository<AccountToken, Integer> {

    /** The link with this hash. The look-up uses the unique index on token_hash. */
    Optional<AccountToken> findByTokenHash(String tokenHash);

    /** Every link of one account for one purpose, used or not. Uses the index (account_id, purpose). */
    List<AccountToken> findByAccountIdAndPurpose(Integer accountId, String purpose);
}
