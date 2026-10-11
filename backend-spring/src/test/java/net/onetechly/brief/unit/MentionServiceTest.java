package net.onetechly.brief.unit;

import net.onetechly.brief.dto.MessagePage;
import net.onetechly.brief.entity.Account;
import net.onetechly.brief.entity.Mention;
import net.onetechly.brief.entity.Message;
import net.onetechly.brief.exception.BadRequestException;
import net.onetechly.brief.exception.NotFoundException;
import net.onetechly.brief.repository.AccountRepository;
import net.onetechly.brief.repository.MentionRepository;
import net.onetechly.brief.repository.MessageRepository;
import net.onetechly.brief.service.MentionService;
import net.onetechly.brief.service.PageLimits;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The mention rule, and the bookkeeping of the mention table.
 *
 * The three repositories are Mockito mocks, but the mention repository is given a memory:
 * its save, delete and find methods work on the list {@code table} below. The tests can
 * then check what ends up stored, the way one would check a real table.
 */
class MentionServiceTest {

    private AccountRepository accounts;
    private MessageRepository messages;
    private MentionRepository mentions;
    private MentionService service;

    /** Stands in for the account table. */
    private final List<Account> members = List.of(
            account(1, "TestUser1"), account(2, "TestUser2"), account(3, "TestUser10"));
    /** Stands in for the mention table. */
    private final List<Mention> table = new ArrayList<>();
    /** How many rows were ever inserted: lets a test prove that nothing was written twice. */
    private int inserts;
    /** Stands in for the message table, for the paging tests: message id -> message. */
    private final Map<Integer, Message> posts = new HashMap<>();
    /** The page size the service last asked the mention repository for. */
    private int lastPageSizeAsked;

    @BeforeEach
    void setUp() {
        accounts = mock(AccountRepository.class);
        messages = mock(MessageRepository.class);
        mentions = mock(MentionRepository.class);
        service = new MentionService(accounts, messages, mentions);

        when(accounts.existsById(1)).thenReturn(true);
        when(accounts.existsById(2)).thenReturn(true);
        when(accounts.existsById(99)).thenReturn(false);
        // Like the database query: every account whose username occurs in the text, whatever the letter case.
        when(accounts.findByUsernameContainedIn(anyString())).thenAnswer(call -> {
            String text = call.getArgument(0);
            List<Account> found = new ArrayList<>();
            for (Account member : members) {
                if (text.toLowerCase(Locale.ROOT).contains(member.getUsername().toLowerCase(Locale.ROOT))) {
                    found.add(member);
                }
            }
            return found;
        });

        when(mentions.findByMessageId(anyInt())).thenAnswer(call -> {
            Integer messageId = call.getArgument(0);
            List<Mention> rows = new ArrayList<>();
            for (Mention row : table) {
                if (row.getMessageId().equals(messageId)) {
                    rows.add(row);
                }
            }
            return rows;
        });
        when(mentions.findByAccountIdOrderByMessageIdAsc(anyInt())).thenAnswer(call -> {
            Integer accountId = call.getArgument(0);
            List<Mention> rows = new ArrayList<>();
            for (Mention row : table) {
                if (row.getAccountId().equals(accountId)) {
                    rows.add(row);
                }
            }
            return rows;
        });
        when(mentions.saveAll(any())).thenAnswer(call -> {
            Iterable<Mention> rows = call.getArgument(0);
            for (Mention row : rows) {
                table.add(row);
                inserts++;
            }
            return rows;
        });
        // The two paging queries (2.3): one member's rows, newest message first, cut off at the page size.
        when(mentions.findByAccountIdOrderByMessageIdDesc(anyInt(), any())).thenAnswer(call ->
                pageOfRows(call.getArgument(0), null, call.getArgument(1)));
        when(mentions.findByAccountIdAndMessageIdLessThanOrderByMessageIdDesc(anyInt(), anyInt(), any())).thenAnswer(call ->
                pageOfRows(call.getArgument(0), call.getArgument(1), call.getArgument(2)));
        // Like the database: the messages with these ids, in no particular order (here: reversed).
        when(messages.findAllById(any())).thenAnswer(call -> {
            Iterable<Integer> ids = call.getArgument(0);
            List<Message> found = new ArrayList<>();
            for (Integer id : ids) {
                if (posts.containsKey(id)) {
                    found.add(0, posts.get(id));
                }
            }
            return found;
        });
        // deleteAll returns nothing (void), and for a void method Mockito is told what to do this way round.
        doAnswer(call -> {
            Iterable<Mention> rows = call.getArgument(0);
            for (Mention row : rows) {
                table.remove(row);
            }
            return null;
        }).when(mentions).deleteAll(any());
    }

    /** What the paging queries of the real repository return: filtered, newest first, limited. */
    private List<Mention> pageOfRows(Integer accountId, Integer beforeMessageId, Pageable page) {
        lastPageSizeAsked = page.getPageSize();
        List<Mention> rows = new ArrayList<>();
        for (Mention row : table) {
            boolean older = beforeMessageId == null || row.getMessageId() < beforeMessageId;
            if (row.getAccountId().equals(accountId) && older) {
                rows.add(row);
            }
        }
        rows.sort(Comparator.comparing(Mention::getMessageId).reversed());
        return new ArrayList<>(rows.subList(0, Math.min(rows.size(), page.getPageSize())));
    }

    /** Saves a message the way MessageService does: into the message table, then indexed. */
    private Message post(int id, int postedBy, String text) {
        Message message = message(id, postedBy, text);
        posts.put(id, message);
        service.index(message);
        return message;
    }

    private static List<Integer> ids(MessagePage page) {
        List<Integer> ids = new ArrayList<>();
        for (Message message : page.items()) {
            ids.add(message.getMessageId());
        }
        return ids;
    }

    private static Account account(int id, String username) {
        Account account = new Account(username, "x");
        account.setAccountId(id);
        return account;
    }

    private static Message message(int id, int postedBy, String text) {
        Message message = new Message(postedBy, text, 100L + id);
        message.setMessageId(id);
        return message;
    }

    /** The mention table as readable text, for example "5->1 5->2" (message 5 mentions accounts 1 and 2). */
    private String stored() {
        List<String> pairs = new ArrayList<>();
        for (Mention row : table) {
            pairs.add(row.getMessageId() + "->" + row.getAccountId());
        }
        pairs.sort(null);
        return String.join(" ", pairs);
    }

    // ---- the matching rule ----

    @Test
    void nameAsAWholeWordIsAMention() {
        assertTrue(MentionService.mentions("Hey TestUser1, are you there?", "TestUser1"));
        assertTrue(MentionService.mentions("TestUser1", "TestUser1"));
        assertTrue(MentionService.mentions("thanks TestUser1", "TestUser1"));
        assertTrue(MentionService.mentions("(TestUser1) see above", "TestUser1"));
    }

    @Test
    void letterCaseDoesNotMatter() {
        assertTrue(MentionService.mentions("hey testuser1!", "TestUser1"));
        assertTrue(MentionService.mentions("HEY TESTUSER1", "TestUser1"));
    }

    @Test
    void aLeadingAtSignIsAllowed() {
        assertTrue(MentionService.mentions("ping @TestUser1 please", "TestUser1"));
    }

    @Test
    void aLongerNameIsNotAMention() {
        assertFalse(MentionService.mentions("TestUser10 said hi", "TestUser1"));
        assertFalse(MentionService.mentions("myTestUser1 said hi", "TestUser1"));
        assertFalse(MentionService.mentions("TestUser1_backup", "TestUser1"));
    }

    @Test
    void aLaterWholeWordStillCountsAfterAPartialOne() {
        assertTrue(MentionService.mentions("TestUser10 and TestUser1 agreed", "TestUser1"));
    }

    @Test
    void emptyOrMissingInputIsNeverAMention() {
        assertFalse(MentionService.mentions(null, "TestUser1"));
        assertFalse(MentionService.mentions("hello", null));
        assertFalse(MentionService.mentions("hello", ""));
        assertFalse(MentionService.mentions("hello", "   "));
        assertFalse(MentionService.mentions("", "TestUser1"));
    }

    // ---- writing: what gets stored when a message is saved ----

    @Test
    void savingAMessageStoresOneRowForEachMemberItMentions() {
        service.index(message(5, 3, "TestUser1 and @testuser2: standup in 5"));
        assertEquals("5->1 5->2", stored());
    }

    @Test
    void aNameWrittenSeveralTimesIsStoredOnce() {
        service.index(message(5, 2, "TestUser1! testuser1? TESTUSER1."));
        assertEquals("5->1", stored());
    }

    @Test
    void writingYourOwnNameIsNotAMention() {
        service.index(message(5, 1, "TestUser1 here, signing off"));
        assertEquals("", stored());
    }

    @Test
    void theDatabaseFindsCandidatesAndTheWholeWordRuleDecides() {
        // "testuser10" contains "testuser1", so the query returns both accounts. Only TestUser10 is really named.
        service.index(message(5, 2, "TestUser10 said hi"));
        assertEquals("5->3", stored());
    }

    @Test
    void aMessageThatMentionsNobodyStoresNothing() {
        service.index(message(5, 2, "Nothing to do with anyone"));
        assertEquals("", stored());
        assertEquals(0, inserts);
    }

    @Test
    void editingAMessageAddsAndRemovesOnlyWhatChanged() {
        service.index(message(5, 4, "TestUser1 and TestUser2: standup in 5")); // written by member 4
        assertEquals(2, inserts);

        // The edit keeps TestUser2, drops TestUser1 and adds TestUser10.
        service.index(message(5, 4, "TestUser2 and TestUser10: moved to 3pm"));

        assertEquals("5->2 5->3", stored());
        assertEquals(3, inserts, "the row for TestUser2 was already right and must not be written again");
    }

    @Test
    void savingTheSameTextAgainChangesNothing() {
        service.index(message(5, 2, "ping TestUser1"));
        service.index(message(5, 2, "ping TestUser1"));
        assertEquals("5->1", stored());
        assertEquals(1, inserts);
    }

    @Test
    void removingAMessageForgetsItsMentionsAndNoOthers() {
        service.index(message(5, 2, "ping TestUser1"));
        service.index(message(6, 2, "TestUser1 again"));

        service.remove(5);

        assertEquals("6->1", stored());
    }

    @Test
    void rebuildingIndexesEveryExistingMessage() {
        when(messages.findAllByOrderByMessageIdAsc()).thenReturn(List.of(
                message(1, 2, "Hey TestUser1, game tonight?"),
                message(2, 2, "Nothing to do with anyone"),
                message(3, 1, "TestUser2: yes")));

        assertEquals(3, service.rebuildAll());
        assertEquals("1->1 3->2", stored());

        service.rebuildAll(); // safe to repeat
        assertEquals("1->1 3->2", stored());
        assertEquals(2, inserts);
    }

    // ---- reading ----

    @Test
    void readingReturnsTheMentioningMessagesOldestFirst() {
        Message first = message(1, 2, "Hey TestUser1, game tonight?");
        Message third = message(3, 2, "one more for testuser1");
        service.index(first);
        service.index(message(2, 2, "Nothing to do with anyone"));
        service.index(third);
        // The database may hand the rows back in any order; the service must sort them.
        when(messages.findAllById(List.of(1, 3))).thenReturn(List.of(third, first));

        assertEquals(List.of(first, third), service.getMentionsOf(1));
    }

    @Test
    void noMentionsIsAnEmptyListAndUnknownAccountIsNotFound() {
        assertTrue(service.getMentionsOf(2).isEmpty());
        assertThrows(NotFoundException.class, () -> service.getMentionsOf(99));
    }

    // ---- reading a page at a time (2.3) ----

    @Test
    void aPageOfMentionsIsNewestFirstAndFollowingTheCursorReachesTheEnd() {
        for (int id = 1; id <= 5; id++) {
            post(id, 2, "note " + id + " for TestUser1");
        }
        post(6, 2, "this one is for TestUser2 only");

        MessagePage newest = service.getMentionsPage(1, 2, null);
        assertEquals(List.of(5, 4), ids(newest));
        assertTrue(newest.hasMore());

        MessagePage middle = service.getMentionsPage(1, 2, 4); // older than the oldest one on screen
        assertEquals(List.of(3, 2), ids(middle));
        assertTrue(middle.hasMore());

        MessagePage last = service.getMentionsPage(1, 2, 2);
        assertEquals(List.of(1), ids(last));
        assertFalse(last.hasMore());
    }

    @Test
    void aPageThatHoldsExactlyTheRestSaysThereIsNoMore() {
        post(1, 2, "TestUser1 one");
        post(2, 2, "TestUser1 two");

        MessagePage page = service.getMentionsPage(1, 2, null);

        assertEquals(List.of(2, 1), ids(page));
        assertFalse(page.hasMore());
        assertEquals(3, lastPageSizeAsked, "the service asks for one row more than the page, to learn whether more exist");
    }

    @Test
    void withoutALimitTheDefaultPageSizeIsUsed() {
        post(1, 2, "TestUser1 one");

        service.getMentionsPage(1, null, null);

        assertEquals(PageLimits.DEFAULT_PAGE_SIZE + 1, lastPageSizeAsked);
    }

    @Test
    void aMemberNobodyMentionedGetsAnEmptyPage() {
        MessagePage page = service.getMentionsPage(2, 10, null);
        assertTrue(page.items().isEmpty());
        assertFalse(page.hasMore());
    }

    @Test
    void pagingValuesOutOfRangeAreABadRequestAndAnUnknownAccountIsNotFound() {
        assertThrows(BadRequestException.class, () -> service.getMentionsPage(1, 0, null));
        assertThrows(BadRequestException.class, () -> service.getMentionsPage(1, PageLimits.MAX_PAGE_SIZE + 1, null));
        assertThrows(BadRequestException.class, () -> service.getMentionsPage(1, 10, 0));
        assertThrows(NotFoundException.class, () -> service.getMentionsPage(99, 10, null));
        // A request that is wrong in both ways is reported as malformed (400), before the account is looked up.
        assertThrows(BadRequestException.class, () -> service.getMentionsPage(99, 0, null));
    }
}
