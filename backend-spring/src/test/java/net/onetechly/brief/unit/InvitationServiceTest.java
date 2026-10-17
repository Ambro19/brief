package net.onetechly.brief.unit;

import net.onetechly.brief.dto.InvitationLink;
import net.onetechly.brief.dto.InvitationView;
import net.onetechly.brief.entity.Account;
import net.onetechly.brief.entity.Invitation;
import net.onetechly.brief.exception.BadRequestException;
import net.onetechly.brief.exception.ForbiddenException;
import net.onetechly.brief.exception.NotFoundException;
import net.onetechly.brief.exception.TooManyRequestsException;
import net.onetechly.brief.mail.AccountMails;
import net.onetechly.brief.mail.LogMailer;
import net.onetechly.brief.mail.Mail;
import net.onetechly.brief.repository.AccountRepository;
import net.onetechly.brief.repository.InvitationRepository;
import net.onetechly.brief.security.BoardOwner;
import net.onetechly.brief.service.InvitationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Invitations (2.5), without Spring and without a database. The invitation table is played by
 * a list behind a Mockito mock, as InMemoryTokens does for the links of 2.4; the post office is
 * LogMailer, which keeps what it was asked to send.
 */
class InvitationServiceTest {

    private static final int OWNER = 1;
    private static final int MEMBER = 2;

    private final AtomicLong now = new AtomicLong(1_000_000);
    private final List<Invitation> rows = new ArrayList<>();
    private final LogMailer mailbox = new LogMailer();
    private InvitationRepository table;
    private AccountRepository accounts;
    private InvitationService service;

    @BeforeEach
    void setUp() throws Exception {
        table = mock(InvitationRepository.class);
        accounts = mock(AccountRepository.class);
        stored(OWNER, "AmbroLum", null);
        stored(MEMBER, "Gilberto", "gil@example.test");
        when(accounts.findByEmail(anyString())).thenReturn(Optional.empty());
        when(accounts.findByEmail("gil@example.test")).thenReturn(Optional.of(new Account("Gilberto", "h")));

        int[] nextId = {1};
        Field id = Invitation.class.getDeclaredField("invitationId");
        id.setAccessible(true);
        when(table.save(any(Invitation.class))).thenAnswer(call -> {
            Invitation row = call.getArgument(0);
            if (row.getInvitationId() == null) {
                id.set(row, nextId[0]++);
            }
            if (!rows.contains(row)) {
                rows.add(row);
            }
            return row;
        });
        when(table.findByTokenHash(anyString())).thenAnswer(call ->
                rows.stream().filter(row -> row.getTokenHash().equals(call.getArgument(0))).findFirst());
        when(table.findById(anyInt())).thenAnswer(call ->
                rows.stream().filter(row -> row.getInvitationId().equals(call.getArgument(0))).findFirst());
        when(table.findByEmailAndUsedAtIsNull(anyString())).thenAnswer(call ->
                rows.stream().filter(row -> call.getArgument(0).equals(row.getEmail()) && !row.isUsed()).toList());
        when(table.findByInviterIdOrderByInvitationIdDesc(anyInt())).thenAnswer(call -> {
            List<Invitation> mine = new ArrayList<>(rows.stream().filter(row -> row.getInviterId().equals(call.getArgument(0))).toList());
            mine.sort((a, b) -> b.getInvitationId() - a.getInvitationId());
            return mine;
        });
        when(table.countByInviterIdAndCreatedAtGreaterThan(anyInt(), anyLong())).thenAnswer(call ->
                rows.stream().filter(row -> row.getInviterId().equals(call.getArgument(0))
                        && row.getCreatedAt() > (Long) call.getArgument(1)).count());
        doAnswer(call -> {
            for (Invitation row : (Iterable<Invitation>) call.getArgument(0)) {
                rows.remove(row);
            }
            return null;
        }).when(table).deleteAll(ArgumentMatchers.<Iterable<Invitation>>any());
        doAnswer(call -> rows.remove((Invitation) call.getArgument(0))).when(table).delete(any(Invitation.class));
        // claim: the one UPDATE that checks and uses an invitation together (InvitationRepository.claim)
        when(table.claim(anyInt(), anyInt(), anyLong())).thenAnswer(call -> {
            for (Invitation row : rows) {
                if (row.getInvitationId().equals(call.getArgument(0)) && !row.isUsed()
                        && row.getExpiresAt() > (Long) call.getArgument(2)) {
                    row.markUsed(call.getArgument(2), call.getArgument(1));
                    return 1;
                }
            }
            return 0;
        });
        doAnswer(call -> rows.removeIf(row -> !row.isUsed() && row.getExpiresAt() < (Long) call.getArgument(0)))
                .when(table).deleteByUsedAtIsNullAndExpiresAtLessThan(anyLong());

        service = new InvitationService(table, accounts, new AccountMails(mailbox, "https://brief.example"),
                new BoardOwner("ambrolum"), 7, now::get);
    }

    private void stored(int id, String username, String email) {
        Account account = new Account(username, "hash");
        account.setAccountId(id);
        if (email != null) {
            account.setEmail(email);
        }
        when(accounts.findById(id)).thenReturn(Optional.of(account));
    }

    /** The secret of the newest invitation mailed to this address. */
    private String secretMailedTo(String address) {
        Mail mail = mailbox.lastTo(address);
        assertNotNull(mail, "a mail to " + address);
        int at = mail.text().indexOf("/?invite=");
        assertTrue(at >= 0, mail.text());
        int from = at + "/?invite=".length();
        return mail.text().substring(from, mail.text().indexOf('\n', from));
    }

    @Test
    void theOwnerMailsAnInvitationAndOnlyAHashIsStored() {
        InvitationView view = service.invite(OWNER, "  Maya@Example.TEST ");

        assertEquals("maya@example.test", view.email());
        assertEquals("pending", view.status());
        assertEquals(1_000_000L + 7 * 24 * 3600, view.expiresAt());
        Mail mail = mailbox.lastTo("maya@example.test");
        assertEquals("AmbroLum invited you to Brief!", mail.subject());
        assertTrue(mail.text().contains("for 7 days"), mail.text());
        String secret = secretMailedTo("maya@example.test");
        assertEquals(43, secret.length());
        assertEquals(1, rows.size());
        assertNotEquals(secret, rows.get(0).getTokenHash());
        assertTrue(rows.get(0).getTokenHash().matches("[0-9a-f]{64}"));
        assertEquals("AmbroLum", service.lookup(secret).orElseThrow().inviter());
        assertEquals("maya@example.test", service.lookup(secret).orElseThrow().email());
    }

    @Test
    void onlyTheOwnerInvites() {
        assertThrows(ForbiddenException.class, () -> service.invite(MEMBER, "maya@example.test"));
        assertThrows(ForbiddenException.class, () -> service.createLink(MEMBER));
        assertThrows(ForbiddenException.class, () -> service.list(MEMBER));
        assertThrows(ForbiddenException.class, () -> service.invite(99, "maya@example.test"), "an account that is gone");
        assertTrue(rows.isEmpty());
        assertTrue(mailbox.recent().isEmpty());
    }

    @Test
    void noInvitationToSomethingThatIsNoAddressOrToAMember() {
        assertThrows(BadRequestException.class, () -> service.invite(OWNER, "not an address"));
        assertThrows(BadRequestException.class, () -> service.invite(OWNER, "GIL@example.test"), "Gilberto is already a member");
        assertTrue(rows.isEmpty());
    }

    @Test
    void invitingTheSameAddressAgainReplacesTheEarlierLink() {
        service.invite(OWNER, "maya@example.test");
        String first = secretMailedTo("maya@example.test");
        service.invite(OWNER, "maya@example.test");
        String second = secretMailedTo("maya@example.test");

        assertTrue(service.usable(first).isEmpty(), "only the newest mail in the inbox works");
        assertTrue(service.usable(second).isPresent());
        assertEquals(1, rows.size());
    }

    @Test
    void aLinkToPassOnNamesNoAddressAndIsShownOnce() {
        InvitationLink link = service.createLink(OWNER);

        assertTrue(link.link().startsWith("https://brief.example/?invite="), link.link());
        assertNull(link.invitation().email());
        String secret = link.link().substring("https://brief.example/?invite=".length());
        assertNull(service.lookup(secret).orElseThrow().email());
        assertTrue(mailbox.recent().isEmpty(), "nothing is mailed");
        assertFalse(rows.get(0).getTokenHash().contains(secret));
    }

    @Test
    void anInvitationWorksOnceAndForSevenDays() {
        service.invite(OWNER, "maya@example.test");
        String secret = secretMailedTo("maya@example.test");
        Invitation invitation = service.usable(secret).orElseThrow();

        now.addAndGet(7 * 24 * 3600 - 1);
        assertTrue(service.usable(secret).isPresent(), "still good in its last second");
        service.markUsed(invitation, 5);
        assertTrue(service.usable(secret).isEmpty(), "used");
        assertThrows(ForbiddenException.class, () -> service.markUsed(invitation, 6), "a second account cannot use it too");
        assertEquals("used", service.list(OWNER).get(0).status());

        service.invite(OWNER, "ben@example.test");
        String later = secretMailedTo("ben@example.test");
        now.addAndGet(7 * 24 * 3600);
        assertTrue(service.usable(later).isEmpty());
        assertEquals("expired", service.list(OWNER).get(0).status());
        assertTrue(service.lookup(later).isEmpty());
    }

    @Test
    void anUnusedInvitationCanBeWithdrawn() {
        InvitationView pending = service.invite(OWNER, "maya@example.test");
        String secret = secretMailedTo("maya@example.test");

        assertThrows(ForbiddenException.class, () -> service.withdraw(MEMBER, pending.invitationId()));
        assertThrows(NotFoundException.class, () -> service.withdraw(OWNER, 999));
        service.withdraw(OWNER, pending.invitationId());
        assertTrue(service.usable(secret).isEmpty(), "the link stops working");

        InvitationView used = service.invite(OWNER, "ben@example.test");
        service.markUsed(service.usable(secretMailedTo("ben@example.test")).orElseThrow(), 5);
        assertThrows(BadRequestException.class, () -> service.withdraw(OWNER, used.invitationId()), "a used one stays as a record");
    }

    @Test
    void anInvitationSentBySomeoneElseCannotBeWithdrawn() {
        // Sent by an earlier owner, say: the owner can change (BRIEF_OWNER_USERNAME).
        Invitation byGilberto = table.save(new Invitation(MEMBER, "ben@example.test", "hash-of-gilberto", 1_000_000L, 2_000_000L));

        assertThrows(NotFoundException.class, () -> service.withdraw(OWNER, byGilberto.getInvitationId()));
        assertTrue(rows.contains(byGilberto));
        assertTrue(service.list(OWNER).isEmpty(), "the list shows only what the caller sent");
    }

    @Test
    void atMostFiftyInvitationsADay() {
        for (int i = 0; i < InvitationService.DAILY_LIMIT; i++) {
            service.createLink(OWNER);
        }
        TooManyRequestsException limit = assertThrows(TooManyRequestsException.class, () -> service.invite(OWNER, "maya@example.test"));
        assertTrue(limit.getRetryAfterSeconds() > 0);
        assertNull(mailbox.lastTo("maya@example.test"));
        now.addAndGet(24 * 3600);
        assertDoesNotThrow(() -> service.invite(OWNER, "maya@example.test"));
    }

    @Test
    void secretsThatAreNotInvitationsAreRefusedQuietly() {
        for (String secret : new String[] {null, "", "   ", "no-such-secret", "x".repeat(500)}) {
            assertTrue(service.usable(secret).isEmpty(), String.valueOf(secret));
            assertTrue(service.lookup(secret).isEmpty());
        }
    }

    // ------------------------------------------------------------------ after the review of 2.5.0

    @Test
    void usingAnInvitationIsOneUpdateThatTheDatabaseDecides() {
        InvitationLink link = service.createLink(OWNER);
        String secret = link.link().substring("https://brief.example/?invite=".length());
        // Two registrations with the same link, both of which found it usable a moment ago:
        Invitation seenByFirst = service.usable(secret).orElseThrow();
        Invitation seenBySecond = service.usable(secret).orElseThrow();
        clearInvocations(table);

        service.markUsed(seenByFirst, 5);
        assertThrows(ForbiddenException.class, () -> service.markUsed(seenBySecond, 6), "the second is refused");

        verify(table, times(2)).claim(eq(seenByFirst.getInvitationId()), anyInt(), anyLong());
        verify(table, never()).save(any(Invitation.class)); // no "read, check, save": that is what let two through
        assertEquals(5, rows.get(0).getUsedBy());
    }

    @Test
    void linksFromAFormerOwnerStopWorking() {
        service.invite(OWNER, "maya@example.test");
        String secret = secretMailedTo("maya@example.test");
        assertTrue(service.usable(secret).isPresent());

        InvitationService afterHandover = new InvitationService(table, accounts, new AccountMails(mailbox, "https://brief.example"),
                new BoardOwner("Gilberto"), 7, now::get);
        assertTrue(afterHandover.usable(secret).isEmpty(), "AmbroLum no longer runs the board");
        assertTrue(afterHandover.lookup(secret).isEmpty());
    }

    @Test
    void unusedInvitationsAreDeletedThirtyDaysAfterTheyExpire() {
        service.invite(OWNER, "maya@example.test");
        service.invite(OWNER, "ben@example.test");
        service.markUsed(service.usable(secretMailedTo("ben@example.test")).orElseThrow(), 5);

        now.addAndGet(7 * 24 * 3600 + InvitationService.KEEP_EXPIRED_SECONDS - 1);
        assertEquals(2, service.list(OWNER).size(), "an expired one is still shown for 30 days");
        now.addAndGet(2);
        List<InvitationView> left = service.list(OWNER);
        assertEquals(1, left.size(), "then Maya's address goes");
        assertEquals("ben@example.test", left.get(0).email(), "a used one stays: Ben is a member");
    }
}
