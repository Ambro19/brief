package net.onetechly.brief.unit;

import net.onetechly.brief.config.StartupReport;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The line the application logs about its database when it has started. */
class StartupReportTest {

    private static String describe(String url) {
        return StartupReport.describe(url);
    }

    private static boolean isLocalFile(String url) {
        return StartupReport.isLocalFile(url);
    }

    @Test
    void namesEachKindOfDatabase() {
        assertEquals("Database: PostgreSQL, //dpg-abc-a:5432/brief.", describe("jdbc:postgresql://dpg-abc-a:5432/brief"));
        assertEquals("Database: H2 file ./h2/brief-spring on this machine.", describe("jdbc:h2:file:./h2/brief-spring"));
        assertEquals("Database: H2 in memory. Everything is forgotten when the application stops.",
                describe("jdbc:h2:mem:brief-contract;DB_CLOSE_DELAY=-1"));
        assertEquals("Database: jdbc:mysql://localhost:3306/brief.", describe("jdbc:mysql://localhost:3306/brief?user=a&password=b"));
        assertEquals("Database: .", describe(null));
    }

    @Test
    void neverShowsWhatFollowsTheQuestionMark() {
        // A JDBC address can carry a user name and a password there.
        String line = describe("jdbc:postgresql://host:5432/brief?user=brief&password=hunter2&sslmode=require");
        assertEquals("Database: PostgreSQL, //host:5432/brief.", line);
        assertFalse(line.contains("hunter2"));
    }

    @Test
    void onlyAnH2FileCountsAsStoredInsideTheContainer() {
        assertTrue(isLocalFile("jdbc:h2:file:./h2/brief-spring"));
        assertFalse(isLocalFile("jdbc:h2:mem:x"));
        assertFalse(isLocalFile("jdbc:postgresql://host/db"));
        assertFalse(isLocalFile(null));
    }

    @Test
    void theBoardLineSaysWhoCanUseIt() {
        assertEquals("Board: open. Anyone can read, and anyone can create an account.", StartupReport.describeBoard(false));
        assertEquals("Board: closed. Creating an account needs the invitation code; reading needs a sign-in.",
                StartupReport.describeBoard(true));
    }
}
