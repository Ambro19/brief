package net.onetechly.brief.unit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import net.onetechly.brief.mail.Mail;
import net.onetechly.brief.mail.ResendMailer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the Resend mailer against a small web server on this machine that plays the part of
 * api.resend.com: real HTTP requests leave the mailer, and the test looks at what arrived.
 * No mail is sent and no account is needed. (com.sun.net.httpserver is part of the JDK.)
 */
class ResendMailerTest {

    /** One request as the stand-in server received it. */
    private record Received(String method, String path, String authorization, String contentType, String body) {
    }

    private final List<Received> received = new CopyOnWriteArrayList<>();
    private final AtomicInteger answerWith = new AtomicInteger(200);
    private HttpServer server;
    private String endpoint;

    @BeforeEach
    void startTheStandInForResend() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/emails", exchange -> {
            received.add(new Received(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                    exchange.getRequestHeaders().getFirst("Authorization"),
                    exchange.getRequestHeaders().getFirst("Content-Type"),
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            int status = answerWith.get();
            byte[] answer = (status == 200
                    ? "{\"id\":\"49a3999c-0ce1-4ea6-ab68-afcd6dc2e794\"}"
                    : "{\"statusCode\":" + status + ",\"message\":\"The example.test domain is not verified.\",\"name\":\"validation_error\"}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, answer.length);
            exchange.getResponseBody().write(answer);
            exchange.close();
        });
        server.start();
        endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/emails";
    }

    @AfterEach
    void stopTheStandIn() {
        server.stop(0);
    }

    /** Calls deliver(...) directly, so the test does not have to wait for the mailer's own thread. */
    private static boolean deliver(ResendMailer mailer, Mail mail) {
        return mailer.deliver(mail);
    }

    @Test
    void aMessageIsPostedAsJsonWithTheKeyInTheAuthorizationHeader() throws Exception {
        ResendMailer mailer = new ResendMailer(endpoint, " re_test_key ", "Brief! <hello@briefmessage.net>");

        assertTrue(deliver(mailer, new Mail("maya@example.test", "Confirm your email address for Brief!",
                "Hello Maya,\n\nhttps://briefmessage.net/?verify=abc\n\n\"Quotes\" and ünïcödé survive.")));

        assertEquals(1, received.size());
        Received request = received.get(0);
        assertEquals("POST", request.method());
        assertEquals("/emails", request.path());
        assertEquals("Bearer re_test_key", request.authorization());
        assertEquals("application/json", request.contentType());
        JsonNode body = new ObjectMapper().readTree(request.body());
        assertEquals(4, body.size(), "from, to, subject, text and nothing else");
        assertEquals("Brief! <hello@briefmessage.net>", body.get("from").asText());
        assertTrue(body.get("to").isArray());
        assertEquals(1, body.get("to").size());
        assertEquals("maya@example.test", body.get("to").get(0).asText());
        assertEquals("Confirm your email address for Brief!", body.get("subject").asText());
        assertEquals("Hello Maya,\n\nhttps://briefmessage.net/?verify=abc\n\n\"Quotes\" and ünïcödé survive.", body.get("text").asText());
    }

    @Test
    void aRefusalByTheServiceIsReportedAndDoesNotThrow() throws Exception {
        ResendMailer mailer = new ResendMailer(endpoint, "re_test_key", "Brief! <hello@example.test>");
        answerWith.set(403);

        assertFalse(deliver(mailer, new Mail("maya@example.test", "subject", "text")));
        assertEquals(1, received.size(), "it was tried once; the member can ask for another mail");
    }

    @Test
    void aServiceThatCannotBeReachedIsReportedAndDoesNotThrow() throws Exception {
        server.stop(0); // nothing listens on the port any more
        ResendMailer mailer = new ResendMailer(endpoint, "re_test_key", "Brief! <hello@example.test>");

        assertFalse(deliver(mailer, new Mail("maya@example.test", "subject", "text")));
    }

    @Test
    void sendHandsTheMessageToAnotherThreadAndReturnsAtOnce() throws Exception {
        ResendMailer mailer = new ResendMailer(endpoint, "re_test_key", "Brief! <hello@example.test>");

        mailer.send(new Mail("maya@example.test", "first", "text"));
        mailer.send(new Mail("gil@example.test", "second", "text"));

        long giveUpAt = System.currentTimeMillis() + 10_000;
        while (received.size() < 2 && System.currentTimeMillis() < giveUpAt) {
            Thread.sleep(20);
        }
        assertEquals(2, received.size());
        assertTrue(received.get(0).body().contains("\"first\""), "messages go out in the order they were handed over");
        assertTrue(received.get(1).body().contains("\"second\""));
    }

    @Test
    void theMailerCannotBeBuiltWithoutAKeyOrASender() {
        assertThrows(IllegalStateException.class, () -> new ResendMailer(endpoint, "", "Brief! <hello@example.test>"));
        assertThrows(IllegalStateException.class, () -> new ResendMailer(endpoint, "re_test_key", null));
        // no endpoint given: the real one is used
        assertDoesNotThrow(() -> new ResendMailer("", "re_test_key", "Brief! <hello@example.test>"));
    }
}
