package net.onetechly.brief.mail;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Sends mail through Resend (resend.com) over HTTPS.
 *
 * One request per message:
 *
 *     POST https://api.resend.com/emails
 *     Authorization: Bearer <the API key>
 *     Content-Type: application/json
 *
 *     {"from": "Brief! <hello@briefmessage.net>", "to": ["member@example.com"],
 *      "subject": "...", "text": "..."}
 *
 * Why HTTPS and not SMTP, the usual protocol for handing over mail: Render's free instances
 * block the SMTP ports. An HTTPS request goes out like any other.
 *
 * Messages are sent on a thread of their own. The request that caused the mail (create
 * account, forgot password) answers at once and does not wait for the mail service. That
 * keeps the application quick when the service is slow. It also keeps "forgot password" from
 * waiting for the mail service only when the address belongs to an account: that wait, a
 * good part of a second, would have told the two cases apart at a glance. (A small difference
 * remains: for a known address the application writes two rows before it answers.)
 *
 * The API key is never logged, and neither is the text of a message: it contains a link that
 * works like a password.
 */
public class ResendMailer implements Mailer {

    private static final Logger log = LoggerFactory.getLogger(ResendMailer.class);

    public static final String DEFAULT_ENDPOINT = "https://api.resend.com/emails";

    /** How many messages may wait to be sent. Beyond that, new ones are dropped and logged. */
    static final int QUEUE_SIZE = 200;

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    /** One worker thread and a bounded queue: mail goes out in order, and a flood cannot use up the memory. */
    private final ExecutorService worker = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(QUEUE_SIZE), runnable -> {
                Thread thread = new Thread(runnable, "brief-mail");
                thread.setDaemon(true); // must not keep the application alive at shutdown
                return thread;
            });

    private final URI endpoint;
    private final String apiKey;
    private final String from;

    public ResendMailer(String endpoint, String apiKey, String from) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("ResendMailer needs an API key");
        }
        if (from == null || from.isBlank()) {
            throw new IllegalStateException("BRIEF_MAIL_FROM (brief.mail.from) must name the sender, "
                    + "for example: Brief! <hello@briefmessage.net>");
        }
        this.endpoint = URI.create(endpoint == null || endpoint.isBlank() ? DEFAULT_ENDPOINT : endpoint.trim());
        this.apiKey = apiKey.trim();
        this.from = from.trim();
    }

    @Override
    public void send(Mail mail) {
        try {
            worker.execute(() -> deliver(mail));
        } catch (RejectedExecutionException e) {
            log.error("Mail to {} was not sent: {} messages are already waiting", mail.to(), QUEUE_SIZE);
        }
    }

    @Override
    public String description() {
        return "sent through Resend, from " + from;
    }

    /**
     * Sends one message and waits for the answer. Returns true if the service accepted it.
     * send(...) runs this on the mailer's own thread; the tests call it directly.
     */
    public boolean deliver(Mail mail) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("from", from);
            body.put("to", List.of(mail.to()));
            body.put("subject", mail.subject());
            body.put("text", mail.text());
            HttpRequest request = HttpRequest.newBuilder(endpoint)
                    .timeout(Duration.ofSeconds(20))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "brief-spring")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(mapper.writeValueAsBytes(body)))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() / 100 == 2) {
                log.info("Mail to {} accepted by Resend: \"{}\"", mail.to(), mail.subject());
                return true;
            }
            // The answer explains what was wrong (an unverified domain, a bad key). It does not contain the key.
            log.error("Mail to {} was refused by Resend with status {}: {}", mail.to(), response.statusCode(),
                    shortened(response.body()));
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Mail to {} was not sent: interrupted", mail.to());
            return false;
        } catch (Exception e) {
            log.error("Mail to {} was not sent: {}", mail.to(), e.toString());
            return false;
        }
    }

    private static String shortened(String text) {
        if (text == null) {
            return "";
        }
        String oneLine = text.replace('\n', ' ').replace('\r', ' ');
        return oneLine.length() <= 300 ? oneLine : oneLine.substring(0, 300) + "...";
    }
}
