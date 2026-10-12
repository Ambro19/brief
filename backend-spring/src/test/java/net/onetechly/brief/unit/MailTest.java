package net.onetechly.brief.unit;

import net.onetechly.brief.config.MailConfig;
import net.onetechly.brief.mail.AccountMails;
import net.onetechly.brief.mail.LogMailer;
import net.onetechly.brief.mail.Mail;
import net.onetechly.brief.mail.Mailer;
import net.onetechly.brief.mail.ResendMailer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Which mailer the application gets, what the log mailer remembers, and the text of the two mails. */
class MailTest {

    /** A mailer that only collects, so a test can read what would have been sent. */
    private static final class Collecting implements Mailer {
        final List<Mail> sent = new ArrayList<>();

        @Override
        public void send(Mail mail) {
            sent.add(mail);
        }

        @Override
        public String description() {
            return "collected by a test";
        }
    }

    @Test
    void withoutAnApiKeyMailIsWrittenToTheLog() {
        MailConfig config = new MailConfig();
        assertInstanceOf(LogMailer.class, config.mailer("", "", ""));
        assertInstanceOf(LogMailer.class, config.mailer("   ", "Brief! <hello@example.test>", ""));
        assertInstanceOf(LogMailer.class, config.mailer(null, null, null));
    }

    @Test
    void withAnApiKeyMailGoesThroughResendAndASenderIsRequired() {
        MailConfig config = new MailConfig();
        Mailer mailer = config.mailer("re_test_key", "Brief! <hello@example.test>", "");
        assertInstanceOf(ResendMailer.class, mailer);
        assertTrue(mailer.description().contains("hello@example.test"));
        assertFalse(mailer.description().contains("re_test_key"), "the key is never part of anything that is logged");

        IllegalStateException refused = assertThrows(IllegalStateException.class, () -> config.mailer("re_test_key", "  ", ""));
        assertTrue(refused.getMessage().contains("BRIEF_MAIL_FROM"), refused.getMessage());
    }

    @Test
    void theLogMailerRemembersTheLastMessagesAndNoMore() {
        LogMailer mailer = new LogMailer();
        assertNull(mailer.lastTo("maya@example.test"));
        for (int i = 1; i <= 60; i++) {
            mailer.send(new Mail(i % 2 == 0 ? "maya@example.test" : "gil@example.test", "subject " + i, "text " + i));
        }
        assertEquals(50, mailer.recent().size(), "only the newest 50 are kept");
        assertEquals("subject 11", mailer.recent().get(0).subject(), "the oldest ten were dropped");
        assertEquals("subject 60", mailer.lastTo("MAYA@example.test").subject());
        assertEquals("subject 59", mailer.lastTo("gil@example.test").subject());
        mailer.clear();
        assertTrue(mailer.recent().isEmpty());
    }

    @Test
    void theConfirmationMailCarriesALinkToTheWebApp() {
        Collecting mailer = new Collecting();
        new AccountMails(mailer, "https://briefmessage.net/").sendVerification("maya@example.test", "Maya_zo", "SECRET123");

        Mail mail = mailer.sent.get(0);
        assertEquals("maya@example.test", mail.to());
        assertTrue(mail.subject().contains("Confirm"));
        assertTrue(mail.text().contains("\nhttps://briefmessage.net/?verify=SECRET123\n"),
                "the link stands on a line of its own, with one slash after the host: " + mail.text());
        assertTrue(mail.text().contains("Maya_zo"));
        assertFalse(mail.text().contains("reset="));
    }

    @Test
    void thePasswordMailCarriesADifferentKindOfLink() {
        Collecting mailer = new Collecting();
        new AccountMails(mailer, "https://briefmessage.net").sendPasswordReset("maya@example.test", "Maya_zo", "SECRET456");

        Mail mail = mailer.sent.get(0);
        assertTrue(mail.subject().contains("password"));
        assertTrue(mail.text().contains("\nhttps://briefmessage.net/?reset=SECRET456\n"), mail.text());
        assertFalse(mail.text().contains("verify="));
    }

    @Test
    void withNoPublicAddressConfiguredTheLinksPointAtThisMachine() {
        assertEquals("http://localhost:8080", new AccountMails(new Collecting(), "").publicUrl());
        assertEquals("http://localhost:8080", new AccountMails(new Collecting(), null).publicUrl());
        assertEquals("https://briefmessage.net", new AccountMails(new Collecting(), " https://briefmessage.net/ ").publicUrl());
    }
}
