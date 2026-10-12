package net.onetechly.brief.config;

import net.onetechly.brief.mail.AccountMails;
import net.onetechly.brief.mail.LogMailer;
import net.onetechly.brief.mail.Mailer;
import net.onetechly.brief.security.BoardAccess;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Writes a few lines to the log when the application has started: which database it is using
 * (2.3), whether the board is open or closed, and how mail is sent (2.4).
 *
 * On your own machine that is the H2 file, and all is well. On a hosting platform the same
 * line is the quickest way to notice a mistake that otherwise shows no symptom: if the
 * platform's DATABASE_URL did not reach the application, it starts happily on an H2 file
 * inside the container, works, and loses every account and post at the next deploy.
 *
 * The same reasoning holds for the two lines added in 2.4. A board that was meant to be closed
 * and is open, or mail that is written to the log instead of being sent, are both mistakes
 * that show no error. One look at the log after a deploy settles all three.
 */
@Component
public class StartupReport implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StartupReport.class);

    private final String datasourceUrl;
    private final BoardAccess board;
    private final Mailer mailer;
    private final AccountMails mails;

    public StartupReport(@Value("${spring.datasource.url}") String datasourceUrl,
                         BoardAccess board, Mailer mailer, AccountMails mails) {
        this.datasourceUrl = datasourceUrl;
        this.board = board;
        this.mailer = mailer;
        this.mails = mails;
    }

    @Override
    public void run(ApplicationArguments args) {
        // Render sets the variable RENDER=true inside every service it runs.
        boolean onRender = "true".equalsIgnoreCase(System.getenv("RENDER"));
        String line = describe(datasourceUrl);
        if (onRender && isLocalFile(datasourceUrl)) {
            log.warn("{} THIS IS A HOSTING PLATFORM: the file is inside the container and is erased at every deploy. "
                    + "Set DATABASE_URL (render.yaml does it) so the data is kept in PostgreSQL.", line);
        } else {
            log.info(line);
        }
        log.info(describeBoard(board.isClosed()));
        String mailLine = "Mail: " + mailer.description() + ". Links in mails point to " + mails.publicUrl() + ".";
        if (onRender && mailer instanceof LogMailer) {
            log.warn("{} THIS IS A HOSTING PLATFORM: members will not receive their confirmation or password "
                    + "links. Set BRIEF_RESEND_API_KEY and BRIEF_MAIL_FROM in the service's environment.", mailLine);
        } else {
            log.info(mailLine);
        }
    }

    /** A sentence saying who can use the board. */
    public static String describeBoard(boolean closed) {
        return closed
                ? "Board: closed. Creating an account needs the invitation code; reading needs a sign-in."
                : "Board: open. Anyone can read, and anyone can create an account.";
    }

    /**
     * A sentence naming the database, safe to log: anything after "?" is cut off, because that
     * is where a JDBC address can carry a user name or password.
     */
    public static String describe(String jdbcUrl) {
        String url = jdbcUrl == null ? "" : jdbcUrl;
        int options = url.indexOf('?');
        String shown = options < 0 ? url : url.substring(0, options);
        if (url.startsWith("jdbc:postgresql:")) {
            return "Database: PostgreSQL, " + shown.substring("jdbc:postgresql:".length()) + ".";
        }
        if (url.startsWith("jdbc:h2:mem:")) {
            return "Database: H2 in memory. Everything is forgotten when the application stops.";
        }
        if (isLocalFile(url)) {
            return "Database: H2 file " + shown.substring("jdbc:h2:file:".length()) + " on this machine.";
        }
        return "Database: " + shown + ".";
    }

    public static boolean isLocalFile(String jdbcUrl) {
        return jdbcUrl != null && jdbcUrl.startsWith("jdbc:h2:file:");
    }
}
