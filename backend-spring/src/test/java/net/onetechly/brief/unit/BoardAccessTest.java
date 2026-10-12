package net.onetechly.brief.unit;

import net.onetechly.brief.security.AuthMode;
import net.onetechly.brief.security.BoardAccess;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Open and closed boards. */
class BoardAccessTest {

    private static final AuthMode STRICT = new AuthMode("strict");

    @Test
    void withoutACodeTheBoardIsOpenAndAdmitsEveryone() {
        for (String noCode : new String[] {"", "   ", null}) {
            BoardAccess open = new BoardAccess(STRICT, noCode);
            assertFalse(open.isClosed());
            assertEquals("open", open.name());
            assertTrue(open.admits(null));
            assertTrue(open.admits("anything"));
        }
        assertFalse(new BoardAccess(new AuthMode("compat"), "").isClosed(), "compat mode with no code is fine");
    }

    @Test
    void withACodeTheBoardIsClosedAndAdmitsOnlyThatCode() {
        BoardAccess closed = new BoardAccess(STRICT, "  spring-tulip-2026 ");
        assertTrue(closed.isClosed());
        assertEquals("closed", closed.name());
        assertTrue(closed.admits("spring-tulip-2026"));
        assertTrue(closed.admits(" spring-tulip-2026 "), "spaces around a pasted code are forgiven");
        for (String wrong : new String[] {null, "", "spring-tulip-202", "spring-tulip-20266", "Spring-Tulip-2026", "spring tulip 2026"}) {
            assertFalse(closed.admits(wrong), "should not be admitted: " + wrong);
        }
    }

    @Test
    void aCodeWithCompatModeStopsTheStart() {
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> new BoardAccess(new AuthMode("compat"), "spring-tulip-2026"));
        assertTrue(refused.getMessage().contains("BRIEF_AUTH_MODE=strict"), refused.getMessage());
        assertFalse(refused.getMessage().contains("spring-tulip-2026"), "the message goes to the log: it must not contain the code");
    }

    @Test
    void aCodeThatIsTooShortStopsTheStart() {
        assertThrows(IllegalStateException.class, () -> new BoardAccess(STRICT, "1234567"));
        assertTrue(new BoardAccess(STRICT, "12345678").isClosed());
    }
}
