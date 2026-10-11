package net.onetechly.brief.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The one place that knows which mode the application runs in.
 *
 *   compat: the original training contract. Anyone may write, passwords need 4 characters,
 *           logins are not rate limited. The contract tests run in this mode.
 *   strict: the default. Writing needs a login token, members act only as themselves,
 *           new passwords must be strong, and repeated failed logins are slowed down.
 *
 * The mode comes from the property brief.auth.mode (application.properties, or the
 * BRIEF_AUTH_MODE environment variable). Anything other than "compat" means strict.
 */
@Component
public class AuthMode {

    private final boolean strict;

    public AuthMode(@Value("${brief.auth.mode:strict}") String mode) {
        this.strict = !"compat".equalsIgnoreCase(mode == null ? null : mode.trim());
    }

    public boolean isStrict() {
        return strict;
    }
}
