package net.onetechly.brief.unit;

import net.onetechly.brief.dto.MessagePage;
import net.onetechly.brief.dto.MessageRequest;
import net.onetechly.brief.entity.Message;
import net.onetechly.brief.exception.BadRequestException;
import net.onetechly.brief.entity.Account;
import net.onetechly.brief.entity.Mention;
import net.onetechly.brief.repository.AccountRepository;
import net.onetechly.brief.repository.MentionRepository;
import net.onetechly.brief.repository.MessageRepository;
import net.onetechly.brief.service.MentionService;
import net.onetechly.brief.service.MessageService;
import net.onetechly.brief.service.MentionAlerts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageRequest;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests the message rules that need the database, with Mockito mocks in place of the repositories.
 * The text rules (blank, 255 characters) are Bean Validation's job and are covered by ContractTest.
 */
class MessageServiceTest {

    private MessageRepository messages;
    private AccountRepository accounts;
    private MentionRepository mentions;
    private MessageService service;

    @BeforeEach
    void setUp() {
        messages = mock(MessageRepository.class);
        accounts = mock(AccountRepository.class);
        mentions = mock(MentionRepository.class);
        // A real MentionService on top of the mocks: the tests below can then see what the
        // message service makes it write.
        service = new MessageService(messages, accounts, new MentionService(accounts, messages, mentions), MentionAlerts.NONE);
    }

    private static Message stored(int id, int postedBy, String text, long epoch) {
        Message message = new Message(postedBy, text, epoch);
        message.setMessageId(id);
        return message;
    }

    /** Makes the mock behave like the database on save: it hands back what it was given. */
    private void savingReturnsTheMessage() {
        when(messages.save(any(Message.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void createSavesTheMessageForARealAuthor() {
        when(accounts.existsById(1)).thenReturn(true);
        savingReturnsTheMessage();

        Message created = service.create(new MessageRequest(1, "hello", 1669947792L));

        assertEquals(1, created.getPostedBy());
        assertEquals("hello", created.getMessageText());
        assertEquals(1669947792L, created.getTimePostedEpoch());
        verify(messages).save(any(Message.class));
    }

    @Test
    void createRejectsUnknownOrMissingAuthor() {
        when(accounts.existsById(99)).thenReturn(false);

        assertThrows(BadRequestException.class, () -> service.create(new MessageRequest(99, "hello", 1L)));
        assertThrows(BadRequestException.class, () -> service.create(new MessageRequest(null, "hello", 1L)));
        verify(messages, never()).save(any(Message.class));
    }

    @Test
    void createFillsInTheTimeWhenTheClientSendsNone() {
        when(accounts.existsById(1)).thenReturn(true);
        savingReturnsTheMessage();
        long before = System.currentTimeMillis() / 1000;

        assertTrue(service.create(new MessageRequest(1, "no time", null)).getTimePostedEpoch() >= before);
        assertTrue(service.create(new MessageRequest(1, "zero time", 0L)).getTimePostedEpoch() >= before);
    }

    @Test
    void updateChangesOnlyTheText() {
        when(messages.findById(1)).thenReturn(Optional.of(stored(1, 1, "first", 1669947792L)));
        savingReturnsTheMessage();

        Message updated = service.updateText(1, "edited");

        assertEquals(1, updated.getMessageId());
        assertEquals(1, updated.getPostedBy());
        assertEquals("edited", updated.getMessageText());
        assertEquals(1669947792L, updated.getTimePostedEpoch());
    }

    @Test
    void updateOfUnknownMessageIsABadRequest() {
        when(messages.findById(42)).thenReturn(Optional.empty());

        assertThrows(BadRequestException.class, () -> service.updateText(42, "ok"));
        verify(messages, never()).save(any(Message.class));
    }

    @Test
    void deleteReturnsTheMessageOnceThenNothing() {
        Message first = stored(1, 1, "first", 1L);
        when(messages.findById(1)).thenReturn(Optional.of(first), Optional.empty());

        assertEquals(Optional.of(first), service.delete(1));
        assertEquals(Optional.empty(), service.delete(1)); // idempotent: the second call finds nothing, no error

        ArgumentCaptor<Message> deleted = ArgumentCaptor.forClass(Message.class);
        verify(messages).delete(deleted.capture());
        assertSame(first, deleted.getValue());
    }

    @Test
    void messagesByAuthorComeFromTheRepositoryQuery() {
        List<Message> mine = List.of(stored(1, 1, "mine", 1L), stored(3, 1, "mine again", 3L));
        when(messages.findByPostedByOrderByMessageIdAsc(1)).thenReturn(mine);

        assertEquals(mine, service.getByAuthor(1));
    }

    // ---- mentions are kept in step with the message ----

    private static Account member(int id, String username) {
        Account account = new Account(username, "x");
        account.setAccountId(id);
        return account;
    }

    /** The mention rows handed to saveAll, as "message->account" pairs. */
    @SuppressWarnings("unchecked")
    private List<String> savedMentions() {
        ArgumentCaptor<Iterable<Mention>> saved = ArgumentCaptor.forClass(Iterable.class);
        verify(mentions).saveAll(saved.capture());
        List<String> pairs = new java.util.ArrayList<>();
        for (Mention row : saved.getValue()) {
            pairs.add(row.getMessageId() + "->" + row.getAccountId());
        }
        return pairs;
    }

    @Test
    void creatingAMessageRecordsWhoItMentions() {
        when(accounts.existsById(1)).thenReturn(true);
        when(accounts.findByUsernameContainedIn("Hey Maya, are you around?")).thenReturn(List.of(member(2, "Maya")));
        when(messages.save(any(Message.class))).thenAnswer(invocation -> {
            Message message = invocation.getArgument(0);
            message.setMessageId(7); // what the database would do on insert
            return message;
        });

        service.create(new MessageRequest(1, "Hey Maya, are you around?", 1L));

        assertEquals(List.of("7->2"), savedMentions());
    }

    @Test
    void editingAMessageRecordsWhoTheNewTextMentions() {
        when(messages.findById(7)).thenReturn(Optional.of(stored(7, 1, "nothing yet", 1L)));
        when(accounts.findByUsernameContainedIn("now for Maya")).thenReturn(List.of(member(2, "Maya")));
        savingReturnsTheMessage();

        service.updateText(7, "now for Maya");

        assertEquals(List.of("7->2"), savedMentions());
    }

    @Test
    void deletingAMessageDeletesItsMentionRows() {
        Mention row = new Mention(7, 2);
        when(messages.findById(7)).thenReturn(Optional.of(stored(7, 1, "Hey Maya", 1L)));
        when(mentions.findByMessageId(7)).thenReturn(List.of(row));

        service.delete(7);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Iterable<Mention>> deleted = ArgumentCaptor.forClass(Iterable.class);
        verify(mentions).deleteAll(deleted.capture());
        assertEquals(List.of(row), deleted.getValue());
    }

    // ---- paging ----

    @Test
    void pageAsksForOneExtraRowAndReportsMoreWhenItComesBack() {
        // limit 2: the service asks the repository for 3 rows. All 3 come back, so there are older messages.
        List<Message> newestThree = List.of(stored(5, 1, "e", 5L), stored(4, 2, "d", 4L), stored(3, 1, "c", 3L));
        when(messages.findAllByOrderByMessageIdDesc(PageRequest.of(0, 3))).thenReturn(newestThree);

        MessagePage page = service.getPage(null, 2, null);

        assertEquals(newestThree.subList(0, 2), page.items());
        assertTrue(page.hasMore());
    }

    @Test
    void lastPageReportsNoMore() {
        List<Message> lastTwo = List.of(stored(2, 1, "b", 2L), stored(1, 1, "a", 1L));
        when(messages.findByMessageIdLessThanOrderByMessageIdDesc(3, PageRequest.of(0, 3))).thenReturn(lastTwo);

        MessagePage page = service.getPage(null, 2, 3);

        assertEquals(lastTwo, page.items());
        assertFalse(page.hasMore());
    }

    @Test
    void pageOfOneAuthorUsesTheAuthorQueries() {
        List<Message> mine = List.of(stored(5, 1, "e", 5L));
        when(messages.findByPostedByOrderByMessageIdDesc(1, PageRequest.of(0, 11))).thenReturn(mine);
        when(messages.findByPostedByAndMessageIdLessThanOrderByMessageIdDesc(1, 5, PageRequest.of(0, 11))).thenReturn(List.of());

        assertEquals(mine, service.getPage(1, 10, null).items());
        assertEquals(List.of(), service.getPage(1, 10, 5).items());
    }

    @Test
    void pageSizeDefaultsToTwentyWhenOnlyBeforeIsGiven() {
        when(messages.findByMessageIdLessThanOrderByMessageIdDesc(50, PageRequest.of(0, MessageService.DEFAULT_PAGE_SIZE + 1)))
                .thenReturn(List.of(stored(49, 1, "x", 1L)));

        assertEquals(1, service.getPage(null, null, 50).items().size());
    }

    @Test
    void pageRejectsLimitsAndCursorsOutOfRange() {
        assertThrows(BadRequestException.class, () -> service.getPage(null, 0, null));
        assertThrows(BadRequestException.class, () -> service.getPage(null, -5, null));
        assertThrows(BadRequestException.class, () -> service.getPage(null, MessageService.MAX_PAGE_SIZE + 1, null));
        assertThrows(BadRequestException.class, () -> service.getPage(null, 10, 0));
        assertEquals(List.of(), service.getPage(null, MessageService.MAX_PAGE_SIZE, null).items()); // the largest allowed size is fine
    }

    // ---- alerts for mentions (2.8) ----

    @Test
    void theMembersAPostNewlyMentionsAreAlertedOnCreateAndOnEdit() {
        MentionService index = mock(MentionService.class);
        java.util.List<String> alerts = new java.util.ArrayList<>();
        MessageService watched = new MessageService(messages, accounts, index,
                (message, ids) -> alerts.add(message.getMessageText() + " -> " + new java.util.TreeSet<>(ids)));
        when(accounts.existsById(1)).thenReturn(true);
        when(messages.findById(1)).thenReturn(Optional.of(stored(1, 1, "hi Gilberto", 1L)));
        savingReturnsTheMessage();
        when(index.index(any(Message.class))).thenReturn(new java.util.HashSet<>(java.util.Set.of(2)), new java.util.HashSet<>());

        watched.create(new MessageRequest(1, "hi Gilberto", 1L));
        watched.updateText(1, "hi Gilberto!");

        assertEquals(java.util.List.of("hi Gilberto -> [2]", "hi Gilberto! -> []"), alerts,
                "the edit names nobody new; PushNotifications sends nothing for an empty set");
    }
}
