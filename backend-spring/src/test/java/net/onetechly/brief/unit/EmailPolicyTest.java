package net.onetechly.brief.unit;

import net.onetechly.brief.security.AuthMode;
import net.onetechly.brief.security.EmailPolicy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** What counts as an email address, and when one is required. */
class EmailPolicyTest {

    @Test
    void anAddressIsRequiredInStrictModeOnly() {
        assertTrue(new EmailPolicy(new AuthMode("strict")).isRequired());
        assertEquals("required", new EmailPolicy(new AuthMode("strict")).name());
        assertFalse(new EmailPolicy(new AuthMode("compat")).isRequired());
        assertEquals("optional", new EmailPolicy(new AuthMode("compat")).name());
    }

    @Test
    void anAddressIsStoredTrimmedAndInLowerCase() {
        assertEquals("maya@example.test", EmailPolicy.normalise("maya@example.test"));
        assertEquals("maya@example.test", EmailPolicy.normalise("  Maya@Example.TEST \n"));
        assertEquals("first.last+tag@mail.example.co.uk", EmailPolicy.normalise("First.Last+Tag@mail.example.co.uk"));
        assertEquals("o'brien_7-x@sub-domain.example.test", EmailPolicy.normalise("O'Brien_7-x@sub-domain.example.test"));
    }

    @Test
    void textThatIsNotAnAddressIsRefused() {
        for (String text : new String[] {null, "", "   ", "maya", "maya@", "@example.test", "maya@example", "maya@.test",
                "maya@example.", "maya@example..test", "maya@@example.test", "maya@exa@mple.test", "ma ya@example.test",
                "maya@exam ple.test", "maya\u0000@example.test", "ma\tya@example.test", "<maya@example.test>",
                "maya@example.test,other@example.test", "maya@example.test;", "\"maya\"@example.test", "maya\\@example.test",
                // each character that could break out of a mail header or of a page, alone
                "ma<ya@example.test", "ma>ya@example.test", "ma,ya@example.test", "ma;ya@example.test",
                "ma\"ya@example.test", "ma\\ya@example.test", "maya@exa<mple.test", "maya@example.te>st"}) {
            assertNull(EmailPolicy.normalise(text), "should not be accepted: " + text);
        }
    }

    @Test
    void anAddressLongerThanTheColumnIsRefused() {
        String domain = "@example.test";
        assertNotNull(EmailPolicy.normalise("a".repeat(EmailPolicy.MAX_LENGTH - domain.length()) + domain));
        assertNull(EmailPolicy.normalise("a".repeat(EmailPolicy.MAX_LENGTH - domain.length() + 1) + domain));
    }
}
