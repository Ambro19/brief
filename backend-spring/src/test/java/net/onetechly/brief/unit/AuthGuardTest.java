package net.onetechly.brief.unit;

import net.onetechly.brief.exception.ForbiddenException;
import net.onetechly.brief.exception.UnauthorizedException;
import net.onetechly.brief.security.AuthGuard;
import net.onetechly.brief.security.AuthMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The two modes of the guard, tested without starting Spring.
 * Each test puts "who is calling" into the SecurityContext by hand - the job that
 * JwtAuthenticationFilter does in the running application.
 */
class AuthGuardTest {

    private static AuthGuard guard(String mode) {
        return new AuthGuard(new AuthMode(mode));
    }

    private static void signInAs(int accountId) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                accountId, null, AuthorityUtils.createAuthorityList("ROLE_MEMBER")));
    }

    /** The SecurityContext belongs to the thread; clear it so one test cannot leak into the next. */
    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void anythingOtherThanCompatIsStrict() {
        assertTrue(new AuthMode("strict").isStrict());
        assertTrue(new AuthMode("").isStrict());
        assertTrue(new AuthMode(null).isStrict());
        assertFalse(new AuthMode("compat").isStrict());
        assertFalse(new AuthMode(" COMPAT ").isStrict());
    }

    @Test
    void compatModeAllowsEverything() {
        AuthGuard guard = guard("compat");
        guard.requireWriteAs(1);        // nobody signed in: still fine
        signInAs(2);
        guard.requireWriteAs(1);        // signed in as someone else: still fine
    }

    @Test
    void strictModeNeedsASignedInCaller() {
        AuthGuard guard = guard("strict");
        assertNull(guard.currentAccountId());
        assertThrows(UnauthorizedException.class, () -> guard.requireWriteAs(1));
        assertThrows(UnauthorizedException.class, () -> guard.requireWriteAs(null));
    }

    @Test
    void springSecuritysAnonymousPlaceholderIsNotASignedInCaller() {
        // With no token, Spring Security fills the context with this placeholder instead of leaving it empty.
        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken(
                "key", "anonymousUser", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));
        AuthGuard guard = guard("strict");
        assertNull(guard.currentAccountId());
        assertThrows(UnauthorizedException.class, () -> guard.requireWriteAs(1));
    }

    @Test
    void strictModeLetsYouActOnlyAsYourself() {
        AuthGuard guard = guard("strict");
        signInAs(1);
        assertEquals(Integer.valueOf(1), guard.currentAccountId());
        guard.requireWriteAs(1);                                                   // yourself: fine
        guard.requireWriteAs(null);                                                // nothing to protect: fine
        assertThrows(ForbiddenException.class, () -> guard.requireWriteAs(2));    // someone else: 403
    }

    // ---- what belongs to one account alone (2.4) ----

    @Test
    void requireSelfNeedsASignedInCallerInEveryMode() {
        assertThrows(UnauthorizedException.class, () -> guard("strict").requireSelf(1));
        assertThrows(UnauthorizedException.class, () -> guard("compat").requireSelf(1),
                "compat mode lets anyone write posts, but nobody may touch another account's email address");
    }

    @Test
    void requireSelfLetsACallerActOnTheirOwnAccountOnly() {
        signInAs(1);
        assertDoesNotThrow(() -> guard("strict").requireSelf(1));
        assertThrows(ForbiddenException.class, () -> guard("strict").requireSelf(2));
        assertThrows(ForbiddenException.class, () -> guard("compat").requireSelf(2));
    }
}
