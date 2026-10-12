package net.onetechly.brief.mail;

/**
 * Sends an email. An interface, because there are two ways to do it and the rest of the
 * application should not care which one is in use:
 *
 *   ResendMailer  hands the message to the Resend service over HTTPS. Used when an API key is set.
 *   LogMailer     writes the message to the log instead. Used when no key is set: on your own
 *                 machine, and in the tests.
 *
 * config/MailConfig decides which of the two the application gets.
 */
public interface Mailer {

    /**
     * Sends the message, or hands it over to be sent. Never throws because of the mail
     * service: a mail that could not be sent is logged, and the member can ask for another.
     */
    void send(Mail mail);

    /** A few words for the start-up log, for example "Resend" or "written to the log". */
    String description();
}
