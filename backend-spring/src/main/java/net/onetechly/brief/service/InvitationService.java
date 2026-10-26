package net.onetechly.brief.service;

import net.onetechly.brief.dto.InvitationInfo;
import net.onetechly.brief.dto.InvitationLink;
import net.onetechly.brief.dto.InvitationView;
import net.onetechly.brief.entity.Account;
import net.onetechly.brief.entity.Invitation;
import net.onetechly.brief.exception.BadRequestException;
import net.onetechly.brief.exception.ForbiddenException;
import net.onetechly.brief.exception.NotFoundException;
import net.onetechly.brief.exception.TooManyRequestsException;
import net.onetechly.brief.mail.AccountMails;
import net.onetechly.brief.repository.AccountRepository;
import net.onetechly.brief.repository.InvitationRepository;
import net.onetechly.brief.security.BoardOwner;
import net.onetechly.brief.security.EmailPolicy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.function.LongSupplier;

/**
 * Invitations (2.5): how a new member gets onto a closed board.
 *
 *   1. The board owner enters an address. Brief! mails it a link:
 *          https://briefmessage.net/?invite=<secret>
 *      Or the owner asks for a link without an address, and passes it on in another way.
 *   2. The invitee opens the link. The web app shows who sent it and a form to create an account.
 *   3. Creating the account uses the invitation up. A mailed invitation also proves that the
 *      invitee can read that inbox, so the new account starts with that address confirmed.
 *
 * As with the links of AccountTokens, the database keeps only a SHA-256 hash of the secret.
 * An invitation works once and for a limited time (brief.invitation-days, 7 by default), and
 * its sender can withdraw it while it is unused.
 */
@Service
public class InvitationService {

    /** At most this many invitations per sender in 24 hours: a stolen owner account cannot become a spam cannon. */
    public static final int DAILY_LIMIT = 50;

    /**
     * An unused invitation is deleted this long after it expired (review of 2.5.0): it holds the
     * address of someone who never joined. Until then the owner still sees it as "expired".
     */
    public static final long KEEP_EXPIRED_SECONDS = 30L * 24 * 60 * 60;

    private static final int SECRET_BYTES = 32;
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    private final InvitationRepository invitations;
    private final AccountRepository accounts;
    private final AccountMails mails;
    private final BoardOwner owner;
    private final EmailBounces bounces;
    private final long lifetimeSeconds;
    private final LongSupplier clockSeconds;
    private final SecureRandom random = new SecureRandom();

    /** The constructor Spring uses. */
    @Autowired
    public InvitationService(InvitationRepository invitations, AccountRepository accounts, AccountMails mails,
                             BoardOwner owner, EmailBounces bounces, @Value("${brief.invitation-days:7}") int days) {
        this(invitations, accounts, mails, owner, bounces, days, () -> System.currentTimeMillis() / 1000);
    }

    /** The clock is injectable so tests can move time forward without sleeping. */
    public InvitationService(InvitationRepository invitations, AccountRepository accounts, AccountMails mails,
                             BoardOwner owner, EmailBounces bounces, int days, LongSupplier clockSeconds) {
        this.invitations = invitations;
        this.accounts = accounts;
        this.mails = mails;
        this.owner = owner;
        this.bounces = bounces;
        this.lifetimeSeconds = (long) Math.max(1, days) * 24 * 60 * 60;
        this.clockSeconds = clockSeconds;
    }

    public long now() {
        return clockSeconds.getAsLong();
    }

    /** How many whole days an invitation is good for. */
    public long lifetimeDays() {
        return lifetimeSeconds / (24 * 60 * 60);
    }

    // ------------------------------------------------------------------ for the sender

    /**
     * Mails an invitation to this address. 403 if the caller is not the board owner; 400 if the
     * text is not an address or the address already belongs to a member; 429 after the daily
     * limit. An earlier unused invitation to the same address stops working: only the newest
     * mail in that inbox does.
     */
    @Transactional
    public InvitationView invite(int callerId, String email) {
        Account sender = requireOwner(callerId);
        forgetLongExpired();
        String address = EmailPolicy.normalise(email);
        if (address == null) {
            throw new BadRequestException();
        }
        if (accounts.findByEmail(address).isPresent()) {
            throw new BadRequestException(); // already a member
        }
        bounces.allowSending(address); // 422 (2.7): it bounced in the last day; most likely a typo
        requireUnderDailyLimit(callerId);
        List<Invitation> earlier = invitations.findByEmailAndUsedAtIsNull(address);
        if (!earlier.isEmpty()) {
            invitations.deleteAll(earlier);
        }
        String secret = newSecret();
        Invitation saved = invitations.save(new Invitation(callerId, address, AccountTokens.hash(secret), now(), now() + lifetimeSeconds));
        mails.sendInvitation(address, sender.getUsername(), secret, lifetimeDays());
        return view(saved);
    }

    /** Makes an invitation that is not mailed, and returns its link. The link is shown this once only. */
    @Transactional
    public InvitationLink createLink(int callerId) {
        requireOwner(callerId);
        forgetLongExpired();
        requireUnderDailyLimit(callerId);
        String secret = newSecret();
        Invitation saved = invitations.save(new Invitation(callerId, null, AccountTokens.hash(secret), now(), now() + lifetimeSeconds));
        return new InvitationLink(mails.invitationLink(secret), view(saved));
    }

    /**
     * Everything the caller sent, newest first. {@code @Transactional} because it first deletes
     * the invitations that expired long ago, and a delete needs a transaction.
     */
    @Transactional
    public List<InvitationView> list(int callerId) {
        requireOwner(callerId);
        forgetLongExpired();
        bounces.purgeOld();
        List<InvitationView> out = new ArrayList<>();
        for (Invitation invitation : invitations.findByInviterIdOrderByInvitationIdDesc(callerId)) {
            out.add(view(invitation));
        }
        return out;
    }

    /** Withdraws an unused invitation: its link stops working. 404 if it is not the caller's; 400 if it was used. */
    @Transactional
    public void withdraw(int callerId, int invitationId) {
        requireOwner(callerId);
        Invitation invitation = invitations.findById(invitationId)
                .filter(found -> found.getInviterId().equals(callerId))
                .orElseThrow(NotFoundException::new);
        if (invitation.isUsed()) {
            throw new BadRequestException();
        }
        invitations.delete(invitation);
    }

    // ------------------------------------------------------------------ for the invitee

    /**
     * The invitation this secret belongs to, if it can still be used: unused, not expired, and
     * sent by the person who runs the board now. When BRIEF_OWNER_USERNAME changes, the links the
     * former owner handed out stop working (review of 2.5.0): the new owner could not see or
     * withdraw them.
     */
    public Optional<Invitation> usable(String secret) {
        if (secret == null || secret.isBlank() || secret.length() > 200) {
            return Optional.empty();
        }
        return invitations.findByTokenHash(AccountTokens.hash(secret.trim()))
                .filter(found -> found.isUsableAt(now()))
                .filter(found -> accounts.findById(found.getInviterId()).filter(owner::isOwner).isPresent());
    }

    /** What an invitation link may tell its holder. Empty if the link is unknown, used or expired. */
    public Optional<InvitationInfo> lookup(String secret) {
        return usable(secret).map(invitation -> new InvitationInfo(
                accounts.findById(invitation.getInviterId()).map(Account::getUsername).orElse("A member"),
                invitation.getEmail(), invitation.getExpiresAt()));
    }

    /**
     * Records that an account was created with this invitation. If another registration used it
     * in the meantime, even one running at this very moment, this one is refused (403), and the
     * transaction it runs in takes the new account back out.
     *
     * Until the review of 2.5.0 this read the row, checked it and saved it. Inside a transaction
     * that "read" came from Hibernate's memory, not the database, so two registrations at the same
     * moment could both pass. The check and the change are now one UPDATE: see
     * InvitationRepository.claim.
     */
    public void markUsed(Invitation invitation, int accountId) {
        if (invitations.claim(invitation.getInvitationId(), accountId, now()) != 1) {
            throw new ForbiddenException();
        }
        // The Invitation object passed in is left as it was: the UPDATE went straight to the row,
        // and changing the object too would only make Hibernate write the same values twice.
    }

    // ------------------------------------------------------------------ helpers

    private Account requireOwner(int callerId) {
        Account caller = accounts.findById(callerId).orElseThrow(ForbiddenException::new);
        if (!owner.isOwner(caller)) {
            throw new ForbiddenException(); // in 2.5 only the board owner invites
        }
        return caller;
    }

    /** Deletes unused invitations that expired more than KEEP_EXPIRED_SECONDS ago. Runs whenever the owner sends or lists. */
    private void forgetLongExpired() {
        invitations.deleteByUsedAtIsNullAndExpiresAtLessThan(now() - KEEP_EXPIRED_SECONDS);
    }

    private void requireUnderDailyLimit(int callerId) {
        if (invitations.countByInviterIdAndCreatedAtGreaterThan(callerId, now() - 24 * 60 * 60) >= DAILY_LIMIT) {
            throw new TooManyRequestsException(60 * 60);
        }
    }

    private String newSecret() {
        byte[] bytes = new byte[SECRET_BYTES];
        random.nextBytes(bytes);
        return ENCODER.encodeToString(bytes);
    }

    /**
     * How the owner's list describes an invitation:
     *   used       an account was created with it
     *   expired    seven days passed
     *   bounced    (2.7) the mail could not be delivered: the address is probably mistyped
     *   taken      (2.7) the address is now the confirmed address of a member, so it can no longer create an account
     *   pending    waiting to be opened
     */
    private InvitationView view(Invitation invitation) {
        String email = invitation.getEmail();
        String status = invitation.isUsed() ? "used"
                : !invitation.isUsableAt(now()) ? "expired"
                : email != null && bounces.isBounced(email) ? "bounced"
                : email != null && accounts.findByEmail(email).filter(Account::isEmailVerified).isPresent() ? "taken"
                : "pending";
        String joinedAs = invitation.getUsedBy() == null ? null
                : accounts.findById(invitation.getUsedBy()).map(Account::getUsername).orElse(null);
        return new InvitationView(invitation.getInvitationId(), invitation.getEmail(), invitation.getCreatedAt(),
                invitation.getExpiresAt(), status, joinedAs);
    }
}
