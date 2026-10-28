package net.onetechly.brief.unit;

import net.onetechly.brief.entity.Account;
import net.onetechly.brief.entity.AccountSession;
import net.onetechly.brief.service.Sessions;
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
    private final InMemorySessions table = new InMemorySessions();
    private final Sessions sessions = new Sessions(table.repository, accounts, 600, now::get);
    private final JwtAuthenticationFilter filter = new JwtAuthenticationFilter(jwt, accounts, sessions);
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

    // ---- the session (2.8) ----

    /** A token as POST /login issues it since 2.8: for a new session of account 7. */
    private String sessionToken(boolean stay) {
        AccountSession session = sessions.open(7, stay, "Mozilla/5.0 (Windows NT 10.0) Chrome/130.0");
        return jwt.issue(7, seven.getTokenVersion(), session.getSessionId(), sessions.tokenExpiry(session));
    }

    @Test
    void aTokenWithALiveSessionSignsTheCallerInAndCarriesTheSession() throws Exception {
        passThroughFilter("Bearer " + sessionToken(false));

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        assertNotNull(authentication);
        assertEquals(Integer.valueOf(7), authentication.getPrincipal());
        assertEquals(Integer.valueOf(1), authentication.getDetails(), "the endpoints of the current session need its id");
    }

    @Test
    void signingOutIsPermanentBecauseTheSessionRowIsGone() throws Exception {
        String token = sessionToken(false);
        sessions.end(1);
        // What used to undo a sign-out: a restart forgets everything held in memory. A new JwtService
        // and filter stand for the restarted application; the table (the database) stays.
        JwtService restarted = new JwtService("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8), 600, now::get);
        new JwtAuthenticationFilter(restarted, accounts, new Sessions(table.repository, accounts, 600, now::get))
                .doFilter(request(token), new MockHttpServletResponse(), new MockFilterChain());

        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void aSessionOfAnotherAccountDoesNotCount() throws Exception {
        sessions.open(8, false, null); // session 1 belongs to account 8
        passThroughFilter("Bearer " + jwt.issue(7, 0, 1, now.get() + 600));
        assertNull(SecurityContextHolder.getContext().getAuthentication(), "a token for 7 naming 8's session is refused");
    }

    @Test
    void aShortSessionEndsAfterItsTwelveHoursAndAStaySessionIsRenewedByUse() throws Exception {
        String brief = sessionToken(false);       // session 1, 600 seconds here
        String stay = sessionToken(true);         // session 2, 30 days from the last use

        now.addAndGet(601);
        passThroughFilter("Bearer " + brief);
        assertNull(SecurityContextHolder.getContext().getAuthentication(), "the short session is over");

        for (int day = 0; day < 40; day++) {      // used every day for 40 days: still signed in
            now.addAndGet(24L * 60 * 60);
            SecurityContextHolder.clearContext();
            passThroughFilter("Bearer " + stay);
            assertNotNull(SecurityContextHolder.getContext().getAuthentication(), "day " + day);
        }
        now.addAndGet(Sessions.STAY_SECONDS);     // then not for 30 days
        SecurityContextHolder.clearContext();
        passThroughFilter("Bearer " + stay);
        assertNull(SecurityContextHolder.getContext().getAuthentication(), "30 days without use end it");
    }

    @Test
    void aSessionTokenIsStillRefusedAfterANewPassword() throws Exception {
        String token = sessionToken(true);
        seven.endAllSessions();
        passThroughFilter("Bearer " + token);
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void aTokenFromBeforeSessionsIsStillAcceptedAndCarriesNoSession() throws Exception {
        passThroughFilter("Bearer " + jwt.issue(7, 0));
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        assertNotNull(authentication, "members signed in with 2.7 stay signed in across the upgrade");
        assertNull(authentication.getDetails());
    }

    private static MockHttpServletRequest request(String token) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/me");
        request.addHeader("Authorization", "Bearer " + token);
        return request;
    }
}
