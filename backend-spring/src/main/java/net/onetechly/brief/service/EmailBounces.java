package net.onetechly.brief.service;

import net.onetechly.brief.entity.EmailBounce;
import net.onetechly.brief.exception.UndeliverableAddressException;
import net.onetechly.brief.repository.EmailBounceRepository;
import net.onetechly.brief.security.EmailPolicy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Addresses that cannot receive mail (2.7).
 *
 * Until 2.6 Brief! handed a mail to Resend and assumed it arrived. When a member mistyped their
 * address (lovepetschoice for lovepetschoices, October 21), Gmail refused the mail, Resend
 * recorded a bounce, and Brief! kept saying "Check your email" for a mail that would never come.
 * Worse, Resend then blocks every later mail to that address, so "Send the link again" could not
 * help either.
 *
 * Now Resend reports such events to Brief! (controller/ResendWebhookController.java), and this
 * class remembers the address:
 *   - for 30 days the member's own page says the mail could not be delivered, and the owner
 *     sees "Bounced" next to an invitation to it (isBounced);
 *   - for one day Brief! refuses to mail it again, 422 (allowSending). After that a member who is
 *     sure the address is right can try once more: the report is cleared, the mail is sent, and
 *     if it bounces again Resend says so again within seconds. Without that, a mistaken report
 *     (a suppression caused by another sender on the same Resend account, say) would lock a
 *     member out for a month, with nobody able to undo it (independent review of 2.7.0).
 *
 * Reports are deleted 30 days after they were made (purgeOld, at most once an hour), and at once
 * when the address is confirmed after all or its account is deleted.
 */
@Service
public class EmailBounces {

    /** How long a report is kept and shown: the figure in the Privacy notice. */
    public static final long KEEP_SECONDS = 30L * 24 * 60 * 60;
    /** How long Brief! refuses to mail a reported address. */
    public static final long BLOCK_SECONDS = 24L * 60 * 60;
    /** Old reports are looked for at most this often. */
    static final long PURGE_INTERVAL_SECONDS = 60L * 60;

    private static final int REASON_LENGTH = 300;

    private final EmailBounceRepository bounces;
    private final LongSupplier clockSeconds;
    private final AtomicLong lastPurge = new AtomicLong(Long.MIN_VALUE / 2);

    @Autowired
    public EmailBounces(EmailBounceRepository bounces) {
        this(bounces, () -> System.currentTimeMillis() / 1000);
    }

    public EmailBounces(EmailBounceRepository bounces, LongSupplier clockSeconds) {
        this.bounces = bounces;
        this.clockSeconds = clockSeconds;
    }

    /** The report about this address, if one was made in the last 30 days. */
    private Optional<EmailBounce> recent(String address, long withinSeconds) {
        String key = EmailPolicy.normalise(address);
        if (key == null) {
            return Optional.empty();
        }
        long since = clockSeconds.getAsLong() - withinSeconds;
        return bounces.findByAddress(key).filter(found -> found.getBouncedAt() >= since);
    }

    /** True if mail to this address bounced in the last 30 days and nobody has tried it since. */
    public boolean isBounced(String address) {
        return recent(address, KEEP_SECONDS).isPresent();
    }

    /** True if mail to this address bounced in the last day: no mail goes there now. */
    public boolean isBlocked(String address) {
        return recent(address, BLOCK_SECONDS).isPresent();
    }

    /**
     * Before mailing an address on a member's request: 422 if it bounced in the last day. An older
     * report is cleared, and the mail goes out: a second chance for an address that is right after all.
     */
    @Transactional
    public void allowSending(String address) {
        if (isBlocked(address)) {
            throw new UndeliverableAddressException();
        }
        if (isBounced(address)) {
            forget(address);
        }
    }

    /**
     * Records a report. A second report about the same address updates the first. Two reports
     * about one new address arriving at the same instant may both try to insert: the unique index
     * keeps the first, the second is answered 409, and Resend sends it again a few seconds later,
     * when it becomes an update.
     */
    @Transactional
    public void record(String address, String kind, String reason) {
        String key = EmailPolicy.normalise(address);
        if (key == null) {
            return;
        }
        long now = clockSeconds.getAsLong();
        String shortReason = reason == null ? null : reason.length() <= REASON_LENGTH ? reason : reason.substring(0, REASON_LENGTH);
        Optional<EmailBounce> found = bounces.findByAddress(key);
        if (found.isPresent()) {
            found.get().update(kind, shortReason, now);
            bounces.save(found.get());
        } else {
            bounces.save(new EmailBounce(key, kind, shortReason, now));
        }
    }

    /** Forgets an address: it was confirmed after all, its account is gone, or it is being tried again. */
    @Transactional
    public void forget(String address) {
        String key = EmailPolicy.normalise(address);
        if (key != null) {
            bounces.deleteByAddress(key);
        }
    }

    /**
     * Deletes reports older than 30 days, at most once an hour. Called from busy places (the
     * member's own page, the owner's list, every report), so that the Privacy notice's "deleted
     * after 30 days" holds even when no new report arrives.
     */
    @Transactional
    public void purgeOld() {
        long now = clockSeconds.getAsLong();
        long last = lastPurge.get();
        if (now - last < PURGE_INTERVAL_SECONDS || !lastPurge.compareAndSet(last, now)) {
            return;
        }
        bounces.deleteByBouncedAtLessThan(now - KEEP_SECONDS);
    }
}
