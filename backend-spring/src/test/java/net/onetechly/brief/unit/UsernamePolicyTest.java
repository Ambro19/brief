package net.onetechly.brief.unit;

import net.onetechly.brief.security.AuthMode;
import net.onetechly.brief.security.UsernamePolicy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The rule for new usernames in each mode. It mirrors USERNAME_RULE in the web app's AuthPanel.tsx. */
class UsernamePolicyTest {

    private final UsernamePolicy strict = new UsernamePolicy(new AuthMode("strict"));
    private final UsernamePolicy compat = new UsernamePolicy(new AuthMode("compat"));

    @Test
    void strictAcceptsLettersDigitsDotsDashesAndUnderscores() {
        assertTrue(strict.accepts("maya"));
        assertTrue(strict.accepts("Maya_zo"));
        assertTrue(strict.accepts("nora.lane"));
        assertTrue(strict.accepts("dev-on.k_9"));
        assertTrue(strict.accepts("abc"), "three characters is the shortest allowed");
        assertTrue(strict.accepts("a".repeat(30)), "thirty characters is the longest allowed");
    }

    @Test
    void strictRejectsNamesThatAreTooShortOrTooLong() {
        assertFalse(strict.accepts(null));
        assertFalse(strict.accepts(""));
        assertFalse(strict.accepts("ab"));
        assertFalse(strict.accepts("a".repeat(31)));
    }

    @Test
    void strictRejectsSpacesSymbolsAndLettersOutsideThePlainAlphabet() {
        assertFalse(strict.accepts("two words"));
        assertFalse(strict.accepts(" maya"), "a space at the start would make two names look the same");
        assertFalse(strict.accepts("maya "));
        assertFalse(strict.accepts("@maya"));
        assertFalse(strict.accepts("maya#2"), "# is what the 2.3 migration uses to set a duplicate name aside");
        assertFalse(strict.accepts("ma/ya"));
        assertFalse(strict.accepts("mаya"), "a Cyrillic letter that looks like a Latin one");
        assertFalse(strict.accepts("josé"));
    }

    @Test
    void compatModeAddsNoRuleOfItsOwn() {
        assertTrue(compat.accepts("ab"));
        assertTrue(compat.accepts("two words"));
        assertTrue(compat.accepts("a".repeat(200)));
    }
}
