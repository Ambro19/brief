package net.onetechly.brief.unit;

import net.onetechly.brief.config.DatabaseUrl;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/** Translating a hosting platform's DATABASE_URL into the three values a JDBC connection needs. */
class DatabaseUrlTest {

    @Test
    void aRenderInternalConnectionStringBecomesAJdbcUrlWithTheDefaultPort() {
        // The form Render gives a service in the same region: no port, a short host name.
        DatabaseUrl.Jdbc jdbc = DatabaseUrl.parse("postgresql://brief_user:s3cretPw@dpg-abc123xyz-a/brief_db");

        assertEquals("jdbc:postgresql://dpg-abc123xyz-a:5432/brief_db", jdbc.url());
        assertEquals("brief_user", jdbc.username());
        assertEquals("s3cretPw", jdbc.password());
    }

    @Test
    void anExternalConnectionStringKeepsItsHostPortAndOptions() {
        DatabaseUrl.Jdbc jdbc = DatabaseUrl.parse(
                "postgres://brief:pw@dpg-abc-a.virginia-postgres.render.com:6543/brief?sslmode=require");

        assertEquals("jdbc:postgresql://dpg-abc-a.virginia-postgres.render.com:6543/brief?sslmode=require", jdbc.url());
        assertEquals("brief", jdbc.username());
        assertEquals("pw", jdbc.password());
    }

    @Test
    void specialCharactersInThePasswordAreDecodedAndPlusIsKept() {
        // %40 is @, %3A is :, %2F is /, %25 is %. A literal @ in the password is allowed too.
        DatabaseUrl.Jdbc jdbc = DatabaseUrl.parse("postgresql://us%40er:p%40ss%3Aw%2Ford%25+1@x@db_host:5432/brief");

        assertEquals("us@er", jdbc.username());
        assertEquals("p@ss:w/ord%+1@x", jdbc.password());
        assertEquals("jdbc:postgresql://db_host:5432/brief", jdbc.url(), "a host name with an underscore is accepted");
    }

    @Test
    void aStringWithoutUserOrPasswordAndAnIpv6HostAreUnderstood() {
        assertEquals(new DatabaseUrl.Jdbc("jdbc:postgresql://localhost:5432/brief", null, null),
                DatabaseUrl.parse("postgresql://localhost/brief"));
        assertEquals(new DatabaseUrl.Jdbc("jdbc:postgresql://localhost:5432/brief", "brief", null),
                DatabaseUrl.parse("postgresql://brief@localhost/brief"));
        assertEquals("jdbc:postgresql://[::1]:5433/brief", DatabaseUrl.parse("postgresql://u:p@[::1]:5433/brief").url());
        assertEquals("jdbc:postgresql://[::1]:5432/brief", DatabaseUrl.parse("postgresql://u:p@[::1]/brief").url());
    }

    @Test
    void aJdbcUrlIsPassedThroughUnchanged() {
        assertEquals(new DatabaseUrl.Jdbc("jdbc:postgresql://h:5432/d", null, null), DatabaseUrl.parse(" jdbc:postgresql://h:5432/d "));
    }

    @Test
    void aStringThatCannotBeUnderstoodIsRefusedWithoutRevealingThePassword() {
        for (String bad : new String[] {"", "   ", "mysql://u:hunter2@h/d", "postgresql://u:hunter2@h", "postgresql://u:hunter2@h/",
                "postgresql://u:hunter2@/d", "postgresql://u:hunter2@h:port/d"}) {
            IllegalArgumentException problem = assertThrows(IllegalArgumentException.class, () -> DatabaseUrl.parse(bad), bad);
            assertFalse(problem.getMessage().contains("hunter2"), "the message must not contain the password");
        }
        assertThrows(IllegalArgumentException.class, () -> DatabaseUrl.parse(null));
    }

    @Test
    void applyWritesTheThreeSpringSettings() {
        Properties system = new Properties();

        assertTrue(DatabaseUrl.apply(Map.of("DATABASE_URL", "postgresql://brief:pw@dpg-abc-a/brief"), system));

        assertEquals("jdbc:postgresql://dpg-abc-a:5432/brief", system.getProperty("spring.datasource.url"));
        assertEquals("brief", system.getProperty("spring.datasource.username"));
        assertEquals("pw", system.getProperty("spring.datasource.password"));
    }

    @Test
    void applyTellsFlywayNotToAdoptAPostgresDatabaseThatAlreadyHasTables() {
        // DATABASE_URL is a name other projects use too. If it points at somebody else's database,
        // Flyway must stop, not mark that database as a Brief! database.
        Properties system = new Properties();
        DatabaseUrl.apply(Map.of("DATABASE_URL", "postgresql://brief:pw@dpg-abc-a/brief"), system);
        assertEquals("false", system.getProperty("spring.flyway.baseline-on-migrate"));

        // A JDBC address handed over as it is (for example H2) keeps the setting from application.properties.
        Properties passedThrough = new Properties();
        DatabaseUrl.apply(Map.of("DATABASE_URL", "jdbc:h2:mem:x"), passedThrough);
        assertNull(passedThrough.getProperty("spring.flyway.baseline-on-migrate"));
    }

    @Test
    void aUserNameWithoutAPasswordMeansNoPassword() {
        Properties system = new Properties();
        DatabaseUrl.apply(Map.of("DATABASE_URL", "postgresql://brief@localhost/brief"), system);
        assertEquals("brief", system.getProperty("spring.datasource.username"));
        assertEquals("", system.getProperty("spring.datasource.password"), "not the 'sa' that the H2 default would supply");
    }

    @Test
    void applyDoesNothingWithoutTheVariableOrWhenTheDatabaseWasSetExplicitly() {
        Properties untouched = new Properties();
        assertFalse(DatabaseUrl.apply(Map.of(), untouched));
        assertFalse(DatabaseUrl.apply(Map.of("DATABASE_URL", " "), untouched));
        // An explicit setting wins over the platform's variable.
        assertFalse(DatabaseUrl.apply(Map.of("DATABASE_URL", "postgresql://a:b@h/d", "BRIEF_DB_URL", "jdbc:h2:mem:x"), untouched));
        assertFalse(DatabaseUrl.apply(Map.of("DATABASE_URL", "postgresql://a:b@h/d", "SPRING_DATASOURCE_URL", "jdbc:h2:mem:x"), untouched));
        assertTrue(untouched.isEmpty());

        Properties alreadySet = new Properties();
        alreadySet.setProperty("spring.datasource.url", "jdbc:h2:mem:chosen");
        assertFalse(DatabaseUrl.apply(Map.of("DATABASE_URL", "postgresql://a:b@h/d"), alreadySet));
        assertEquals("jdbc:h2:mem:chosen", alreadySet.getProperty("spring.datasource.url"));
    }

    @Test
    void applyStopsTheStartIfTheVariableIsSetButUnusable() {
        // Starting anyway would silently use the H2 file, and the data would vanish at the next redeploy.
        assertThrows(IllegalArgumentException.class,
                () -> DatabaseUrl.apply(Map.of("DATABASE_URL", "something else"), new Properties()));
    }
}
