package net.onetechly.brief.push;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Which push service addresses Brief! will send to (2.8).
 *
 * The endpoint of a subscription comes from the browser, which means from whoever sends the
 * request: anyone signed in can hand Brief! any URL. Without a check, Brief! would then make
 * POST requests to that URL whenever its owner is mentioned, a server-side request forgery: a
 * way to make the server reach addresses on its own network, or attack a third party in its name.
 *
 * So an endpoint is accepted only when it is https and its host is one of the push services the
 * browsers use (the default list below), and HttpClient never follows a redirect. The list can
 * be replaced with brief.push.allowed-hosts; an entry "*.example.com" covers the subdomains of
 * example.com. The tests add "http://localhost", which allows plain http to this machine, for a
 * stand-in push service. That entry must never be set in production.
 */
@Component
public class PushEndpoints {

    /** The push services of Chrome and Edge on Android (Google), Firefox (Mozilla), Safari (Apple), Edge on Windows (Microsoft). */
    public static final String DEFAULT_HOSTS =
            "fcm.googleapis.com,*.push.services.mozilla.com,web.push.apple.com,*.push.apple.com,*.notify.windows.com";

    static final int MAX_LENGTH = 1000;

    private final List<String> hosts = new ArrayList<>();
    private boolean localHttp;

    @Autowired
    public PushEndpoints(@Value("${brief.push.allowed-hosts:}") String allowed) {
        String list = allowed == null || allowed.isBlank() ? DEFAULT_HOSTS : allowed;
        for (String entry : list.split(",")) {
            String host = entry.trim().toLowerCase(Locale.ROOT);
            if (host.equals("http://localhost")) {
                localHttp = true;
            } else if (!host.isEmpty()) {
                hosts.add(host);
            }
        }
    }

    /**
     * The endpoint as a URI if Brief! may send to it, null otherwise. The URI comes back in one
     * spelling (host in lower case, no ":443"), so that the same browser is always stored the same
     * way and the VAPID audience is what the push service expects (independent review of 2.8.0).
     */
    public URI check(String endpoint) {
        if (endpoint == null || endpoint.isBlank() || endpoint.length() > MAX_LENGTH) {
            return null;
        }
        URI uri;
        try {
            uri = new URI(endpoint.trim());
        } catch (URISyntaxException e) {
            return null;
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (uri.getRawUserInfo() != null || host.isEmpty()) {
            return null; // "https://fcm.googleapis.com@evil.example/" and the like
        }
        if (scheme.equals("http") && localHttp && host.equals("localhost")) {
            return normalised("http", host, uri.getPort(), uri);
        }
        if (!scheme.equals("https") || (uri.getPort() != -1 && uri.getPort() != 443)) {
            return null;
        }
        for (String allowed : hosts) {
            if (allowed.startsWith("*.") ? host.endsWith(allowed.substring(1)) : host.equals(allowed)) {
                return normalised("https", host, -1, uri);
            }
        }
        return null;
    }

    private static URI normalised(String scheme, String host, int port, URI uri) {
        String path = uri.getRawPath() == null ? "" : uri.getRawPath();
        String query = uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery();
        return URI.create(scheme + "://" + host + (port == -1 ? "" : ":" + port) + path + query);
    }
}
