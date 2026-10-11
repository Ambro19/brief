package Service;

import DAO.AccountDAO;
import DTO.PublicAccount;
import Model.Account;
import Util.PasswordHasher;

import java.util.ArrayList;
import java.util.List;

/**
 * Business rules for accounts: registration, login, and the public directory.
 *
 * v1.0 change from the training version: passwords are stored as salted PBKDF2
 * hashes instead of plain text. The API contract is unchanged - register and login
 * still answer with the account JSON the caller sent, plus its account_id - but the
 * database never holds a readable password for new accounts.
 */
public class AccountService {

    private static final int MAX_USERNAME_LENGTH = 255; // column size

    private final AccountDAO accountDAO;

    public AccountService(AccountDAO accountDAO) {
        this.accountDAO = accountDAO;
    }

    public Account registerAccount(Account account) {
        // Validate username and password
        if (account == null || account.getUsername() == null || account.getUsername().isBlank() ||
            account.getUsername().length() > MAX_USERNAME_LENGTH ||
            account.getPassword() == null || account.getPassword().length() < 4) {
            return null;
        }
        // Check if username is unique
        if (accountDAO.getAccountByUsername(account.getUsername()) != null) {
            return null;
        }
        // Store a hash, never the password itself
        Account toSave = new Account(account.getUsername(), PasswordHasher.hash(account.getPassword()));
        Account saved = accountDAO.saveAccount(toSave);
        if (saved == null) {
            return null; // lost a race for the same username
        }
        return new Account(saved.getAccount_id(), saved.getUsername(), account.getPassword());
    }

    public Account login(Account account) {
        if (account == null || account.getUsername() == null || account.getPassword() == null) {
            return null;
        }
        Account existingAccount = accountDAO.getAccountByUsername(account.getUsername());
        if (existingAccount == null) {
            return null;
        }

        String stored = existingAccount.getPassword();
        boolean matches;
        if (PasswordHasher.isHashed(stored)) {
            matches = PasswordHasher.verify(account.getPassword(), stored);
        } else {
            // Legacy row created before hashing existed (for example the seed data).
            // Accept it once, then replace the plain text with a hash.
            matches = PasswordHasher.constantTimeEquals(stored, account.getPassword());
            if (matches) {
                accountDAO.updatePassword(existingAccount.getAccount_id(),
                        PasswordHasher.hash(account.getPassword()));
            }
        }
        if (!matches) {
            return null;
        }
        return new Account(existingAccount.getAccount_id(), existingAccount.getUsername(), account.getPassword());
    }

    /** Everyone who has an account, without passwords. */
    public List<PublicAccount> getAllPublicAccounts() {
        List<PublicAccount> result = new ArrayList<>();
        for (Account account : accountDAO.getAllAccounts()) {
            result.add(new PublicAccount(account.getAccount_id(), account.getUsername()));
        }
        return result;
    }

    /** One account without its password, or null if the id does not exist. */
    public PublicAccount getPublicAccount(int accountId) {
        Account account = accountDAO.getAccountById(accountId);
        return account == null ? null : new PublicAccount(account.getAccount_id(), account.getUsername());
    }
}
