import Controller.SocialMediaController;
import Util.ConnectionUtil;
import io.javalin.Javalin;

/**
 * Starts the application.
 *
 *   PORT              port to listen on (default 8080)
 *   BRIEF_AUTH_MODE   "strict" (default) or "compat" - see SocialMediaController
 *   BRIEF_DB_URL      JDBC URL (default: file database at ./h2/db)
 */
public class Main {
    public static void main(String[] args) {
        ConnectionUtil.initSchema();

        boolean strictAuth = !"compat".equalsIgnoreCase(System.getenv("BRIEF_AUTH_MODE"));
        int port = parsePort(System.getenv("PORT"));

        Javalin app = new SocialMediaController(strictAuth).startAPI();
        app.start(port);

        System.out.println("Brief! is running on http://localhost:" + port
                + "  (auth mode: " + (strictAuth ? "strict" : "compat") + ")");
    }

    private static int parsePort(String value) {
        try {
            return value == null ? 8080 : Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return 8080;
        }
    }
}
