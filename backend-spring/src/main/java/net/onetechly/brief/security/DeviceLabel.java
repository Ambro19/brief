package net.onetechly.brief.security;

import java.util.Locale;

/**
 * Turns a browser's User-Agent header into a short label for the list of sessions in "Your
 * account" (2.8), such as "Chrome on Windows" or "Safari on iPhone".
 *
 * Only the label is stored, never the User-Agent itself: the full header is close to a
 * fingerprint of the device, and the list needs no more than a name the member recognises.
 *
 * The order of the checks matters. Edge and Opera put "Chrome" in their User-Agent, Chrome puts
 * "Safari" in it, and Android puts "Linux" in it; the more specific name is tested first. An
 * unknown header gives "a browser" / "an unknown system" rather than a guess.
 */
public final class DeviceLabel {

    /** Fits the device column (varchar 60) with room to spare. */
    static final int MAX_LENGTH = 60;

    private DeviceLabel() {
    }

    public static String of(String userAgent) {
        String ua = userAgent == null ? "" : userAgent.toLowerCase(Locale.ROOT);
        String label = browser(ua) + " on " + system(ua);
        return label.length() <= MAX_LENGTH ? label : label.substring(0, MAX_LENGTH);
    }

    private static String browser(String ua) {
        if (ua.contains("edg/") || ua.contains("edga/") || ua.contains("edgios/")) return "Edge";
        if (ua.contains("opr/") || ua.contains("opera")) return "Opera";
        if (ua.contains("samsungbrowser/")) return "Samsung Internet";
        if (ua.contains("firefox/") || ua.contains("fxios/")) return "Firefox";
        if (ua.contains("chrome/") || ua.contains("crios/") || ua.contains("chromium/")) return "Chrome";
        if (ua.contains("safari/")) return "Safari";
        return "A browser";
    }

    private static String system(String ua) {
        if (ua.contains("iphone")) return "iPhone";
        if (ua.contains("ipad")) return "iPad";
        if (ua.contains("android")) return "Android";
        if (ua.contains("cros")) return "ChromeOS";
        if (ua.contains("windows")) return "Windows";
        if (ua.contains("mac os x") || ua.contains("macintosh")) return "Mac";
        if (ua.contains("linux")) return "Linux";
        return "an unknown system";
    }
}
