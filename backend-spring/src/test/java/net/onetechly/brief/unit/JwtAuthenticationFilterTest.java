package net.onetechly.brief.unit;

import net.onetechly.brief.entity.Account;
import net.onetechly.brief.repository.AccountRepository;
import net.onetechly.brief.security.JwtAuthenticationFilter;
import net.onetechly.brief.security.JwtService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Runs the filter on its own with Spring's stand-in request and response objects
 * (MockHttpServletRequest and friends): no server is started. The account table is played by
 * a Mockito mock of the repository.
 */
class JwtAuthenticationFilterTest {

    private final AtomicLong now = new AtomicLong(1_000_000);
    private final JwtService jwt = new JwtService(
            "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8), 600, now::get);
    private final AccountRepository accounts = mock(AccountRepository.class);
    private final JwtAuthenticationFilter filter = new JwtAuthenticationFilter(jwt, accounts);
    /** Account 7 as the database holds it. A test changes this object to change what the filter finds. */
    private final Account seven = new Account("seven", "hash");

    @BeforeEach
    void accountSevenExists() {
        seven.setAccountId(7);
        when(accounts.findById(7)).thenReturn(Optional.of(seven));
    }

    /** Sends one request with this Authorization header through the filter; returns the chain it was handed on to. */
    private MockFilterChain passThroughFilter(String authorizationHeader) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/messages");
        if (authorizationHeader != null) {
            request.addHeader("Authorization", authorizationHeader);
        }
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, new MockHttpServletResponse(), chain);
        return chain;
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void validTokenSignsTheCallerInForThisRequest() throws Exception {
        MockFilterChain chain = passThroughFilter("Bearer " + jwt.issue(7));

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        assertNotNull(authentication);
        assertTrue(authentication.isAuthenticated());
        assertEquals(Integer.valueOf(7), authentication.getPrincipal());
        assertNotNull(chain.getRequest(), "the request must be handed on to the rest of the chain");
    }

    @Test
    void missingTokenLeavesTheCallerAnonymousButStillHandsTheRequestOn() throws Exception {
        MockFilterChain chain = passThroughFilter(null);

        assertNull(SecurityContextHolder.getContext().getAuthentication());
        assertNotNull(chain.getRequest());
    }

    @Test
    void badTokenLeavesTheCallerAnonymousButStillHandsTheRequestOn() throws Exception {
        MockFilterChain chain = passThroughFilter("Bearer made-up-token");

        assertNull(SecurityContextHolder.getContext().getAuthentication());
        assertNotNull(chain.getRequest());
    }

    @Test
    void signedOutTokenNoLongerSignsTheCallerIn() throws Exception {
        String token = jwt.issue(7);
        jwt.revoke(token);

        passThroughFilter("Bearer " + token);

        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    // ---- the look at the account row (2.4) ----

    @Test
    void aTokenIssuedBeforeThePasswordWasResetNoLongerSignsTheCallerIn() throws Exception {
        String before = jwt.issue(7, seven.getTokenVersion());
        seven.endAllSessions();                // the password is replaced: in the same second, as it happens

        passThroughFilter("Bearer " + before);
        assertNull(SecurityContextHolder.getContext().getAuthentication(), "the session from before the reset is over");

        passThroughFilter("Bearer " + jwt.issue(7, seven.getTokenVersion()));
        assertNotNull(SecurityContextHolder.getContext().getAuthentication(), "signing in again after the reset works, at once");
    }

    @Test
    void aTokenFromBeforeThisReleaseWorksUntilThePasswordIsReplaced() throws Exception {
        String from23 = jwt.issue(7);          // 2.3 knew no token generation: such a token counts as generation 0
        passThroughFilter("Bearer " + from23);
        assertNotNull(SecurityContextHolder.getContext().getAuthentication(), "members stay signed in across the upgrade");

        SecurityContextHolder.clearContext();
        seven.endAllSessions();
        passThroughFilter("Bearer " + from23);
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void aTokenCannotClaimAGenerationThatHasNotComeYet() throws Exception {
        passThroughFilter("Bearer " + jwt.issue(7, 5));
        assertNull(SecurityContextHolder.getContext().getAuthentication(), "only the current number is accepted, not any number from it upwards");
    }

    @Test
    void aTokenOfAnAccountThatNoLongerExistsDoesNotSignAnyoneIn() throws Exception {
        when(accounts.findById(8)).thenReturn(Optional.empty());

        MockFilterChain chain = passThroughFilter("Bearer " + jwt.issue(8));

        assertNull(SecurityContextHolder.getContext().getAuthentication());
        assertNotNull(chain.getRequest(), "the request is still handed on, as an anonymous one");
    }

    @Test
    void withoutAValidTokenTheAccountTableIsNotAskedAtAll() throws Exception {
        passThroughFilter(null);
        passThroughFilter("Bearer made-up-token");
        passThroughFilter("Basic dXNlcjpwYXNz");

        verify(accounts, never()).findById(anyInt());
    }
}
