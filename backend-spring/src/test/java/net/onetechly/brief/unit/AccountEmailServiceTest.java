package net.onetechly.brief.unit;

import net.onetechly.brief.repository.EmailBounceRepository;
import net.onetechly.brief.service.EmailBounces;
import net.onetechly.brief.repository.AccountSessionRepository;
import net.onetechly.brief.service.Sessions;
import net.onetechly.brief.dto.Profile;
import net.onetechly.brief.entity.Account;
import net.onetechly.brief.entity.AccountToken;
import net.onetechly.brief.exception.BadRequestException;
import net.onetechly.brief.exception.ForbiddenException;
import net.onetechly.brief.exception.NotFoundException;
import net.onetechly.brief.exception.TooManyRequestsException;
import net.onetechly.brief.mail.AccountMails;
import net.onetechly.brief.mail.LogMailer;
import net.onetechly.brief.mail.Mail;
import net.onetechly.brief.repository.AccountRepository;
import net.onetechly.brief.security.AuthMode;
import net.onetechly.brief.security.BoardOwner;
import net.onetechly.brief.security.PasswordPolicy;
import net.onetechly.brief.service.AccountEmailService;
import net.onetechly.brief.service.AccountTokens;
import net.onetechly.brief.util.PasswordHasher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The rules around a member's email address, without Spring and without a database.
 *
 * What is real here: the service, the link logic (AccountTokens) and the texts of the mails.
 * What is played: the two tables (a mock for accounts, a list for the links), the clock, and
 * the post office: LogMailer keeps what it was asked to send, so a test can read the mail.
 */
class AccountEmailServiceTest {
    private final EmailBounceRepository bounceTable = mock(EmailBounceRepository.class);
    private final AccountSessionRepository sessionTable = mock(AccountSessionRepository.class);


    private static final String ADDRESS = "ada@example.test";

    private final AtomicLong now = new AtomicLong(1_000_000);
    private final InMemoryTokens table = new InMemoryTokens();
    private final LogMailer mailbox = new LogMailer();
    private AccountRepository accounts;
    private AccountEmailService service;

    @BeforeEach
    void setUp() {
        accounts = mock(AccountRepository.class);
        when(accounts.save(any(Account.class))).thenAnswer(call -> call.getArgument(0));
        when(accounts.saveAndFlush(any(Account.class))).thenAnswer(call -> call.getArgument(0));
        service = serviceFor("strict");
    }

    private AccountEmailService serviceFor(String mode) {
        AuthMode authMode = new AuthMode(mode);
        return new AccountEmailService(accounts, new AccountTokens(table.repository, now::get),
                new AccountMails(mailbox, "https://brief.example"), new PasswordPolicy(authMode), authMode, new BoardOwner("Cy"), new EmailBounces(bounceTable, now::get),
                new Sessions(sessionTable, accounts, 12 * 60 * 60, now::get), now::get); // 2.8: a new password ends every session
    }

    /** An account the mocked table knows, by id and (if it has one) by address. */
    private Account stored(int id, String username, String email, boolean confirmed) {
        Account account = new Account(username, PasswordHasher.hash("old-secret-1"));
        account.setAccountId(id);
        if (email != null) {
            account.setEmail(email);
            when(accounts.findByEmail(email)).thenReturn(Optional.of(account));
        }
        if (confirmed) {
            account.setEmailVerifiedAt(999_000L);
        }
        when(accounts.findById(id)).thenReturn(Optional.of(account));
        return account;
    }

    /** The secret in the newest mail to this address: the text between "?verify=" (or "?reset=") and the end of that line. */
    private String secretMailedTo(String address, String kind) {
        Mail mail = mailbox.lastTo(address);
        assertNotNull(mail, "a mail to " + address);
        String marker = "/?" + kind + "=";
        int start = mail.text().indexOf(marker);
        assertTrue(start >= 0, "the mail holds a " + kind + " link: " + mail.text());
        int from = start + marker.length();
        return mail.text().substring(from, mail.text().indexOf('\n', from));
    }

    // ------------------------------------------------------------------ what a member sees and may do

    @Test
    void theProfileSaysWhetherTheMemberMayPost() {
        stored(1, "Ada", null, false);
        stored(2, "Ben", "ben@example.test", false);
        stored(3, "Cy", "cy@example.test", true);

        assertEquals(new Profile(1, "Ada", null, false, true, false, false), service.profile(1), "no address: nothing to wait for");
        assertEquals(new Profile(2, "Ben", "ben@example.test", false, false, false, false), service.profile(2), "address given, not confirmed");
        assertEquals(new Profile(3, "Cy", "cy@example.test", true, true, true, false), service.profile(3), "Cy runs the board (2.5)");
        assertThrows(NotFoundException.class, () -> service.profile(99));
    }

    @Test
    void anUnconfirmedAddressStopsPostingInStrictModeOnly() {
        stored(2, "Ben", "ben@example.test", false);
        stored(3, "Cy", "cy@example.test", true);

        assertThrows(ForbiddenException.class, () -> service.requireMayPost(2));
        assertDoesNotThrow(() -> service.requireMayPost(3));
        assertDoesNotThrow(() -> service.requireMayPost(null), "nobody is signed in: not this rule's business");
        assertDoesNotThrow(() -> service.requireMayPost(99), "no such account: the message service answers that");

        AccountEmailService compat = serviceFor("compat");
        assertDoesNotThrow(() -> compat.requireMayPost(2));
        assertTrue(compat.profile(2).canPost());
    }

    // ------------------------------------------------------------------ confirming an address

    @Test
    void givingAnAddressStoresItInLowerCaseAndMailsALink() {
        Account ada = stored(1, "Ada", null, false);

        Profile profile = service.setEmail(1, "  Ada@Example.TEST ");

        assertEquals(ADDRESS, ada.getEmail());
        assertEquals(ADDRESS, profile.email());
        assertFalse(profile.emailVerified());
        assertFalse(profile.canPost(), "from now on the address has to be confirmed first");
        Mail mail = mailbox.lastTo(ADDRESS);
        assertTrue(mail.subject().contains("Confirm"));
        assertTrue(mail.text().contains("https://brief.example/?verify="), mail.text());
        assertEquals(43, secretMailedTo(ADDRESS, "verify").length());
        assertEquals(1, table.rows.size());
        assertEquals(AccountToken.VERIFY, table.rows.get(0).getPurpose());
    }

    @Test
    void anAddressIsRefusedIfItIsNoAddressTakenOrAlreadyConfirmed() {
        stored(1, "Ada", null, false);
        stored(2, "Ben", "ben@example.test", false);
        stored(3, "Cy", "cy@example.test", true);

        assertThrows(BadRequestException.class, () -> service.setEmail(1, "not an address"));
        assertThrows(BadRequestException.class, () -> service.setEmail(1, null));
        assertThrows(BadRequestException.class, () -> service.setEmail(1, "BEN@example.test"), "Ben has it, whatever the capitals");
        assertThrows(BadRequestException.class, () -> service.setEmail(3, "other@example.test"), "confirmed addresses do not change in 2.4");
        assertThrows(NotFoundException.class, () -> service.setEmail(99, ADDRESS));
        assertTrue(mailbox.recent().isEmpty(), "none of these sent a mail");
        assertTrue(table.rows.isEmpty());
    }

    @Test
    void anUnconfirmedAddressCanBeCorrectedAndTheOldLinkStopsWorking() {
        Account ben = stored(2, "Ben", "bne@example.test", false);
        service.startVerification(ben);
        String wrongInbox = secretMailedTo("bne@example.test", "verify");

        service.setEmail(2, "ben@example.test");

        assertEquals("ben@example.test", ben.getEmail());
        assertThrows(BadRequestException.class, () -> service.verify(wrongInbox), "the link sent to the mistyped address");
        assertFalse(ben.isEmailVerified());
        service.verify(secretMailedTo("ben@example.test", "verify"));
        assertTrue(ben.isEmailVerified());
    }

    @Test
    void twoMembersGivingOneAddressAtTheSameInstantAreToldApartByTheDatabase() {
        stored(1, "Ada", null, false);
        // Both passed the "is it taken?" look; the unique column then refuses the second writer.
        when(accounts.saveAndFlush(any(Account.class))).thenThrow(new DataIntegrityViolationException("uq_account_email"));

        assertThrows(BadRequestException.class, () -> service.setEmail(1, ADDRESS));
        assertTrue(mailbox.recent().isEmpty(), "the loser gets no confirmation mail");
        assertTrue(table.rows.isEmpty());
    }

    @Test
    void givingTheSameAddressAgainIsAllowed() {
        Account ben = stored(2, "Ben", "ben@example.test", false);
        assertDoesNotThrow(() -> service.setEmail(2, "ben@example.test"), "the holder of the address is the member");
        assertEquals("ben@example.test", ben.getEmail());
    }

    @Test
    void theLinkConfirmsTheAddressOnce() {
        Account ben = stored(2, "Ben", "ben@example.test", false);
        service.startVerification(ben);
        String secret = secretMailedTo("ben@example.test", "verify");
        now.addAndGet(500);

        service.verify(secret);

        assertEquals(Long.valueOf(1_000_500), ben.getEmailVerifiedAt());
        assertTrue(service.profile(2).canPost());
        assertThrows(BadRequestException.class, () -> service.verify(secret), "used");
        assertThrows(BadRequestException.class, () -> service.verify("no-such-secret"));
        assertThrows(BadRequestException.class, () -> service.verify(null));
    }

    @Test
    void aConfirmationLinkIsGoodForADay() {
        Account ben = stored(2, "Ben", "ben@example.test", false);
        service.startVerification(ben);
        String secret = secretMailedTo("ben@example.test", "verify");

        now.addAndGet(AccountTokens.VERIFY_SECONDS + 1);

        assertThrows(BadRequestException.class, () -> service.verify(secret));
        assertFalse(ben.isEmailVerified());
    }

    @Test
    void aPasswordLinkDoesNotConfirmAnAddressAndTheReverse() {
        Account cy = stored(3, "Cy", "cy@example.test", true);
        service.requestPasswordReset("cy@example.test");
        String reset = secretMailedTo("cy@example.test", "reset");
        Account ben = stored(2, "Ben", "ben@example.test", false);
        service.startVerification(ben);
        String verify = secretMailedTo("ben@example.test", "verify");

        assertThrows(BadRequestException.class, () -> service.verify(reset));
        assertThrows(BadRequestException.class, () -> service.resetPassword(verify, "a-new-password-9"));
        assertFalse(ben.isEmailVerified());
        assertTrue(PasswordHasher.verify("old-secret-1", cy.getPassword()));
    }

    @Test
    void theLinkCanBeMailedAgainButNotWithinAMinute() {
        Account ben = stored(2, "Ben", "ben@example.test", false);
        service.startVerification(ben);
        String first = secretMailedTo("ben@example.test", "verify");

        now.addAndGet(20);
        TooManyRequestsException tooSoon = assertThrows(TooManyRequestsException.class, () -> service.resendVerification(2));
        assertEquals(40, tooSoon.getRetryAfterSeconds(), "the answer says how long to wait");
        assertEquals(1, mailbox.recent().size());

        now.addAndGet(40);
        service.resendVerification(2);
        String second = secretMailedTo("ben@example.test", "verify");

        assertEquals(2, mailbox.recent().size());
        assertNotEquals(first, second);
        assertThrows(BadRequestException.class, () -> service.verify(first), "the newer link replaces the older one");
        service.verify(second);
        assertTrue(ben.isEmailVerified());
    }

    @Test
    void thereIsNothingToMailAgainWithoutAnAddressOrOnceItIsConfirmed() {
        stored(1, "Ada", null, false);
        stored(3, "Cy", "cy@example.test", true);

        assertThrows(BadRequestException.class, () -> service.resendVerification(1));
        assertThrows(BadRequestException.class, () -> service.resendVerification(3));
        assertThrows(NotFoundException.class, () -> service.resendVerification(99));
        assertTrue(mailbox.recent().isEmpty());
    }

    // ------------------------------------------------------------------ a forgotten password

    @Test
    void forgotPasswordMailsALinkToAConfirmedAddressOnly() {
        stored(2, "Ben", "ben@example.test", false);
        stored(3, "Cy", "cy@example.test", true);

        service.requestPasswordReset("nobody@example.test");
        service.requestPasswordReset("ben@example.test");
        service.requestPasswordReset("not an address");
        service.requestPasswordReset(null);
        assertTrue(mailbox.recent().isEmpty(), "unknown, unconfirmed and malformed addresses get no mail, and no error");

        service.requestPasswordReset(" CY@example.test ");
        Mail mail = mailbox.lastTo("cy@example.test");
        assertNotNull(mail);
        assertTrue(mail.subject().contains("new password"));
        assertTrue(mail.text().contains("https://brief.example/?reset="), mail.text());
    }

    @Test
    void aSecondRequestWithinAMinuteSendsNoSecondMail() {
        stored(3, "Cy", "cy@example.test", true);

        service.requestPasswordReset("cy@example.test");
        now.addAndGet(30);
        service.requestPasswordReset("cy@example.test");
        assertEquals(1, mailbox.recent().size(), "and no error either: the answer must not depend on the address");

        now.addAndGet(30);
        service.requestPasswordReset("cy@example.test");
        assertEquals(2, mailbox.recent().size());
    }

    @Test
    void theLinkSetsANewPasswordAndEndsEarlierSessions() {
        Account cy = stored(3, "Cy", "cy@example.test", true);
        service.requestPasswordReset("cy@example.test");
        String secret = secretMailedTo("cy@example.test", "reset");
        now.addAndGet(120);

        service.resetPassword(secret, "a-new-password-9");

        assertTrue(PasswordHasher.verify("a-new-password-9", cy.getPassword()));
        assertFalse(PasswordHasher.verify("old-secret-1", cy.getPassword()));
        assertNotEquals("a-new-password-9", cy.getPassword(), "stored as a hash");
        assertEquals(1, cy.getTokenVersion(), "the account's token generation moved on: every earlier login token is refused");
        assertThrows(BadRequestException.class, () -> service.resetPassword(secret, "another-password-9"), "the link works once");
        assertTrue(PasswordHasher.verify("a-new-password-9", cy.getPassword()));
    }

    @Test
    void aRefusedPasswordLeavesTheLinkUsable() {
        Account cy = stored(3, "Cy", "cy@example.test", true);
        service.requestPasswordReset("cy@example.test");
        String secret = secretMailedTo("cy@example.test", "reset");

        assertThrows(BadRequestException.class, () -> service.resetPassword(secret, "short"));
        assertThrows(BadRequestException.class, () -> service.resetPassword(secret, null));
        assertTrue(PasswordHasher.verify("old-secret-1", cy.getPassword()), "nothing changed");
        assertEquals(0, cy.getTokenVersion(), "and nobody was signed out");

        service.resetPassword(secret, "a-new-password-9");
        assertTrue(PasswordHasher.verify("a-new-password-9", cy.getPassword()));
    }

    @Test
    void aPasswordLinkIsGoodForAnHour() {
        Account cy = stored(3, "Cy", "cy@example.test", true);
        service.requestPasswordReset("cy@example.test");
        String secret = secretMailedTo("cy@example.test", "reset");

        now.addAndGet(AccountTokens.RESET_SECONDS + 1);

        assertThrows(BadRequestException.class, () -> service.resetPassword(secret, "a-new-password-9"));
        assertTrue(PasswordHasher.verify("old-secret-1", cy.getPassword()));
    }

    @Test
    void aLinkWhoseAccountIsGoneIsRefused() {
        stored(3, "Cy", "cy@example.test", true);
        service.requestPasswordReset("cy@example.test");
        String secret = secretMailedTo("cy@example.test", "reset");
        when(accounts.findById(3)).thenReturn(Optional.empty());

        assertThrows(BadRequestException.class, () -> service.resetPassword(secret, "a-new-password-9"));
        assertThrows(BadRequestException.class, () -> service.resetPassword("no-such-secret", "a-new-password-9"));
    }
}
