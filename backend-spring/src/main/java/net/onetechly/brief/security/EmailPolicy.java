package net.onetechly.brief.security;

import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * What counts as an email address, and whether a new account must give one (2.4).
 *
 *   strict mode: a new account must give an address.
 *   compat mode: the original training contract knows no email, so none is asked for.
 *                One that is given anyway is stored if it is well formed.
 *
 * The shape check is deliberately loose: something, an @, something, a dot, something, and no
 * spaces. Whether the address really exists and belongs to the member cannot be seen from
 * its text. That is what the confirmation link is for.
 */
@Component
public class EmailPolicy {

    /** The longest address the mail standards allow in practice, and the width of the column. */
    public static final int MAX_LENGTH = 254;

    private final boolean required;

    public EmailPolicy(AuthMode mode) {
        this.required = mode.isStrict();
    }

    /** True if a new account must give an address. */
    public boolean isRequired() {
        return required;
    }

    /** "required" or "optional". The web app reads this from GET /health. */
    public String name() {
        return required ? "required" : "optional";
    }

    /**
     * The form in which an address is stored and compared: without spaces around it, in lower
     * case. Returns null if the text is empty or does not have the shape of an address.
     * Locale.ROOT makes "lower case" mean the same thing on every computer.
     */
    public static String normalise(String email) {
        if (email == null) {
            return null;
        }
        String text = email.trim().toLowerCase(Locale.ROOT);
        return looksLikeAnAddress(text) ? text : null;
    }

    static boolean looksLikeAnAddress(String text) {
        if (text.isEmpty() || text.length() > MAX_LENGTH) {
            return false;
        }
        int at = text.indexOf('@');
        // exactly one @, with something before it
        if (at < 1 || at != text.lastIndexOf('@')) {
            return false;
        }
        String domain = text.substring(at + 1);
        int dot = domain.lastIndexOf('.');
        // a dot inside the domain, with something on both sides of it: "a.b", not ".b" or "a."
        if (dot < 1 || dot == domain.length() - 1 || domain.startsWith(".") || domain.contains("..")) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            // No spaces, no control characters (which includes the character with code 0 that
            // PostgreSQL cannot store), and none of the characters that would let an address
            // break out of a mail header or of the text of a page.
            if (c <= ' ' || c == 127 || c == '<' || c == '>' || c == ',' || c == ';' || c == '"' || c == '\\') {
                return false;
            }
        }
        return true;
    }
}
