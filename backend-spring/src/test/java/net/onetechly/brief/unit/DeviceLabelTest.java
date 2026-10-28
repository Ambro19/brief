package net.onetechly.brief.unit;

import net.onetechly.brief.security.DeviceLabel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The short names in "Where you're signed in" (2.8), from real User-Agent headers. */
class DeviceLabelTest {

    @Test
    void namesTheBrowserAndTheSystem() {
        assertEquals("Edge on Windows", DeviceLabel.of("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                + "(KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36 Edg/130.0.0.0"));
        assertEquals("Chrome on Windows", DeviceLabel.of("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                + "(KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"));
        assertEquals("Safari on iPhone", DeviceLabel.of("Mozilla/5.0 (iPhone; CPU iPhone OS 17_6 like Mac OS X) "
                + "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.6 Mobile/15E148 Safari/604.1"));
        assertEquals("Chrome on Android", DeviceLabel.of("Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 "
                + "(KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"));
        assertEquals("Firefox on Mac", DeviceLabel.of("Mozilla/5.0 (Macintosh; Intel Mac OS X 14.6; rv:131.0) Gecko/20100101 Firefox/131.0"));
        assertEquals("Samsung Internet on Android", DeviceLabel.of("Mozilla/5.0 (Linux; Android 14; SM-S921B) AppleWebKit/537.36 "
                + "(KHTML, like Gecko) SamsungBrowser/26.0 Chrome/122.0.0.0 Mobile Safari/537.36"));
        assertEquals("Chrome on iPad", DeviceLabel.of("Mozilla/5.0 (iPad; CPU OS 17_6 like Mac OS X) AppleWebKit/605.1.15 "
                + "(KHTML, like Gecko) CriOS/130.0.6723.90 Mobile/15E148 Safari/604.1"));
    }

    @Test
    void somethingUnknownIsNotGuessed() {
        assertEquals("A browser on an unknown system", DeviceLabel.of(null));
        assertEquals("A browser on an unknown system", DeviceLabel.of("curl/8.5.0"));
        assertTrue(DeviceLabel.of("x".repeat(5000)).length() <= 60, "never longer than the column");
    }
}
