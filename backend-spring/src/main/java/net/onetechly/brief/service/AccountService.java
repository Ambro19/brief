package net.onetechly.brief.service;

import net.onetechly.brief.dto.AccountResponse;
import net.onetechly.brief.dto.Credentials;
import net.onetechly.brief.dto.PublicAccount;
import net.onetechly.brief.dto.Registration;
import net.onetechly.brief.entity.Account;
import net.onetechly.brief.exception.BadRequestException;
import net.onetechly.brief.exception.ForbiddenException;
import net.onetechly.brief.exception.NotFoundException;
import net.onetechly.brief.exception.UnauthorizedException;
import net.onetechly.brief.repository.AccountRepository;
import net.onetechly.brief.security.BoardAccess;
import net.onetechly.brief.security.EmailPolicy;
import net.onetechly.brief.security.PasswordPolicy;
import net.onetechly.brief.security.UsernamePolicy;
import net.onetechly.brief.util.PasswordHasher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
 *
 * Since 2.4 a new account also gives an email address (strict mode), and on a closed board
 * the invitation code. What happens with the address afterwards is AccountEmailService's job.
 */
@Service
public class AccountService {

    private final AccountRepository accounts;
    private final UsernamePolicy usernamePolicy;
    private final PasswordPolicy passwordPolicy;
    private final EmailPolicy emailPolicy;
    private final BoardAccess board;
    private final AccountEmailService emailService;

    /** Constructor injection: Spring sees the parameters and passes in the objects it created. */
    public AccountService(AccountRepository accounts, UsernamePolicy usernamePolicy, PasswordPolicy passwordPolicy,
                          EmailPolicy emailPolicy, BoardAccess board, AccountEmailService emailService) {
        this.accounts = accounts;
        this.usernamePolicy = usernamePolicy;
        this.passwordPolicy = passwordPolicy;
        this.emailPolicy = emailPolicy;
        this.board = board;
        this.emailService = emailService;
    }

    /**
     * Creates an account. 403 if the board is closed and the invitation code is missing or
     * wrong; 400 for everything else that is not acceptable.
     *
     * {@code @Transactional}: the account row and the row of its confirmation link are stored
     * together or not at all.
     */
    @Transactional
    public AccountResponse register(Registration registration) {
        // The invitation code comes first: without it, nothing else about the request is answered.
        if (!board.admits(registration.inviteCode())) {
            throw new ForbiddenException();
        }
        if (!usernamePolicy.accepts(registration.username())) {
            throw new BadRequestException(); // not a name the mode the application runs in allows
        }
        if (!passwordPolicy.accepts(registration.username(), registration.password())) {
            throw new BadRequestException(); // too weak for the mode the application runs in
        }
        String email = null;
        if (registration.email() != null && !registration.email().isBlank()) {
            email = EmailPolicy.normalise(registration.email());
            if (email == null) {
                throw new BadRequestException(); // something was given, and it is not an address
            }
        } else if (emailPolicy.isRequired()) {
            throw new BadRequestException(); // strict mode: an account needs an address
        }
        // Taken, in any capitals: "MAYA" is refused when "maya" exists.
        if (accounts.findByUsernameKey(Account.keyOf(registration.username())).isPresent()) {
            throw new BadRequestException();
        }
        // One address, one account.
        if (email != null && accounts.findByEmail(email).isPresent()) {
            throw new BadRequestException();
        }
        Account account = new Account(registration.username(), PasswordHasher.hash(registration.password()));
        if (email != null) {
            account.setEmail(email);
        }
        Account saved;
        try {
            // Store a hash, never the password itself. The Account constructor fills in username_key.
            saved = accounts.save(account);
        } catch (DataIntegrityViolationException e) {
            // Two registrations for the same name or address arrived together; a unique column rejected the second.
            throw new BadRequestException();
        }
        if (email != null) {
            emailService.startVerification(saved); // mail the link that confirms the address
        }
        return new AccountResponse(saved.getAccountId(), saved.getUsername(), registration.password());
    }

    public AccountResponse login(Credentials credentials) {
        Account account = authenticate(credentials);
        return new AccountResponse(account.getAccountId(), account.getUsername(), credentials.password());
    }

    /**
     * Checks a username and password and returns the account they belong to, as it was read
     * for that check. Throws UnauthorizedException (401) on any mismatch.
     *
     * The controller issues the login token from this same object, so the token generation in
     * the token (Account.tokenVersion) is the one that stood next to the password that was
     * checked. Reading the account a second time could pick up a row whose password has been
     * replaced in between.
     */
    public Account authenticate(Credentials credentials) {
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
        return account;
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
