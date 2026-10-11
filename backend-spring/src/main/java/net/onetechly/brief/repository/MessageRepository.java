package net.onetechly.brief.repository;

import net.onetechly.brief.entity.Message;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Data access for messages. Spring Data generates the implementation from the method names:
 * findByPostedByOrderByMessageIdAsc becomes
 *   select ... from message where posted_by = ? order by message_id asc
 */
public interface MessageRepository extends JpaRepository<Message, Integer> {

    List<Message> findAllByOrderByMessageIdAsc();

    List<Message> findByPostedByOrderByMessageIdAsc(Integer postedBy);

    // ---- Paging: newest first, a limited number of rows ----
    //
    // A Pageable parameter tells Spring Data how many rows to fetch; it adds the LIMIT to the SQL.
    // "MessageIdLessThan" becomes  where message_id < ?  - the "before" cursor: start below this id.

    List<Message> findAllByOrderByMessageIdDesc(Pageable pageable);

    List<Message> findByMessageIdLessThanOrderByMessageIdDesc(Integer messageId, Pageable pageable);

    List<Message> findByPostedByOrderByMessageIdDesc(Integer postedBy, Pageable pageable);

    List<Message> findByPostedByAndMessageIdLessThanOrderByMessageIdDesc(Integer postedBy, Integer messageId, Pageable pageable);
}
