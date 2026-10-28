package net.onetechly.brief.push;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Which addresses Brief! sends notifications to. An endpoint comes from the browser, which means
 * from anyone signed in; everything outside the push services is refused (server-side request
 * forgery).
 */
class PushEndpointsTest {

    private final PushEndpoints defaults = new PushEndpoints("");

    @Test
    void thePushServicesOfTheFourBrowserMakersAreAccepted() {
        assertNotNull(defaults.check("https://fcm.googleapis.com/fcm/send/dKx:APA91b"));
        assertNotNull(defaults.check("https://updates.push.services.mozilla.com/wpush/v2/gAAAA"));
        assertNotNull(defaults.check("https://web.push.apple.com/QGuQyavXutnMH"));
        assertNotNull(defaults.check("https://wns2-par02p.notify.windows.com/w/?token=BQYAAA"));
        assertNotNull(defaults.check("https://FCM.googleapis.com/fcm/send/x"), "host names are not case-sensitive");
        assertNotNull(defaults.check("https://fcm.googleapis.com:443/fcm/send/x"), "the https port, written out");
    }

    @Test
    void everythingElseIsRefused() {
        assertNull(defaults.check("http://fcm.googleapis.com/fcm/send/x"), "plain http");
        assertNull(defaults.check("https://fcm.googleapis.com:8443/x"), "another port");
        assertNull(defaults.check("https://fcm.googleapis.com.evil.example/x"), "a look-alike host");
        assertNull(defaults.check("https://evilfcm.googleapis.com/x"), "an exact entry does not cover other hosts");
        assertNull(defaults.check("https://push.services.mozilla.com.evil.example/x"));
        assertNull(defaults.check("https://evilpush.services.mozilla.com/x"), "*.x.com means subdomains of x.com, not any name ending in it");
        assertNull(defaults.check("https://fcm.googleapis.com@169.254.169.254/latest"), "user info hides the real host");
        assertNull(defaults.check("https://user:secret@fcm.googleapis.com/fcm/send/x"), "user info is refused even before an allowed host");
        assertNull(defaults.check("https://169.254.169.254/latest/meta-data"), "the cloud metadata address");
        assertNull(defaults.check("http://localhost:8080/accounts"), "this machine");
        assertNull(defaults.check("file:///etc/passwd"));
        assertNull(defaults.check("not a url"));
        assertNull(defaults.check(""));
        assertNull(defaults.check(null));
        assertNull(defaults.check("https://fcm.googleapis.com/" + "x".repeat(1000)), "too long for the column");
    }

    @Test
    void theTestsMayAllowPlainHttpToThisMachineAndNothingElse() {
        PushEndpoints forTests = new PushEndpoints("http://localhost");
        assertNotNull(forTests.check("http://localhost:18200/push/1"));
        assertNull(forTests.check("http://127.0.0.1:18200/push/1"));
        assertNull(forTests.check("https://fcm.googleapis.com/x"), "a replaced list replaces the defaults");
        assertNull(defaults.check("http://localhost:18200/push/1"), "and the default list does not allow it");
    }
}
