package net.onetechly.brief.config;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Properties;

/**
 * Turns a PostgreSQL connection string, as hosting platforms provide it, into what Java needs.
 *
 * Render (like Heroku and others) gives the application ONE environment variable:
 *
 *     DATABASE_URL = postgresql://user:password@host:5432/database
 *
 * A Java database driver cannot use that form. It wants three separate values:
 *
 *     url       jdbc:postgresql://host:5432/database
 *     username  user
 *     password  password
 *
 * {@link #apply} does the translation once, at start-up, and hands the three values to Spring as
 * Java system properties (spring.datasource.url, .username, .password). System properties rank
 * above application.properties, so they win. Nothing else has to be configured: seeing a
 * jdbc:postgresql address, Spring Boot picks the PostgreSQL driver, Hibernate picks the
 * PostgreSQL dialect, and Flyway runs the migrations there.
 *
 * This class is plain Java on purpose (no Spring types): it must run before Spring starts.
 */
public final class DatabaseUrl {

    /** The variable hosting platforms use for the connection string. */
    public static final String VARIABLE = "DATABASE_URL";

    private static final int DEFAULT_POSTGRES_PORT = 5432;

    /** The three values a JDBC connection needs. username and password are null if the string had none. */
    public record Jdbc(String url, String username, String password) {
    }

    private DatabaseUrl() {
    }

    /**
     * If DATABASE_URL is set, and the database was not configured explicitly some other way,
     * writes spring.datasource.url / username / password into {@code systemProperties}.
     *
     * @return true if it did so
     * @throws IllegalArgumentException if DATABASE_URL is set but cannot be understood. Stopping
     *         here is deliberate: starting anyway would use the H2 file, and every account and
     *         post would disappear at the next redeploy.
     */
    public static boolean apply(Map<String, String> environment, Properties systemProperties) {
        String connectionString = environment.get(VARIABLE);
        if (connectionString == null || connectionString.isBlank()) {
            return false;
        }
        // An explicit setting wins over the platform's variable.
        boolean setExplicitly = hasText(environment.get("BRIEF_DB_URL"))
                || hasText(environment.get("SPRING_DATASOURCE_URL"))
                || hasText(systemProperties.getProperty("spring.datasource.url"));
        if (setExplicitly) {
            return false;
        }
        Jdbc jdbc = parse(connectionString.trim());
        systemProperties.setProperty("spring.datasource.url", jdbc.url());
        if (jdbc.username() != null) {
            systemProperties.setProperty("spring.datasource.username", jdbc.username());
            // A user name without a password means "no password", not the "sa" that
            // application.properties would otherwise supply for the H2 file.
            systemProperties.setProperty("spring.datasource.password", jdbc.password() == null ? "" : jdbc.password());
        }
        if (jdbc.url().startsWith("jdbc:postgresql:")) {
            // application.properties lets Flyway adopt a database that already has tables but no
            // Flyway history, because that is what a database made by Brief! 2.2 looks like. Such
            // a database can only be an H2 file: 2.2 never ran on PostgreSQL. DATABASE_URL is a
            // name other projects use too, so if it ever points at somebody else's database,
            // Flyway must refuse to touch it. With this setting it stops with a clear message.
            systemProperties.setProperty("spring.flyway.baseline-on-migrate", "false");
        }
        return true;
    }

    /**
     * Parses {@code postgresql://user:password@host:port/database?options}. The scheme may also be
     * {@code postgres://}; the port and the options are optional; characters written as %XX in the
     * user name or password are decoded. A string that already starts with {@code jdbc:} is
     * returned unchanged, with no user name or password.
     *
     * The parsing is done by hand, not with java.net.URI, which reports no host at all for a
     * host name that contains an underscore. The %XX decoding is done by hand too, because
     * java.net.URLDecoder would also turn every "+" in a password into a space.
     *
     * One limit: a "/" inside a password must be written as %2F, as the URL rules require.
     * Hosting platforms write their connection strings that way.
     */
    public static Jdbc parse(String connectionString) {
        if (connectionString == null || connectionString.isBlank()) {
            throw new IllegalArgumentException(VARIABLE + " is empty");
        }
        String text = connectionString.trim();
        if (text.startsWith("jdbc:")) {
            return new Jdbc(text, null, null);
        }
        String rest;
        if (text.startsWith("postgresql://")) {
            rest = text.substring("postgresql://".length());
        } else if (text.startsWith("postgres://")) {
            rest = text.substring("postgres://".length());
        } else {
            // Never repeat the value in the message: it contains the password.
            throw new IllegalArgumentException(VARIABLE + " must start with postgresql:// or postgres://");
        }

        // rest = [user[:password]@]host[:port]/database[?options]
        int slash = rest.indexOf('/');
        if (slash < 0) {
            throw new IllegalArgumentException(VARIABLE + " has no database name after the host");
        }
        String authority = rest.substring(0, slash);
        String databaseAndOptions = rest.substring(slash + 1);

        String username = null;
        String password = null;
        String hostAndPort = authority;
        int at = authority.lastIndexOf('@'); // the last one: a password may itself contain @
        if (at >= 0) {
            String userInfo = authority.substring(0, at);
            hostAndPort = authority.substring(at + 1);
            int colon = userInfo.indexOf(':');
            username = percentDecode(colon < 0 ? userInfo : userInfo.substring(0, colon));
            password = colon < 0 ? null : percentDecode(userInfo.substring(colon + 1));
        }

        String host = hostAndPort;
        int port = DEFAULT_POSTGRES_PORT;
        // An IPv6 address is written in brackets and contains colons itself: [::1]:5432
        int portColon = hostAndPort.startsWith("[") ? hostAndPort.indexOf("]:") + 1 : hostAndPort.lastIndexOf(':');
        if (portColon > 0) {
            host = hostAndPort.substring(0, portColon);
            try {
                port = Integer.parseInt(hostAndPort.substring(portColon + 1));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(VARIABLE + " has a port that is not a number");
            }
        }
        if (host.isEmpty()) {
            throw new IllegalArgumentException(VARIABLE + " has no host");
        }

        int question = databaseAndOptions.indexOf('?');
        String database = question < 0 ? databaseAndOptions : databaseAndOptions.substring(0, question);
        String options = question < 0 ? "" : databaseAndOptions.substring(question); // keeps the "?"
        if (database.isEmpty()) {
            throw new IllegalArgumentException(VARIABLE + " has no database name after the host");
        }
        return new Jdbc("jdbc:postgresql://" + host + ":" + port + "/" + database + options, username, password);
    }

    /** Replaces each %XX by the byte it stands for. Everything else, "+" included, is kept as it is. */
    static String percentDecode(String text) {
        if (text.indexOf('%') < 0) {
            return text;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(text.length());
        int i = 0;
        while (i < text.length()) {
            boolean escape = text.charAt(i) == '%' && i + 2 < text.length()
                    && isHex(text.charAt(i + 1)) && isHex(text.charAt(i + 2));
            if (escape) {
                out.write(Integer.parseInt(text.substring(i + 1, i + 3), 16));
                i += 3;
            } else {
                int codePoint = text.codePointAt(i); // one whole character, even if it takes two chars
                byte[] bytes = new String(Character.toChars(codePoint)).getBytes(StandardCharsets.UTF_8);
                out.write(bytes, 0, bytes.length);
                i += Character.charCount(codePoint);
            }
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    private static boolean isHex(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
