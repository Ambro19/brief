package net.onetechly.brief.unit;

import net.onetechly.brief.security.AuthMode;
import net.onetechly.brief.security.BoardAccess;
import net.onetechly.brief.security.BoardOwner;
import net.onetechly.brief.entity.Account;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Open and closed boards, and who runs them (2.5). */
class BoardAccessTest {

    private static final AuthMode STRICT = new AuthMode("strict");

    @Test
    void theBoardIsOpenUnlessSaidOtherwise() {
        for (String setting : new String[] {"", "   ", null, "open", " OPEN "}) {
            BoardAccess open = new BoardAccess(STRICT, setting, "");
            assertFalse(open.isClosed(), String.valueOf(setting));
            assertEquals("open", open.name());
        }
        assertFalse(new BoardAccess(new AuthMode("compat"), "", "").isClosed(), "compat mode with an open board is fine");
    }

    @Test
    void closedMeansClosed() {
        BoardAccess closed = new BoardAccess(STRICT, " Closed ", "");
        assertTrue(closed.isClosed());
        assertEquals("closed", closed.name());
        assertFalse(closed.isClosedByOldCode());
    }

    @Test
    void theCodeOf24KeepsABoardClosedSoThatAnUpgradeCannotOpenIt() {
        BoardAccess upgraded = new BoardAccess(STRICT, "", "spring-tulip-2026");
        assertTrue(upgraded.isClosed());
        assertTrue(upgraded.isClosedByOldCode(), "the log asks for BRIEF_BOARD=closed in its place");
        assertFalse(new BoardAccess(STRICT, "open", "spring-tulip-2026").isClosed(), "an explicit setting wins");
        assertFalse(new BoardAccess(STRICT, "closed", "spring-tulip-2026").isClosedByOldCode());
    }

    @Test
    void aClosedBoardWithCompatModeOrAnUnknownSettingStopsTheStart() {
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> new BoardAccess(new AuthMode("compat"), "closed", ""));
        assertTrue(refused.getMessage().contains("BRIEF_AUTH_MODE=strict"), refused.getMessage());
        assertThrows(IllegalStateException.class, () -> new BoardAccess(new AuthMode("compat"), "", "spring-tulip-2026"));
        assertThrows(IllegalStateException.class, () -> new BoardAccess(STRICT, "private", ""));
    }

    @Test
    void theOwnerIsOneAccountNamedInAnyCapitals() {
        Account ambro = new Account("AmbroLum", "hash");
        Account other = new Account("Gilberto", "hash");
        BoardOwner owner = new BoardOwner(" ambrolum ");
        assertTrue(owner.isConfigured());
        assertEquals("ambrolum", owner.usernameKey());
        assertTrue(owner.isOwner(ambro));
        assertFalse(owner.isOwner(other));
        assertFalse(owner.isOwner(null));

        for (String none : new String[] {"", "  ", null}) {
            BoardOwner nobody = new BoardOwner(none);
            assertFalse(nobody.isConfigured());
            assertFalse(nobody.isOwner(ambro));
            assertNull(nobody.usernameKey());
        }
    }
}
