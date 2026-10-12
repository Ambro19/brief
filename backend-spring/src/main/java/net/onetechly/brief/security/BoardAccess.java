package net.onetechly.brief.security;

import net.onetechly.brief.util.PasswordHasher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Whether the board is open to everyone or closed to invited people (2.4).
 *
 *   open (the default):  anyone may read, and anyone may create an account.
 *   closed:              creating an account needs the invitation code, and reading needs a
 *                        sign-in. The web app itself and the API documentation stay public;
 *                        what is protected is what members wrote and who they are.
 *
 * The board is closed when the property brief.invite-code (environment variable
 * BRIEF_INVITE_CODE) holds a code. The code is a shared secret: whoever has it can join. Give
 * it to the people you invite, and change it on the server to stop new sign-ups with the old one.
 *
 * Closed mode builds on login tokens, so it needs strict mode. A code together with compat
 * mode stops the application at start-up: starting anyway would look closed and be open.
 */
@Component
public class BoardAccess {

    /** A shorter code could be found by trying, even with the attempt limit in front of it. */
    public static final int MIN_CODE_LENGTH = 8;

    /** The code, or null when the board is open. */
    private final String inviteCode;

    public BoardAccess(AuthMode mode, @Value("${brief.invite-code:}") String inviteCode) {
        String code = inviteCode == null ? "" : inviteCode.trim();
        if (code.isEmpty()) {
            this.inviteCode = null;
            return;
        }
        if (!mode.isStrict()) {
            throw new IllegalStateException("BRIEF_INVITE_CODE (brief.invite-code) is set, but the application runs in "
                    + "compat mode, where nobody has to sign in. A closed board needs BRIEF_AUTH_MODE=strict.");
        }
        if (code.length() < MIN_CODE_LENGTH) {
            throw new IllegalStateException("BRIEF_INVITE_CODE (brief.invite-code) must be at least "
                    + MIN_CODE_LENGTH + " characters long");
        }
        this.inviteCode = code;
    }

    public boolean isClosed() {
        return inviteCode != null;
    }

    /** "closed" or "open". The web app reads this from GET /health. */
    public String name() {
        return isClosed() ? "closed" : "open";
    }

    /**
     * True if this caller may create an account: always on an open board, and on a closed one
     * only with the right code. The comparison takes the same time wherever the first wrong
     * character is, so the response time does not reveal how much of a guess was right.
     */
    public boolean admits(String offeredCode) {
        if (inviteCode == null) {
            return true;
        }
        return offeredCode != null && PasswordHasher.constantTimeEquals(inviteCode, offeredCode.trim());
    }
}
