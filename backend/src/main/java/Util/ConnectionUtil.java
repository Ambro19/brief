package Util;

import org.h2.jdbcx.JdbcConnectionPool;
import org.h2.tools.RunScript;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * Hands out JDBC connections from a small connection pool.
 *
 * The database URL can be overridden so tests run against an in-memory database
 * and never touch the file on disk:
 *   - JVM system property  brief.db.url   (set by the Maven test configuration)
 *   - environment variable BRIEF_DB_URL
 *   - default: a file database at ./h2/db
 *
 * Callers must close the connection they receive (use try-with-resources);
 * closing returns it to the pool.
 */
public class ConnectionUtil {

    private static final String DEFAULT_URL = "jdbc:h2:./h2/db";
    private static final String USERNAME = "sa";
    private static final String PASSWORD = "sa";

    private static final JdbcConnectionPool pool = JdbcConnectionPool.create(resolveUrl(), USERNAME, PASSWORD);

    private ConnectionUtil() {
    }

    private static String resolveUrl() {
        String url = System.getProperty("brief.db.url");
        if (url == null || url.isBlank()) {
            url = System.getenv("BRIEF_DB_URL");
        }
        return (url == null || url.isBlank()) ? DEFAULT_URL : url;
    }

    public static Connection getConnection() throws SQLException {
        return pool.getConnection();
    }

    /** Creates the tables if they do not exist yet. Safe to call on every start. */
    public static void initSchema() {
        runScript("/schema.sql");
    }

    /** Drops and recreates all tables with the seed data. For tests only. */
    public static void resetTestDatabase() {
        runScript("/SocialMedia.sql");
    }

    private static void runScript(String resource) {
        InputStream in = ConnectionUtil.class.getResourceAsStream(resource);
        if (in == null) {
            throw new IllegalStateException("SQL script not found on classpath: " + resource);
        }
        try (Connection connection = getConnection();
             Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            RunScript.execute(connection, reader);
        } catch (SQLException | IOException e) {
            throw new IllegalStateException("Could not run SQL script " + resource, e);
        }
    }
}
