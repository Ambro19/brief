package net.onetechly.brief.security;

import org.springframework.stereotype.Component;

/**
 * The rule a NEW password must meet. It is checked at registration only: members who
 * already have an account keep signing in with the password they have.
 *
 *   compat mode ("basic"):  no extra rule. Bean Validation on Credentials already
 *                           requires 4 characters, which is the original contract.
 *   strict mode ("strong"): at least 8 characters, with at least one letter and one
 *                           digit, and not the same as the username.
 */
@Component
public class PasswordPolicy {

    public static final int STRONG_MIN_LENGTH = 8;

    private final boolean strong;

    public PasswordPolicy(AuthMode mode) {
        this.strong = mode.isStrict();
    }

    /** "strong" or "basic". The web app reads this from GET /health to show the right hint. */
    public String name() {
        return strong ? "strong" : "basic";
    }

    public boolean accepts(String username, String password) {
        if (!strong) {
            return true;
        }
        if (password == null || password.length() < STRONG_MIN_LENGTH) {
            return false;
        }
        if (password.equalsIgnoreCase(username)) {
            return false;
        }
        boolean hasLetter = false;
        boolean hasDigit = false;
        for (int i = 0; i < password.length(); i++) {
            char c = password.charAt(i);
            if (Character.isLetter(c)) {
                hasLetter = true;
            } else if (Character.isDigit(c)) {
                hasDigit = true;
            }
        }
        return hasLetter && hasDigit;
    }
}
