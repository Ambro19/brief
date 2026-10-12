package net.onetechly.brief.security;

import net.onetechly.brief.exception.ForbiddenException;
import net.onetechly.brief.exception.UnauthorizedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Decides whether the current request may write on behalf of an account.
 *
 *   compat mode: always yes.
 *   strict mode: the caller must be signed in (else 401), and if an owner is known
 *                the caller must be that owner (else 403).
 *
 * Division of work with Spring Security:
 *   - "Is there a valid token?" is answered before the controller runs, by
 *     JwtAuthenticationFilter and the URL rules in SecurityConfig.
 *   - "Is this YOUR message?" depends on data (who wrote message 7?), so it cannot be a
 *     URL rule. That question is answered here.
 */
@Component
public class AuthGuard {

    private final AuthMode mode;

    public AuthGuard(AuthMode mode) {
        this.mode = mode;
    }

    /**
     * The account id of the signed-in caller, or null if nobody is signed in.
     * JwtAuthenticationFilter stored it in the security context earlier in this same request.
     */
    public Integer currentAccountId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof Integer accountId) {
            return accountId;
        }
        return null; // no authentication, or Spring Security's "anonymousUser" placeholder
    }

    /**
     * For what belongs to one account alone (its email address, its profile): the caller must
     * be signed in (else 401) and must be that account (else 403). Unlike requireWriteAs this
     * holds in every mode. The original contract has no such endpoints, so there is no
     * "anyone may" behaviour to keep.
     */
    public void requireSelf(int accountId) {
        Integer callerId = currentAccountId();
        if (callerId == null) {
            throw new UnauthorizedException();
        }
        if (callerId != accountId) {
            throw new ForbiddenException();
        }
    }

    /**
     * Passes silently if the write is allowed; throws otherwise.
     *
     * @param ownerId the account the write is on behalf of, or null if there is nothing to
     *                protect (for example deleting a message that does not exist)
     */
    public void requireWriteAs(Integer ownerId) {
        if (!mode.isStrict()) {
            return;
        }
        Integer callerId = currentAccountId();
        if (callerId == null) {
            throw new UnauthorizedException();
        }
        if (ownerId != null && !ownerId.equals(callerId)) {
            throw new ForbiddenException();
        }
    }
}
