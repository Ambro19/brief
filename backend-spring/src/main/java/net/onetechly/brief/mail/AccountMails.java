package net.onetechly.brief.mail;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Writes the two emails the application sends, and hands them to the Mailer (2.4).
 *
 * The links point at the web app, which reads the secret from the address and calls the API:
 *
 *     https://briefmessage.net/?verify=<secret>     confirm an email address
 *     https://briefmessage.net/?reset=<secret>      choose a new password
 *     https://briefmessage.net/?invite=<secret>     create an account on a closed board (2.5)
 *
 * Where the links point is configuration (brief.public-url, environment variable
 * BRIEF_PUBLIC_URL), never the Host header of the request that asked for the mail. A request
 * can claim any host name; a link built from it could lead a member to someone else's site.
 */
@Service
public class AccountMails {

    private final Mailer mailer;
    private final String publicUrl;

    public AccountMails(Mailer mailer, @Value("${brief.public-url:http://localhost:8080}") String publicUrl) {
        this.mailer = mailer;
        String url = publicUrl == null || publicUrl.isBlank() ? "http://localhost:8080" : publicUrl.trim();
        this.publicUrl = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /** The address the links point at, without a slash at the end. */
    public String publicUrl() {
        return publicUrl;
    }

    public void sendVerification(String to, String username, String secret) {
        mailer.send(new Mail(to, "Confirm your email address for Brief!",
                "Hello " + username + ",\n\n"
                        + "Please confirm that this address is yours by opening this link:\n\n"
                        + publicUrl + "/?verify=" + secret + "\n\n"
                        + "The link works once, for 24 hours. Until you have used it you can read on Brief! but not post.\n\n"
                        + "If you did not create an account on Brief!, you can ignore this message.\n"));
    }

    /** The address of an invitation link (2.5). */
    public String invitationLink(String secret) {
        return publicUrl + "/?invite=" + secret;
    }

    /** An invitation to join the board (2.5). */
    public void sendInvitation(String to, String inviterName, String secret, long days) {
        mailer.send(new Mail(to, inviterName + " invited you to Brief!",
                "Hello,\n\n"
                        + inviterName + " has invited you to Brief!, a small message board where every post fits in 255 characters.\n\n"
                        + "To create your account, open this link:\n\n"
                        + invitationLink(secret) + "\n\n"
                        + "The link works once, for " + (days == 1 ? "one day" : days + " days") + ". "
                        + "Your account will use this email address, already confirmed.\n\n"
                        + "If you do not know " + inviterName + ", you can ignore this message: nothing happens unless you open the link.\n"));
    }

    public void sendPasswordReset(String to, String username, String secret) {
        mailer.send(new Mail(to, "Choose a new password for Brief!",
                "Hello " + username + ",\n\n"
                        + "Someone asked to set a new password for your Brief! account. If that was you, open this link:\n\n"
                        + publicUrl + "/?reset=" + secret + "\n\n"
                        + "The link works once, for one hour. Setting a new password signs you out on every device.\n\n"
                        + "If it was not you, ignore this message: your password stays as it is.\n"));
    }
}
