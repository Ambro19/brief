package net.onetechly.brief.unit;

import net.onetechly.brief.security.AuthMode;
import net.onetechly.brief.security.PasswordPolicy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PasswordPolicyTest {

    private final PasswordPolicy strong = new PasswordPolicy(new AuthMode("strict"));
    private final PasswordPolicy basic = new PasswordPolicy(new AuthMode("compat"));

    @Test
    void strongPolicyAcceptsEightCharactersWithALetterAndADigit() {
        assertEquals("strong", strong.name());
        assertTrue(strong.accepts("ambroise", "secret12"));
        assertTrue(strong.accepts("ambroise", "1234567a"));
        assertTrue(strong.accepts("ambroise", "correct horse 7 battery"));
    }

    @Test
    void strongPolicyRejectsShortPasswords() {
        assertFalse(strong.accepts("ambroise", "abc1234"));   // 7 characters
        assertFalse(strong.accepts("ambroise", ""));
        assertFalse(strong.accepts("ambroise", null));
    }

    @Test
    void strongPolicyNeedsBothALetterAndADigit() {
        assertFalse(strong.accepts("ambroise", "password"));   // letters only
        assertFalse(strong.accepts("ambroise", "12345678"));   // digits only
        assertFalse(strong.accepts("ambroise", "!!!!!!!!1"));  // no letter
    }

    @Test
    void strongPolicyRejectsThePasswordThatIsTheUsername() {
        assertFalse(strong.accepts("ambroise1", "ambroise1"));
        assertFalse(strong.accepts("Ambroise1", "ambroise1")); // also when only the capitals differ
    }

    @Test
    void basicPolicyAddsNoRuleOfItsOwn() {
        assertEquals("basic", basic.name());
        assertTrue(basic.accepts("ambroise", "pass"));
        assertTrue(basic.accepts("ambroise", "password"));
    }
}
