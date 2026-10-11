package net.onetechly.brief.security;

import org.springframework.stereotype.Component;

/**
 * The rule a NEW username must meet (2.3). Like PasswordPolicy it is checked at registration
 * only: accounts that already exist keep their name.
 *
 *   compat mode: no extra rule. Bean Validation on Credentials already requires a name that
 *                is not blank, which is the original contract.
 *   strict mode: 3 to 30 characters, each one a letter a-z or A-Z, a digit, a dot, a dash or
 *                an underscore.
 *
 * Why the plain alphabet. A username is typed by other people to mention someone and is
 * compared without regard to capitals. Both stay simple and predictable when every name is
 * made of characters whose lower-case form is the same everywhere, and when two different
 * names can never look alike on screen (a space at the end, a Cyrillic "a").
 *
 * The web app has applied this rule since 2.2. Until 2.3 a request sent straight to the API
 * could get around it.
 */
@Component
public class UsernamePolicy {

    public static final int MIN_LENGTH = 3;
    public static final int MAX_LENGTH = 30;

    private final boolean strict;

    public UsernamePolicy(AuthMode mode) {
        this.strict = mode.isStrict();
    }

    public boolean accepts(String username) {
        if (!strict) {
            return true;
        }
        if (username == null || username.length() < MIN_LENGTH || username.length() > MAX_LENGTH) {
            return false;
        }
        for (int i = 0; i < username.length(); i++) {
            char c = username.charAt(i);
            boolean allowed = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '.' || c == '-' || c == '_';
            if (!allowed) {
                return false;
            }
        }
        return true;
    }
}
