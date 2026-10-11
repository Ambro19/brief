package integration;

import Controller.SocialMediaController;
import Util.ConnectionUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import org.junit.After;
import org.junit.Before;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * Starts the real application on a free port against a freshly reset in-memory
 * database before every test, and stops it afterwards. Tests talk to it over HTTP,
 * exactly as a client would.
 */
public abstract class ApiTestBase {

    static {
        // Safety net for running tests from an IDE (where the Maven setting is not applied):
        // never let a test reset the real on-disk database.
        if (System.getProperty("brief.db.url") == null) {
            System.setProperty("brief.db.url", "jdbc:h2:mem:brieftest;DB_CLOSE_DELAY=-1");
        }
    }

    protected final ObjectMapper mapper = new ObjectMapper();
    protected final HttpClient client = HttpClient.newHttpClient();
    protected Javalin app;
    protected String baseUrl;

    /** Subclasses choose the mode under test. */
    protected abstract boolean strictAuth();

    @Before
    public void startApp() {
        ConnectionUtil.resetTestDatabase();
        app = new SocialMediaController(strictAuth()).startAPI();
        app.start(0); // 0 = let the OS pick a free port
        baseUrl = "http://localhost:" + app.port();
    }

    @After
    public void stopApp() {
        app.stop();
    }

    protected HttpResponse<String> get(String path) throws IOException, InterruptedException {
        return send("GET", path, null, null);
    }

    protected HttpResponse<String> send(String method, String path, String jsonBody, String token)
            throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .header("Content-Type", "application/json");
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        HttpRequest.BodyPublisher body = jsonBody == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(jsonBody);
        builder.method(method, body);
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    protected static String accountJson(String username, String password) {
        return "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}";
    }

    protected static String messageJson(int postedBy, String text, long epoch) {
        return "{\"posted_by\":" + postedBy + ",\"message_text\":\"" + text + "\",\"time_posted_epoch\":" + epoch + "}";
    }

    protected static String textJson(String text) {
        return "{\"message_text\":\"" + text + "\"}";
    }

    protected static String repeat(char c, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(c);
        return sb.toString();
    }
}
