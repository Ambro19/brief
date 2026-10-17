package net.onetechly.brief.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Whether the board is open to everyone or closed to invited people.
 *
 *   open (the default):  anyone may read, and anyone may create an account.
 *   closed:              creating an account needs an invitation, and reading needs a sign-in.
 *                        The web app itself, the API documentation and the mailed links stay
 *                        public; what is protected is what members wrote and who they are.
 *
 * The setting is brief.board (environment variable BRIEF_BOARD): "open" or "closed".
 *
 * History. In 2.4 a board was closed by setting a shared invitation code (BRIEF_INVITE_CODE)
 * that every newcomer had to type. In 2.5 newcomers are invited by link instead, and the code
 * is no longer asked for anywhere. A server whose environment still holds BRIEF_INVITE_CODE and
 * no BRIEF_BOARD is treated as closed, so that the upgrade can never open a board by accident;
 * the start-up log then asks for BRIEF_BOARD=closed in its place.
 *
 * Closed mode builds on login tokens, so it needs strict mode. Closed together with compat mode
 * stops the application at start-up: starting anyway would look closed and be open.
 */
@Component
public class BoardAccess {

    private final boolean closed;
    private final boolean closedByOldCode;

    public BoardAccess(AuthMode mode,
                       @Value("${brief.board:}") String board,
                       @Value("${brief.invite-code:}") String oldInviteCode) {
        String setting = board == null ? "" : board.trim().toLowerCase(Locale.ROOT);
        boolean oldCodeSet = oldInviteCode != null && !oldInviteCode.isBlank();
        switch (setting) {
            case "closed" -> { closed = true; closedByOldCode = false; }
            case "open" -> { closed = false; closedByOldCode = false; }
            case "" -> { closed = oldCodeSet; closedByOldCode = oldCodeSet; }
            default -> throw new IllegalStateException("BRIEF_BOARD (brief.board) must be \"open\" or \"closed\"");
        }
        if (closed && !mode.isStrict()) {
            throw new IllegalStateException("The board is closed, but the application runs in compat mode, where nobody "
                    + "has to sign in. A closed board needs BRIEF_AUTH_MODE=strict.");
        }
    }

    public boolean isClosed() {
        return closed;
    }

    /** True if the board is closed only because the 2.4 setting BRIEF_INVITE_CODE is still there. */
    public boolean isClosedByOldCode() {
        return closedByOldCode;
    }

    /** "closed" or "open". The web app reads this from GET /health. */
    public String name() {
        return closed ? "closed" : "open";
    }
}
