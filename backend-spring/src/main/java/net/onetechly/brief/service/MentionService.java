package net.onetechly.brief.service;

import net.onetechly.brief.dto.MessagePage;
import net.onetechly.brief.entity.Account;
import net.onetechly.brief.entity.Mention;
import net.onetechly.brief.entity.Message;
import net.onetechly.brief.exception.NotFoundException;
import net.onetechly.brief.repository.AccountRepository;
import net.onetechly.brief.repository.MentionRepository;
import net.onetechly.brief.repository.MessageRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Who is mentioned in which message.
 *
 * A message mentions "TestUser1" when that name appears in the text as a whole word,
 * in any letter case, with or without a leading @:
 *
 *   "Hey TestUser1, are you there?"   yes
 *   "hey @testuser1"                  yes
 *   "TestUser10 said hi"              no   (a different name)
 *   "myTestUser1"                     no   (part of a longer word)
 *
 * Messages a person wrote themselves never count as mentions of that person.
 *
 * How it scales (changed in 2.2). Until 2.1, every "who mentioned me?" request read every
 * message and searched its text. The web app asks that question every 15 seconds for every
 * signed-in member, so the cost grew with (members online) x (messages ever written).
 *
 * Now the work is done once, when a message is saved or edited: the members it mentions
 * are written to the mention table. Reading is then a look-up through an index. Its cost
 * depends on how often that one member was mentioned, not on how many messages exist.
 *
 * What it costs: a write does a little more work, and the answer is fixed at the moment of
 * writing. A name typed before that member registered does not become a mention later.
 *
 * The limit that remains: finding the mentioned members still asks the database to compare
 * the text with every username (see AccountRepository.findByUsernameContainedIn). That is
 * one query per post, and fine for many thousands of members. Beyond that, the usual step
 * is to count only names written with a leading @, which can be looked up by index.
 */
@Service
public class MentionService {

    private final AccountRepository accounts;
    private final MessageRepository messages;
    private final MentionRepository mentions;

    public MentionService(AccountRepository accounts, MessageRepository messages, MentionRepository mentions) {
        this.accounts = accounts;
        this.messages = messages;
        this.mentions = mentions;
    }

    // ------------------------------------------------------------------ reading

    /**
     * Messages by other people that mention this account, oldest first.
     * Throws NotFoundException (404) if the account does not exist.
     */
    public List<Message> getMentionsOf(int accountId) {
        if (!accounts.existsById(accountId)) {
            throw new NotFoundException();
        }
        List<Integer> messageIds = new ArrayList<>();
        for (Mention mention : mentions.findByAccountIdOrderByMessageIdAsc(accountId)) {
            messageIds.add(mention.getMessageId());
        }
        if (messageIds.isEmpty()) {
            return List.of();
        }
        // findAllById fetches all of them in one query, but promises no particular order.
        List<Message> found = new ArrayList<>(messages.findAllById(messageIds));
        found.sort(Comparator.comparing(Message::getMessageId));
        return found;
    }

    /**
     * One page of the messages that mention this account, newest first (2.3).
     *
     * The method above returns every mention a member ever received, and the web app asks
     * for them every 15 seconds. This one reads a fixed number of rows through the index,
     * however long the member's history is. It pages exactly like MessageService.getPage:
     * the cursor is the id of the oldest message already on screen.
     *
     * @param limit    how many to return, 1 to PageLimits.MAX_PAGE_SIZE; null means the default
     * @param beforeId return only mentions in messages with a smaller id; null starts from the newest
     * @throws net.onetechly.brief.exception.BadRequestException (400) if limit or beforeId is out of range
     * @throws NotFoundException (404) if the account does not exist
     */
    public MessagePage getMentionsPage(int accountId, Integer limit, Integer beforeId) {
        int size = PageLimits.sizeFor(limit, beforeId);
        if (!accounts.existsById(accountId)) {
            throw new NotFoundException();
        }
        // One row more than the page holds: if it comes back, there are older mentions.
        Pageable oneExtra = PageRequest.of(0, size + 1);
        List<Mention> rows = beforeId == null
                ? mentions.findByAccountIdOrderByMessageIdDesc(accountId, oneExtra)
                : mentions.findByAccountIdAndMessageIdLessThanOrderByMessageIdDesc(accountId, beforeId, oneExtra);
        boolean hasMore = rows.size() > size;

        List<Integer> messageIds = new ArrayList<>();
        for (Mention mention : hasMore ? rows.subList(0, size) : rows) {
            messageIds.add(mention.getMessageId());
        }
        if (messageIds.isEmpty()) {
            return new MessagePage(List.of(), false);
        }
        List<Message> found = new ArrayList<>(messages.findAllById(messageIds));
        found.sort(Comparator.comparing(Message::getMessageId).reversed()); // newest first
        return new MessagePage(found, hasMore);
    }

    // ------------------------------------------------------------------ writing

    /**
     * Brings the mention rows of one message in line with its text. Call it after a message
     * is created or edited.
     *
     * It changes only what differs: rows that are still right are left alone, rows that are
     * no longer right are deleted, missing rows are added. (Deleting everything and inserting
     * it again would also be slower, and can trip over the unique constraint: within one
     * transaction Hibernate sends inserts to the database before deletes.)
     */
    @Transactional
    public void index(Message message) {
        Set<Integer> wanted = mentionedAccountIds(message);

        List<Mention> stale = new ArrayList<>();
        for (Mention existing : mentions.findByMessageId(message.getMessageId())) {
            boolean stillWanted = wanted.remove(existing.getAccountId()); // also takes it off the "to add" list
            if (!stillWanted) {
                stale.add(existing);
            }
        }
        mentions.deleteAll(stale);

        List<Mention> missing = new ArrayList<>();
        for (Integer accountId : wanted) {
            missing.add(new Mention(message.getMessageId(), accountId));
        }
        mentions.saveAll(missing);
    }

    /** Forgets the mentions of a message. Call it when the message is deleted. */
    @Transactional
    public void remove(int messageId) {
        mentions.deleteAll(mentions.findByMessageId(messageId));
    }

    /**
     * Works out the mentions of every message again. Needed once for messages that were
     * written before the mention table existed; safe to repeat.
     *
     * @return how many messages were looked at
     */
    @Transactional
    public int rebuildAll() {
        List<Message> all = messages.findAllByOrderByMessageIdAsc();
        for (Message message : all) {
            index(message);
        }
        return all.size();
    }

    /** The members a message mentions: other people whose username is in the text as a whole word. */
    private Set<Integer> mentionedAccountIds(Message message) {
        Set<Integer> ids = new LinkedHashSet<>();
        String text = message.getMessageText();
        if (text == null || text.isBlank()) {
            return ids;
        }
        for (Account candidate : accounts.findByUsernameContainedIn(text)) {
            boolean someoneElse = !candidate.getAccountId().equals(message.getPostedBy());
            if (someoneElse && mentions(text, candidate.getUsername())) {
                ids.add(candidate.getAccountId());
            }
        }
        return ids;
    }

    // ------------------------------------------------------------------ the rule

    /** True if {@code text} contains {@code username} as a whole word, ignoring letter case. */
    public static boolean mentions(String text, String username) {
        if (text == null || username == null || username.isBlank()) {
            return false;
        }
        String haystack = text.toLowerCase(Locale.ROOT);
        String needle = username.toLowerCase(Locale.ROOT);

        int from = 0;
        while (true) {
            int start = haystack.indexOf(needle, from);
            if (start < 0) {
                return false;
            }
            int end = start + needle.length();
            boolean startsCleanly = start == 0 || !isNameCharacter(haystack.charAt(start - 1));
            boolean endsCleanly = end == haystack.length() || !isNameCharacter(haystack.charAt(end));
            if (startsCleanly && endsCleanly) {
                return true;
            }
            from = start + 1;
        }
    }

    /** Characters that can be part of a name: if one touches the match, it is a longer word, not a mention. */
    private static boolean isNameCharacter(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }
}
