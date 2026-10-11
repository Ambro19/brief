package net.onetechly.brief.repository;

import net.onetechly.brief.entity.Mention;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Data access for the mention table.
 *
 * findByAccountIdOrderByMessageIdAsc becomes
 *   select ... from mention where account_id = ? order by message_id asc
 * which the index idx_mention_account_message answers without reading the rest of the table.
 */
public interface MentionRepository extends JpaRepository<Mention, Integer> {

    /** Every mention of one member, oldest message first. */
    List<Mention> findByAccountIdOrderByMessageIdAsc(Integer accountId);

    /** Every member one message mentions. */
    List<Mention> findByMessageId(Integer messageId);

    // ---- Paging (2.3): newest first, a limited number of rows. Same pattern as MessageRepository. ----
    //
    // The index (account_id, message_id) holds one member's mentions in message order, so the
    // database reads only the rows of the page, however many mentions the member has.

    List<Mention> findByAccountIdOrderByMessageIdDesc(Integer accountId, Pageable pageable);

    List<Mention> findByAccountIdAndMessageIdLessThanOrderByMessageIdDesc(Integer accountId, Integer messageId, Pageable pageable);
}
