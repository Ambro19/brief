package net.onetechly.brief.service;

import net.onetechly.brief.dto.AccountResponse;
import net.onetechly.brief.dto.Credentials;
import net.onetechly.brief.dto.PublicAccount;
import net.onetechly.brief.entity.Account;
import net.onetechly.brief.exception.BadRequestException;
import net.onetechly.brief.exception.NotFoundException;
import net.onetechly.brief.exception.UnauthorizedException;
import net.onetechly.brief.repository.AccountRepository;
import net.onetechly.brief.security.PasswordPolicy;
import net.onetechly.brief.security.UsernamePolicy;
import net.onetechly.brief.util.PasswordHasher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Business rules for accounts: registration, login, and the public directory.
 *
 * Differences from the Javalin edition:
 *   - "username not blank, password at least 4 characters" is no longer checked here;
 *     Bean Validation checks it on the Credentials record before the controller runs.
 *     The stronger rule of strict mode depends on configuration, so PasswordPolicy checks it.
 *   - A broken rule is reported by throwing an exception instead of returning null.
 *     ApiExceptionHandler maps each exception type to a status code.
 *
 * Since 2.3 a username is one name whatever its capitals. Every look-up by name goes through
 * Account.keyOf(...), which turns "Maya_Zo" into "maya_zo", and the account table has a unique
 * column holding that form. The name is still stored and shown as the member typed it.
 */
@Service
public class AccountService {

    private final AccountRepository accounts;
    private final UsernamePolicy usernamePolicy;
    private final PasswordPolicy passwordPolicy;

    /** Constructor injection: Spring sees the parameters and passes in the objects it created. */
    public AccountService(AccountRepository accounts, UsernamePolicy usernamePolicy, PasswordPolicy passwordPolicy) {
        this.accounts = accounts;
        this.usernamePolicy = usernamePolicy;
        this.passwordPolicy = passwordPolicy;
    }

    public AccountResponse register(Credentials credentials) {
        if (!usernamePolicy.accepts(credentials.username())) {
            throw new BadRequestException(); // not a name the mode the application runs in allows
        }
        if (!passwordPolicy.accepts(credentials.username(), credentials.password())) {
            throw new BadRequestException(); // too weak for the mode the application runs in
        }
        // Taken, in any capitals: "MAYA" is refused when "maya" exists.
        if (accounts.findByUsernameKey(Account.keyOf(credentials.username())).isPresent()) {
            throw new BadRequestException();
        }
        Account saved;
        try {
            // Store a hash, never the password itself. The Account constructor fills in username_key.
            saved = accounts.save(new Account(credentials.username(), PasswordHasher.hash(credentials.password())));
        } catch (DataIntegrityViolationException e) {
            // Two registrations for the same name arrived together; the unique column rejected the second.
            throw new BadRequestException();
        }
        return new AccountResponse(saved.getAccountId(), saved.getUsername(), credentials.password());
    }

    public AccountResponse login(Credentials credentials) {
        if (credentials == null || credentials.username() == null || credentials.password() == null) {
            throw new UnauthorizedException();
        }
        // No account can have such a name, and PostgreSQL refuses to even compare text that
        // contains the character with code 0. Answer as for any unknown name.
        if (credentials.username().indexOf('\0') >= 0) {
            throw new UnauthorizedException();
        }
        // The name is matched without regard to capitals; the password, of course, is not.
        Account account = accounts.findByUsernameKey(Account.keyOf(credentials.username()))
                .orElseThrow(UnauthorizedException::new);

        String stored = account.getPassword();
        boolean matches;
        if (PasswordHasher.isHashed(stored)) {
            matches = PasswordHasher.verify(credentials.password(), stored);
        } else {
            // Legacy row created before hashing existed. Accept it once, then replace it with a hash.
            matches = PasswordHasher.constantTimeEquals(stored, credentials.password());
            if (matches) {
                account.setPassword(PasswordHasher.hash(credentials.password()));
                accounts.save(account);
            }
        }
        if (!matches) {
            throw new UnauthorizedException();
        }
        return new AccountResponse(account.getAccountId(), account.getUsername(), credentials.password());
    }

    /** Everyone who has an account, without passwords. */
    public List<PublicAccount> listPublicAccounts() {
        List<PublicAccount> result = new ArrayList<>();
        for (Account account : accounts.findAllByOrderByAccountIdAsc()) {
            result.add(new PublicAccount(account.getAccountId(), account.getUsername()));
        }
        return result;
    }

    /** One account without its password. Throws NotFoundException (404) if the id does not exist. */
    public PublicAccount getPublicAccount(int accountId) {
        Account account = accounts.findById(accountId).orElseThrow(NotFoundException::new);
        return new PublicAccount(account.getAccountId(), account.getUsername());
    }
}
