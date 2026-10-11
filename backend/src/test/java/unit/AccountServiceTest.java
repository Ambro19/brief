package unit;

import DAO.AccountDAO;
import DTO.PublicAccount;
import Model.Account;
import Service.AccountService;
import Util.PasswordHasher;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/** Tests the account rules with an in-memory stand-in for the database. */
public class AccountServiceTest {

    /** Keeps accounts in a map instead of a database, so these tests need no JDBC. */
    private static class FakeAccountDAO extends AccountDAO {
        final Map<Integer, Account> rows = new LinkedHashMap<>();
        int nextId = 1;

        @Override
        public Account getAccountByUsername(String username) {
            for (Account a : rows.values()) {
                if (a.getUsername().equals(username)) return a;
            }
            return null;
        }

        @Override
        public Account getAccountById(int accountId) {
            return rows.get(accountId);
        }

        @Override
        public List<Account> getAllAccounts() {
            return new ArrayList<>(rows.values());
        }

        @Override
        public Account saveAccount(Account account) {
            Account saved = new Account(nextId++, account.getUsername(), account.getPassword());
            rows.put(saved.getAccount_id(), saved);
            return saved;
        }

        @Override
        public void updatePassword(int accountId, String storedPassword) {
            rows.get(accountId).setPassword(storedPassword);
        }
    }

    private FakeAccountDAO dao;
    private AccountService service;

    @Before
    public void setUp() {
        dao = new FakeAccountDAO();
        service = new AccountService(dao);
    }

    @Test
    public void registerReturnsAccountWithIdAndTheSubmittedPassword() {
        Account created = service.registerAccount(new Account("ambroise", "secret12"));
        assertEquals(new Account(1, "ambroise", "secret12"), created);
    }

    @Test
    public void registerStoresAHashNotThePassword() {
        service.registerAccount(new Account("ambroise", "secret12"));
        String stored = dao.rows.get(1).getPassword();
        assertNotEquals("secret12", stored);
        assertTrue(PasswordHasher.isHashed(stored));
    }

    @Test
    public void registerRejectsBlankUsername() {
        assertNull(service.registerAccount(new Account("", "secret12")));
        assertNull(service.registerAccount(new Account("   ", "secret12")));
        assertNull(service.registerAccount(new Account(null, "secret12")));
        assertTrue(dao.rows.isEmpty());
    }

    @Test
    public void registerRejectsPasswordShorterThanFour() {
        assertNull(service.registerAccount(new Account("ambroise", "abc")));
        assertNull(service.registerAccount(new Account("ambroise", null)));
        assertNotNull(service.registerAccount(new Account("ambroise", "abcd")));
    }

    @Test
    public void registerRejectsDuplicateUsername() {
        assertNotNull(service.registerAccount(new Account("ambroise", "secret12")));
        assertNull(service.registerAccount(new Account("ambroise", "another1")));
        assertEquals(1, dao.rows.size());
    }

    @Test
    public void loginSucceedsWithTheRightPassword() {
        service.registerAccount(new Account("ambroise", "secret12"));
        assertEquals(new Account(1, "ambroise", "secret12"), service.login(new Account("ambroise", "secret12")));
    }

    @Test
    public void loginFailsWithWrongPasswordOrUnknownUser() {
        service.registerAccount(new Account("ambroise", "secret12"));
        assertNull(service.login(new Account("ambroise", "wrong")));
        assertNull(service.login(new Account("nobody", "secret12")));
        assertNull(service.login(new Account("ambroise", null)));
    }

    @Test
    public void legacyPlainTextPasswordWorksOnceThenIsUpgradedToAHash() {
        dao.rows.put(1, new Account(1, "testuser1", "password")); // as in the seed data
        dao.nextId = 2;

        assertEquals(new Account(1, "testuser1", "password"), service.login(new Account("testuser1", "password")));
        assertTrue("stored value should now be a hash", PasswordHasher.isHashed(dao.rows.get(1).getPassword()));

        // and it keeps working after the upgrade
        assertNotNull(service.login(new Account("testuser1", "password")));
        assertNull(service.login(new Account("testuser1", "Password")));
    }

    @Test
    public void legacyPasswordIsNotUpgradedOnAFailedLogin() {
        dao.rows.put(1, new Account(1, "testuser1", "password"));
        assertNull(service.login(new Account("testuser1", "nope")));
        assertEquals("password", dao.rows.get(1).getPassword());
    }

    @Test
    public void publicDirectoryNeverContainsPasswords() {
        service.registerAccount(new Account("ambroise", "secret12"));
        service.registerAccount(new Account("john", "secret34"));

        List<PublicAccount> all = service.getAllPublicAccounts();
        assertEquals(2, all.size());
        assertEquals("ambroise", all.get(0).getUsername());
        assertEquals(2, all.get(1).getAccount_id());

        assertEquals("john", service.getPublicAccount(2).getUsername());
        assertNull(service.getPublicAccount(99));
    }
}
