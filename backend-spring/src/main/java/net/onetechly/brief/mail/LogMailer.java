package net.onetechly.brief.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * The mailer for when no mail service is configured: every message is written to the log.
 *
 * On your own machine this is all that is needed. Create an account, look at the console, and
 * the confirmation link is there to click. Nothing has to be signed up for, and no mail can
 * go to a real person by accident.
 *
 * The last few messages are also kept in memory, so the tests can read the link a member
 * would have received.
 */
public class LogMailer implements Mailer {

    private static final Logger log = LoggerFactory.getLogger(LogMailer.class);

    /** How many messages are remembered. Older ones are dropped, so the memory used stays small. */
    static final int KEPT = 50;

    private final Deque<Mail> recent = new ArrayDeque<>();

    @Override
    public void send(Mail mail) {
        synchronized (recent) {
            recent.addLast(mail);
            if (recent.size() > KEPT) {
                recent.removeFirst();
            }
        }
        log.info("MAIL NOT SENT (no mail service is configured). It would have been:\n  To: {}\n  Subject: {}\n  {}",
                mail.to(), mail.subject(), mail.text().replace("\n", "\n  "));
    }

    @Override
    public String description() {
        return "written to the log, not sent (BRIEF_RESEND_API_KEY is not set)";
    }

    /** The remembered messages, oldest first. A copy: the caller may keep it. */
    public List<Mail> recent() {
        synchronized (recent) {
            return new ArrayList<>(recent);
        }
    }

    /** The newest remembered message to this address, or null. */
    public Mail lastTo(String address) {
        Mail found = null;
        for (Mail mail : recent()) {
            if (mail.to().equalsIgnoreCase(address)) {
                found = mail;
            }
        }
        return found;
    }

    /** Forgets the remembered messages. Used by the tests between cases. */
    public void clear() {
        synchronized (recent) {
            recent.clear();
        }
    }
}
