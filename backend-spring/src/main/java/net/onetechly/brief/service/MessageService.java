package net.onetechly.brief.service;

import net.onetechly.brief.dto.MessagePage;
import net.onetechly.brief.dto.MessageRequest;
import net.onetechly.brief.entity.Message;
import net.onetechly.brief.exception.BadRequestException;
import net.onetechly.brief.repository.AccountRepository;
import net.onetechly.brief.repository.MessageRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Business rules for messages.
 *
 * The text rules (not blank, at most 255 characters) are enforced by Bean Validation on the
 * request records, so what remains here are the rules that need the database.
 *
 * The three methods that change data are {@code @Transactional}: each one writes the message
 * AND its mention rows. Either both are stored or neither is; a failure half-way leaves
 * nothing behind.
 */
@Service
public class MessageService {

    public static final int DEFAULT_PAGE_SIZE = PageLimits.DEFAULT_PAGE_SIZE;
    public static final int MAX_PAGE_SIZE = PageLimits.MAX_PAGE_SIZE;

    private final MessageRepository messages;
    private final AccountRepository accounts;
    private final MentionService mentionService;

    public MessageService(MessageRepository messages, AccountRepository accounts, MentionService mentionService) {
        this.messages = messages;
        this.accounts = accounts;
        this.mentionService = mentionService;
    }

    @Transactional
    public Message create(MessageRequest request) {
        if (request.postedBy() == null || !accounts.existsById(request.postedBy())) {
            throw new BadRequestException(); // the author must be a real account
        }
        Long time = request.timePostedEpoch();
        if (time == null || time <= 0) {
            time = System.currentTimeMillis() / 1000; // the client sent no time: use now, in epoch seconds
        }
        Message saved = messages.save(new Message(request.postedBy(), request.messageText(), time));
        mentionService.index(saved); // record who this message mentions, in the same transaction
        return saved;
    }

    public List<Message> getAll() {
        return messages.findAllByOrderByMessageIdAsc();
    }

    /** Optional: either the message, or "nothing there" - the caller must handle both. */
    public Optional<Message> findById(int messageId) {
        return messages.findById(messageId);
    }

    /**
     * Deletes a message and returns what was deleted, or an empty Optional if there was nothing
     * to delete. Calling it twice is safe (idempotent).
     */
    @Transactional
    public Optional<Message> delete(int messageId) {
        Optional<Message> existing = messages.findById(messageId);
        if (existing.isPresent()) {
            mentionService.remove(messageId);
            messages.delete(existing.get());
        }
        return existing;
    }

    /** Changes only the text. Throws BadRequestException (400) if the id does not exist. */
    @Transactional
    public Message updateText(int messageId, String newText) {
        Message message = messages.findById(messageId).orElseThrow(BadRequestException::new);
        message.setMessageText(newText);
        Message saved = messages.save(message);
        mentionService.index(saved); // the new text may mention different people
        return saved;
    }

    public List<Message> getByAuthor(int accountId) {
        return messages.findByPostedByOrderByMessageIdAsc(accountId);
    }

    /**
     * One page of messages, newest first.
     *
     * This is "cursor" (or "keyset") paging: instead of "give me page 3", the client says
     * "give me the next 20 below message id 140". New posts arriving at the top cannot shift
     * the pages, so nothing is shown twice or skipped while people are posting.
     *
     * @param authorId only this member's messages, or null for everyone's
     * @param limit    how many to return, 1 to MAX_PAGE_SIZE; null means DEFAULT_PAGE_SIZE
     * @param beforeId return only messages with a smaller id (the oldest one already on screen);
     *                 null starts from the newest
     * @throws BadRequestException (400) if limit or beforeId is out of range
     */
    public MessagePage getPage(Integer authorId, Integer limit, Integer beforeId) {
        int size = PageLimits.sizeFor(limit, beforeId);
        // Ask for one row more than the page holds: if it comes back, there are older messages.
        Pageable oneExtra = PageRequest.of(0, size + 1);
        List<Message> rows;
        if (authorId == null) {
            rows = beforeId == null
                    ? messages.findAllByOrderByMessageIdDesc(oneExtra)
                    : messages.findByMessageIdLessThanOrderByMessageIdDesc(beforeId, oneExtra);
        } else {
            rows = beforeId == null
                    ? messages.findByPostedByOrderByMessageIdDesc(authorId, oneExtra)
                    : messages.findByPostedByAndMessageIdLessThanOrderByMessageIdDesc(authorId, beforeId, oneExtra);
        }
        boolean hasMore = rows.size() > size;
        return new MessagePage(hasMore ? List.copyOf(rows.subList(0, size)) : rows, hasMore);
    }
}
