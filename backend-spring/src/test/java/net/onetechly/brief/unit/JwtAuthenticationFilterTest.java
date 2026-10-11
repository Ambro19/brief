package net.onetechly.brief.unit;

import net.onetechly.brief.security.JwtAuthenticationFilter;
import net.onetechly.brief.security.JwtService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Runs the filter on its own with Spring's stand-in request and response objects
 * (MockHttpServletRequest and friends): no server is started.
 */
class JwtAuthenticationFilterTest {

    private final JwtService jwt = new JwtService(
            "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8), 600, () -> 1_000_000L);
    private final JwtAuthenticationFilter filter = new JwtAuthenticationFilter(jwt);

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
}
