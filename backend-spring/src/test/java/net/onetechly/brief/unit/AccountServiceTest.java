package net.onetechly.brief.unit;

import net.onetechly.brief.dto.AccountResponse;
import net.onetechly.brief.dto.Credentials;
import net.onetechly.brief.dto.PublicAccount;
import net.onetechly.brief.entity.Account;
import net.onetechly.brief.exception.BadRequestException;
import net.onetechly.brief.exception.NotFoundException;
import net.onetechly.brief.exception.UnauthorizedException;
import net.onetechly.brief.repository.AccountRepository;
import net.onetechly.brief.security.AuthMode;
import net.onetechly.brief.security.PasswordPolicy;
import net.onetechly.brief.security.UsernamePolicy;
import net.onetechly.brief.service.AccountService;
import net.onetechly.brief.util.PasswordHasher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests the account rules with a Mockito mock in place of the repository: no Spring, no database.
 * The mock answers what each test tells it to, and records how it was called.
 */
class AccountServiceTest {

    private AccountRepository accounts;
    private AccountService service;

    @BeforeEach
    void setUp() {
        accounts = mock(AccountRepository.class);
        AuthMode strict = new AuthMode("strict");
        service = new AccountService(accounts, new UsernamePolicy(strict), new PasswordPolicy(strict));
    }

    /** Makes the mock behave like the database on insert: it hands back the same object with an id. */
    private void savingAssignsId(int id) {
        when(accounts.save(any(Account.class))).thenAnswer(invocation -> {
            Account account = invocation.getArgument(0);
            account.setAccountId(id);
            return account;
        });
    }

    private static Account existing(int id, String username, String storedPassword) {
        Account account = new Account(username, storedPassword);
        account.setAccountId(id);
        return account;
    }

    @Test
    void registerReturnsAccountWithIdAndTheSubmittedPassword() {
        when(accounts.findByUsernameKey("ambroise")).thenReturn(Optional.empty());
        savingAssignsId(1);

        AccountResponse created = service.register(new Credentials("ambroise", "secret12"));

        assertEquals(new AccountResponse(1, "ambroise", "secret12"), created);
    }

    @Test
    void registerStoresAHashNotThePassword() {
        when(accounts.findByUsernameKey("ambroise")).thenReturn(Optional.empty());
        savingAssignsId(1);

        service.register(new Credentials("ambroise", "secret12"));

        ArgumentCaptor<Account> saved = ArgumentCaptor.forClass(Account.class);
        verify(accounts).save(saved.capture());
        assertNotEquals("secret12", saved.getValue().getPassword());
        assertTrue(PasswordHasher.isHashed(saved.getValue().getPassword()));
    }

    @Test
    void registerRejectsDuplicateUsername() {
        when(accounts.findByUsernameKey("ambroise")).thenReturn(Optional.of(existing(1, "ambroise", "x")));

        assertThrows(BadRequestException.class, () -> service.register(new Credentials("ambroise", "another1")));
        verify(accounts, never()).save(any(Account.class));
    }

    // ---- usernames are one name whatever the capitals (2.3) ----

    @Test
    void registerRejectsANameThatDiffersOnlyInCapitals() {
        // "ambroise" exists. The service must ask the repository for the lower-case key, whatever was typed.
        when(accounts.findByUsernameKey("ambroise")).thenReturn(Optional.of(existing(1, "ambroise", "x")));

        assertThrows(BadRequestException.class, () -> service.register(new Credentials("Ambroise", "another1")));
        assertThrows(BadRequestException.class, () -> service.register(new Credentials("AMBROISE", "another1")));
        verify(accounts, never()).save(any(Account.class));
    }

    @Test
    void registerStoresTheNameAsTypedAndItsLowerCaseKey() {
        when(accounts.findByUsernameKey("maya_zo")).thenReturn(Optional.empty());
        savingAssignsId(1);

        AccountResponse created = service.register(new Credentials("Maya_Zo", "secret12"));

        assertEquals("Maya_Zo", created.username(), "the name is shown as it was typed");
        ArgumentCaptor<Account> saved = ArgumentCaptor.forClass(Account.class);
        verify(accounts).save(saved.capture());
        assertEquals("Maya_Zo", saved.getValue().getUsername());
        assertEquals("maya_zo", saved.getValue().getUsernameKey());
    }

    @Test
    void loginFindsTheAccountWhateverCapitalsAreTyped() {
        Account stored = existing(1, "Maya_Zo", PasswordHasher.hash("secret12"));
        when(accounts.findByUsernameKey("maya_zo")).thenReturn(Optional.of(stored));

        // The answer carries the name as it was registered, not as it was typed at sign-in.
        assertEquals(new AccountResponse(1, "Maya_Zo", "secret12"), service.login(new Credentials("MAYA_ZO", "secret12")));
        assertEquals(new AccountResponse(1, "Maya_Zo", "secret12"), service.login(new Credentials(" maya_zo ", "secret12")));
        // The password stays exact.
        assertThrows(UnauthorizedException.class, () -> service.login(new Credentials("maya_zo", "SECRET12")));
    }

    @Test
    void theKeyIsLowerCaseWithoutSurroundingSpacesAndFollowsARename() {
        assertEquals("maya_zo", Account.keyOf("  Maya_ZO "));
        assertNull(Account.keyOf(null));

        Account account = new Account("First", "hash");
        assertEquals("first", account.getUsernameKey());
        account.setUsername("Second");
        assertEquals("second", account.getUsernameKey(), "changing the name changes the key with it");
    }

    @Test
    void registerRejectsANameTheStrictRuleDoesNotAllowWithoutTouchingTheDatabase() {
        assertThrows(BadRequestException.class, () -> service.register(new Credentials("ab", "secret12")));
        assertThrows(BadRequestException.class, () -> service.register(new Credentials("two words", "secret12")));
        verify(accounts, never()).findByUsernameKey(any());
        verify(accounts, never()).save(any(Account.class));
    }

    @Test
    void registerRejectsAWeakPasswordInStrictModeWithoutTouchingTheDatabase() {
        assertThrows(BadRequestException.class, () -> service.register(new Credentials("ambroise", "password")));
        assertThrows(BadRequestException.class, () -> service.register(new Credentials("ambroise", "abc1")));
        verify(accounts, never()).save(any(Account.class));
    }

    @Test
    void registerAcceptsAFourCharacterPasswordInCompatMode() {
        AuthMode compatMode = new AuthMode("compat");
        AccountService compat = new AccountService(accounts, new UsernamePolicy(compatMode), new PasswordPolicy(compatMode));
        when(accounts.findByUsernameKey("ambroise")).thenReturn(Optional.empty());
        savingAssignsId(1);

        assertEquals(new AccountResponse(1, "ambroise", "pass"), compat.register(new Credentials("ambroise", "pass")));
    }

    @Test
    void registerLosingARaceForTheSameNameIsABadRequest() {
        when(accounts.findByUsernameKey("ambroise")).thenReturn(Optional.empty());
        when(accounts.save(any(Account.class))).thenThrow(new DataIntegrityViolationException("unique username"));

        assertThrows(BadRequestException.class, () -> service.register(new Credentials("ambroise", "secret12")));
    }

    @Test
    void loginSucceedsWithTheRightPassword() {
        Account stored = existing(1, "ambroise", PasswordHasher.hash("secret12"));
        when(accounts.findByUsernameKey("ambroise")).thenReturn(Optional.of(stored));

        assertEquals(new AccountResponse(1, "ambroise", "secret12"), service.login(new Credentials("ambroise", "secret12")));
    }

    @Test
    void loginFailsWithWrongPasswordOrUnknownUser() {
        Account stored = existing(1, "ambroise", PasswordHasher.hash("secret12"));
        when(accounts.findByUsernameKey("ambroise")).thenReturn(Optional.of(stored));
        when(accounts.findByUsernameKey("nobody")).thenReturn(Optional.empty());

        assertThrows(UnauthorizedException.class, () -> service.login(new Credentials("ambroise", "wrong")));
        assertThrows(UnauthorizedException.class, () -> service.login(new Credentials("nobody", "secret12")));
        assertThrows(UnauthorizedException.class, () -> service.login(new Credentials("ambroise", null)));
        assertThrows(UnauthorizedException.class, () -> service.login(new Credentials(null, "secret12")));
    }

    @Test
    void aUsernameContainingTheNulCharacterIsAnOrdinaryFailedLoginAndNeverReachesTheDatabase() {
        // PostgreSQL refuses to compare text that contains this character; the service must not ask it to.
        assertThrows(UnauthorizedException.class, () -> service.login(new Credentials("ambro\u0000ise", "secret12")));
        verify(accounts, never()).findByUsernameKey(any());
    }

    @Test
    void legacyPlainTextPasswordWorksOnceThenIsUpgradedToAHash() {
        Account legacy = existing(1, "testuser1", "password"); // as in the seed data
        when(accounts.findByUsernameKey("testuser1")).thenReturn(Optional.of(legacy));

        assertEquals(new AccountResponse(1, "testuser1", "password"), service.login(new Credentials("testuser1", "password")));
        verify(accounts).save(legacy);
        assertTrue(PasswordHasher.isHashed(legacy.getPassword()), "stored value should now be a hash");

        // and it keeps working after the upgrade
        assertNotNull(service.login(new Credentials("testuser1", "password")));
        assertThrows(UnauthorizedException.class, () -> service.login(new Credentials("testuser1", "Password")));
    }

    @Test
    void legacyPasswordIsNotUpgradedOnAFailedLogin() {
        Account legacy = existing(1, "testuser1", "password");
        when(accounts.findByUsernameKey("testuser1")).thenReturn(Optional.of(legacy));

        assertThrows(UnauthorizedException.class, () -> service.login(new Credentials("testuser1", "nope")));
        assertEquals("password", legacy.getPassword());
        verify(accounts, never()).save(any(Account.class));
    }

    @Test
    void publicDirectoryNeverContainsPasswords() {
        when(accounts.findAllByOrderByAccountIdAsc())
                .thenReturn(List.of(existing(1, "ambroise", "hash-1"), existing(2, "john", "hash-2")));
        when(accounts.findById(2)).thenReturn(Optional.of(existing(2, "john", "hash-2")));
        when(accounts.findById(99)).thenReturn(Optional.empty());

        assertEquals(List.of(new PublicAccount(1, "ambroise"), new PublicAccount(2, "john")), service.listPublicAccounts());
        assertEquals(new PublicAccount(2, "john"), service.getPublicAccount(2));
        assertThrows(NotFoundException.class, () -> service.getPublicAccount(99));
    }
}
