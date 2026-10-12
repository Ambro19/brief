package net.onetechly.brief.service;

import net.onetechly.brief.dto.Profile;
import net.onetechly.brief.entity.Account;
import net.onetechly.brief.entity.AccountToken;
import net.onetechly.brief.exception.BadRequestException;
import net.onetechly.brief.exception.ForbiddenException;
import net.onetechly.brief.exception.NotFoundException;
import net.onetechly.brief.exception.TooManyRequestsException;
import net.onetechly.brief.mail.AccountMails;
import net.onetechly.brief.repository.AccountRepository;
import net.onetechly.brief.security.AuthMode;
import net.onetechly.brief.security.EmailPolicy;
import net.onetechly.brief.security.PasswordPolicy;
import net.onetechly.brief.util.PasswordHasher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.function.LongSupplier;

/**
 * Everything that involves a member's email address (2.4): confirming it, and using it to get
 * back into an account whose password was forgotten.
 *
 * Confirming an address:
 *   1. A member gives an address (when creating the account, or later).
 *   2. A link with a one-time secret is mailed to it.
 *   3. Opening the link sends the secret back. Only someone who can read that inbox can do so,
 *      which is the proof that the address is the member's.
 *   Until step 3 the member can read and cannot post (strict mode).
 *
 * A forgotten password:
 *   1. Someone types an address into "Forgot password?".
 *   2. If it is the confirmed address of an account, a link is mailed to it. If not, nothing
 *      happens. Either way the answer is the same, so the form cannot be used to find out
 *      who has an account.
 *   3. Opening the link lets its holder set a new password. Every login token issued before
 *      that moment stops working: whoever knew the old password is signed out.
 */
@Service
public class AccountEmailService {

    /** At most one mail of a kind per account in this many seconds. */
    public static final long MAIL_INTERVAL_SECONDS = 60;

    private final AccountRepository accounts;
    private final AccountTokens tokens;
    private final AccountMails mails;
    private final PasswordPolicy passwordPolicy;
    private final AuthMode mode;
    private final LongSupplier clockSeconds;

    /** The constructor Spring uses. */
    @Autowired
    public AccountEmailService(AccountRepository accounts, AccountTokens tokens, AccountMails mails,
                               PasswordPolicy passwordPolicy, AuthMode mode) {
        this(accounts, tokens, mails, passwordPolicy, mode, () -> System.currentTimeMillis() / 1000);
    }

    /** The clock is injectable so tests can move time forward without sleeping. */
    public AccountEmailService(AccountRepository accounts, AccountTokens tokens, AccountMails mails,
                               PasswordPolicy passwordPolicy, AuthMode mode, LongSupplier clockSeconds) {
        this.accounts = accounts;
        this.tokens = tokens;
        this.mails = mails;
        this.passwordPolicy = passwordPolicy;
        this.mode = mode;
        this.clockSeconds = clockSeconds;
    }

    /** What the member may see of their own account. Throws NotFoundException (404) if the id does not exist. */
    public Profile profile(int accountId) {
        return profileOf(accounts.findById(accountId).orElseThrow(NotFoundException::new));
    }

    private Profile profileOf(Account account) {
        return new Profile(account.getAccountId(), account.getUsername(), account.getEmail(),
                account.isEmailVerified(), mayPost(account));
    }

    /** In strict mode a member who gave an address must have confirmed it. In compat mode nothing is asked. */
    private boolean mayPost(Account account) {
        return !mode.isStrict() || !account.isWaitingForVerification();
    }

    /**
     * Passes silently if this account may post or edit; throws ForbiddenException (403) while
     * its address is waiting to be confirmed. An id that belongs to no account passes: saying
     * "no such author" is the job of whoever is about to store the post.
     */
    public void requireMayPost(Integer accountId) {
        if (accountId == null || !mode.isStrict()) {
            return; // compat mode asks for nothing, so there is nothing to look up
        }
        Optional<Account> account = accounts.findById(accountId);
        if (account.isPresent() && account.get().isWaitingForVerification()) {
            throw new ForbiddenException();
        }
    }

    /** Makes a confirmation link for the account's address and mails it. The account must have an address. */
    public void startVerification(Account account) {
        String secret = tokens.issue(account.getAccountId(), AccountToken.VERIFY);
        mails.sendVerification(account.getEmail(), account.getUsername(), secret);
    }

    /**
     * Gives the account an address, or replaces one that was never confirmed, and mails the
     * confirmation link. 400 if the text is not an address, if another account has it, or if
     * the account's address is already confirmed: changing a confirmed address is not part of 2.4.
     */
    @Transactional
    public Profile setEmail(int accountId, String email) {
        Account account = accounts.findById(accountId).orElseThrow(NotFoundException::new);
        String address = EmailPolicy.normalise(email);
        if (address == null || account.isEmailVerified()) {
            throw new BadRequestException();
        }
        Optional<Account> holder = accounts.findByEmail(address);
        if (holder.isPresent() && !holder.get().getAccountId().equals(accountId)) {
            throw new BadRequestException();
        }
        account.setEmail(address);
        try {
            // saveAndFlush, not save: an UPDATE is normally sent to the database when the transaction
            // ends, which is after this method. Flushing sends it now, so that a refusal by the
            // unique column arrives here, before any mail is sent.
            account = accounts.saveAndFlush(account);
        } catch (DataIntegrityViolationException e) {
            // Another account took the same address at the same instant; the unique column refused this one.
            throw new BadRequestException();
        }
        startVerification(account);
        return profileOf(account);
    }

    /**
     * Mails the confirmation link again. 400 if there is nothing to confirm; 429 with
     * Retry-After if the last one was sent less than a minute ago.
     */
    @Transactional
    public void resendVerification(int accountId) {
        Account account = accounts.findById(accountId).orElseThrow(NotFoundException::new);
        if (!account.isWaitingForVerification()) {
            throw new BadRequestException();
        }
        long since = tokens.secondsSinceLastIssue(accountId, AccountToken.VERIFY);
        if (since >= 0 && since < MAIL_INTERVAL_SECONDS) {
            throw new TooManyRequestsException(MAIL_INTERVAL_SECONDS - since);
        }
        startVerification(account);
    }

    /**
     * Uses a confirmation link. Throws BadRequestException (400) if the secret is unknown, was
     * used before, or is too old.
     */
    @Transactional
    public void verify(String secret) {
        AccountToken token = tokens.redeem(secret, AccountToken.VERIFY).orElseThrow(BadRequestException::new);
        Account account = accounts.findById(token.getAccountId()).orElseThrow(BadRequestException::new);
        if (account.getEmail() == null) {
            throw new BadRequestException();
        }
        account.setEmailVerifiedAt(clockSeconds.getAsLong());
        accounts.save(account);
    }

    /**
     * "Forgot password?": mails a link if, and only if, the text is the confirmed address of
     * an account. Returns normally in every case; the caller answers the same way each time.
     *
     * An address that was never confirmed gets no link. Its owner mistyped it, perhaps, and
     * the inbox it names belongs to a stranger, who must not be handed the account.
     */
    @Transactional
    public void requestPasswordReset(String email) {
        String address = EmailPolicy.normalise(email);
        if (address == null) {
            return;
        }
        Optional<Account> found = accounts.findByEmail(address);
        if (found.isEmpty() || !found.get().isEmailVerified()) {
            return;
        }
        Account account = found.get();
        long since = tokens.secondsSinceLastIssue(account.getAccountId(), AccountToken.RESET);
        if (since >= 0 && since < MAIL_INTERVAL_SECONDS) {
            return; // one was sent a moment ago; a second click on the button does not send another
        }
        String secret = tokens.issue(account.getAccountId(), AccountToken.RESET);
        mails.sendPasswordReset(account.getEmail(), account.getUsername(), secret);
    }

    /**
     * Uses a password link: stores the new password and ends every earlier session of the
     * account. 400 if the link is not good, or if the password does not meet the rule of the
     * current mode. A refused password does not use the link up: the member can try another.
     */
    @Transactional
    public void resetPassword(String secret, String newPassword) {
        // Look first, use afterwards: the password rule needs the username, and a password that
        // is refused must leave the link usable.
        AccountToken token = tokens.find(secret, AccountToken.RESET).orElseThrow(BadRequestException::new);
        Account account = accounts.findById(token.getAccountId()).orElseThrow(BadRequestException::new);
        if (!passwordPolicy.accepts(account.getUsername(), newPassword)) {
            throw new BadRequestException();
        }
        if (tokens.redeem(secret, AccountToken.RESET).isEmpty()) {
            throw new BadRequestException(); // it stopped being good between the two looks: it expired this very second
        }
        account.setPassword(PasswordHasher.hash(newPassword));
        // Every login token issued so far is refused from now on (see JwtAuthenticationFilter).
        account.endAllSessions();
        accounts.save(account);
    }
}
