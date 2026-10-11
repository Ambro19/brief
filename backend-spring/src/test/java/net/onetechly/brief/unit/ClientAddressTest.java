package net.onetechly.brief.unit;

import net.onetechly.brief.security.ClientAddress;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Which address the login rate limit counts by: the connection's, or the one a proxy passes on.
 * MockHttpServletRequest stands in for a real request; no server is started.
 */
class ClientAddressTest {

    private static final String PROXY = "10.20.30.40";

    /** A request that arrived from the proxy, optionally carrying a header. */
    private static MockHttpServletRequest requestFromProxy(String headerName, String headerValue) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/login");
        request.setRemoteAddr(PROXY);
        if (headerName != null) {
            request.addHeader(headerName, headerValue);
        }
        return request;
    }

    @Test
    void withNoHeaderConfiguredTheConnectionAddressIsUsedAndHeadersAreIgnored() {
        for (String notConfigured : new String[] {"", "   ", null}) {
            ClientAddress address = new ClientAddress(notConfigured);
            assertEquals(PROXY, address.of(requestFromProxy(null, null)));
            // On your own machine nothing sets these headers except the caller, so they must not count.
            assertEquals(PROXY, address.of(requestFromProxy("CF-Connecting-IP", "203.0.113.7")));
            assertEquals(PROXY, address.of(requestFromProxy("X-Forwarded-For", "203.0.113.7")));
        }
    }

    @Test
    void theConfiguredHeaderGivesTheVisitorsAddress() {
        ClientAddress address = new ClientAddress("CF-Connecting-IP");
        assertEquals("203.0.113.7", address.of(requestFromProxy("CF-Connecting-IP", "203.0.113.7")));
        assertEquals("2001:db8::1f", address.of(requestFromProxy("CF-Connecting-IP", " 2001:db8::1f ")));
        // Header names are not case sensitive.
        assertEquals("203.0.113.7", address.of(requestFromProxy("cf-connecting-ip", "203.0.113.7")));
    }

    @Test
    void aHeaderOtherThanTheConfiguredOneIsIgnored() {
        ClientAddress address = new ClientAddress("CF-Connecting-IP");
        // X-Forwarded-For can be written by the visitor; only the configured header counts.
        assertEquals(PROXY, address.of(requestFromProxy("X-Forwarded-For", "203.0.113.7")));
    }

    @Test
    void aMissingOrMalformedHeaderFallsBackToTheConnectionAddress() {
        ClientAddress address = new ClientAddress("CF-Connecting-IP");
        assertEquals(PROXY, address.of(requestFromProxy(null, null)));
        assertEquals(PROXY, address.of(requestFromProxy("CF-Connecting-IP", "")));
        assertEquals(PROXY, address.of(requestFromProxy("CF-Connecting-IP", "not an address")));
        assertEquals(PROXY, address.of(requestFromProxy("CF-Connecting-IP", "203.0.113.7, 198.51.100.2")), "a list is not one address");
        assertEquals(PROXY, address.of(requestFromProxy("CF-Connecting-IP", "1234567")), "digits alone are not an address");
        assertEquals(PROXY, address.of(requestFromProxy("CF-Connecting-IP", "1".repeat(20) + ":" + "2".repeat(40))), "longer than any address");
    }
}
