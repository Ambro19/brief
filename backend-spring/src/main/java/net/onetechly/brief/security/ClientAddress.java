package net.onetechly.brief.security;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Works out which address a request really comes from (2.3).
 *
 * On your own machine the answer is the address of the connection. On a hosting platform it
 * is not: every connection reaches the application from the platform's proxy, so all visitors
 * appear to have the same address. The login rate limit counts per address, so it would lock
 * out everybody at once, or nobody.
 *
 * The proxy therefore passes the visitor's address on in a request header. Which header can
 * be trusted depends on the proxy, so its name is a setting (brief.client-ip.header):
 *
 *   not set            use the address of the connection
 *   CF-Connecting-IP   Render. Its network writes this header on every request and replaces
 *                      whatever the visitor sent under that name.
 *
 * X-Forwarded-For is deliberately not used. Proxies ADD to that header, so its first entry is
 * whatever the visitor wrote there, and someone guessing passwords would write a new address
 * with every attempt and never be locked out.
 *
 * This class only finds the address. Which addresses count as one visitor (an IPv6 address
 * together with the rest of its block) is decided afterwards, by AddressGroup.
 *
 * To see what arrives on a real deployment, set the log level of this package to DEBUG
 * (environment variable LOGGING_LEVEL_NET_ONETECHLY_BRIEF_SECURITY=DEBUG) and sign in once.
 */
@Component
public class ClientAddress {

    private static final Logger log = LoggerFactory.getLogger(ClientAddress.class);

    /** The longest text form of an address: an IPv6 address with an IPv4 tail is 45 characters. */
    private static final int MAX_ADDRESS_LENGTH = 45;

    /** The header to read, or null to use the address of the connection. */
    private final String headerName;

    public ClientAddress(@Value("${brief.client-ip.header:}") String headerName) {
        this.headerName = headerName == null || headerName.isBlank() ? null : headerName.trim();
    }

    /** The visitor's address for this request. Never null if the connection has an address. */
    public String of(HttpServletRequest request) {
        String connection = request.getRemoteAddr();
        if (headerName == null) {
            return connection;
        }
        String forwarded = request.getHeader(headerName);
        if (looksLikeAnAddress(forwarded)) {
            String address = forwarded.trim();
            log.debug("Client address {} from header {} (connection {})", address, headerName, connection);
            return address;
        }
        // The header is missing or holds something else: for example a request that did not
        // come through the proxy. Fall back to what is certain.
        log.debug("Header {} missing or not an address; using the connection address {}", headerName, connection);
        return connection;
    }

    /**
     * True for text that has the shape of an IPv4 or IPv6 address: only digits, the letters a-f,
     * dots and colons, and no longer than an address can be. This is not a full parser. Its job
     * is to keep anything else (a list of addresses, a made-up word, a very long string) from
     * being used as a rate-limit key.
     */
    static boolean looksLikeAnAddress(String value) {
        if (value == null) {
            return false;
        }
        String text = value.trim();
        if (text.length() < 2 || text.length() > MAX_ADDRESS_LENGTH) {
            return false;
        }
        boolean hasSeparator = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean hexDigit = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (c == '.' || c == ':') {
                hasSeparator = true;
            } else if (!hexDigit) {
                return false;
            }
        }
        return hasSeparator;
    }
}
