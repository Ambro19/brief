package net.onetechly.brief.push;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Delivers one notification to one browser's push service (Web Push, RFC 8030).
 *
 *     POST <the subscription's endpoint>
 *     Authorization: vapid t=..., k=...      Brief!'s signature (Vapid)
 *     Content-Encoding: aes128gcm            the body is encrypted for the browser (WebPushCrypto)
 *     TTL: 86400                             the push service keeps it a day for a device that is off
 *     Urgency: normal
 *
 * The push service answers 201 when it has taken the message. 404 or 410 mean the subscription
 * no longer exists (notifications were turned off in the browser, the site data was cleared, the
 * browser was uninstalled): the caller is told, and deletes it. Anything else is logged.
 *
 * Like mail, notifications go out on a thread of their own, through a bounded queue: posting
 * never waits for Google, Apple or Mozilla, and a flood cannot use up the memory. Redirects are
 * not followed (HttpClient's default): only an address that passed PushEndpoints is ever reached.
 */
@Component
public class WebPushSender {

    private static final Logger log = LoggerFactory.getLogger(WebPushSender.class);

    /** How long a push service keeps a message for a device that is offline: a day. */
    static final int TTL_SECONDS = 24 * 60 * 60;
    static final int QUEUE_SIZE = 500;

    private final Vapid vapid;
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    private final ExecutorService worker = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(QUEUE_SIZE), runnable -> {
                Thread thread = new Thread(runnable, "brief-push");
                thread.setDaemon(true); // must not keep the application alive at shutdown
                return thread;
            });

    public WebPushSender(Vapid vapid) {
        this.vapid = vapid;
    }

    /** One delivery: where, to which browser keys, what. */
    public record Delivery(URI endpoint, String p256dh, String auth, byte[] payload) {
    }

    /**
     * Queues a delivery. {@code gone} is called (on the push thread) with the endpoint if the push
     * service says the subscription no longer exists.
     */
    public void send(Delivery delivery, Consumer<String> gone) {
        try {
            worker.execute(() -> {
                int status = deliver(delivery);
                if (status == 404 || status == 410) {
                    gone.accept(delivery.endpoint().toString());
                }
            });
        } catch (RejectedExecutionException e) {
            log.warn("Notification to {} dropped: {} are already waiting to be sent.", hostOf(delivery.endpoint()), QUEUE_SIZE);
        }
    }

    /**
     * Runs a piece of work on the push thread, in turn with the deliveries (independent review of
     * 2.8.0): preparing the notifications for a post reads and deletes rows, which must not happen
     * inside the request's transaction after it has committed.
     */
    public void run(Runnable task) {
        try {
            worker.execute(task);
        } catch (RejectedExecutionException e) {
            log.warn("Notifications dropped: {} tasks are already waiting.", QUEUE_SIZE);
        }
    }

    /** Sends now, on the calling thread. Returns the push service's status, or -1 if it could not be reached. */
    public int deliver(Delivery delivery) {
        String where = hostOf(delivery.endpoint());
        try {
            byte[] body = WebPushCrypto.encrypt(delivery.payload(), WebPushCrypto.unbase64(delivery.p256dh()),
                    WebPushCrypto.unbase64(delivery.auth()));
            HttpRequest request = HttpRequest.newBuilder(delivery.endpoint())
                    .timeout(Duration.ofSeconds(15))
                    .header("Authorization", vapid.authorization(delivery.endpoint()))
                    .header("Content-Encoding", "aes128gcm")
                    .header("Content-Type", "application/octet-stream")
                    .header("TTL", String.valueOf(TTL_SECONDS))
                    .header("Urgency", "normal")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status >= 200 && status < 300) {
                log.info("Notification accepted by {}.", where);
            } else if (status == 404 || status == 410) {
                log.info("Notification to {}: the subscription no longer exists ({}); it is deleted.", where, status);
            } else {
                // The body can say why (a wrong VAPID key, a payload too large); it holds nothing secret.
                String reason = response.body() == null ? "" : response.body().replaceAll("\\s+", " ");
                log.warn("Notification refused by {} with status {}: {}", where, status,
                        reason.length() > 200 ? reason.substring(0, 200) : reason);
            }
            return status;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        } catch (Exception e) {
            // An unreachable service, a time-out, or a subscription whose keys do not decode.
            log.warn("Notification to {} not sent: {}", where, e.getClass().getSimpleName());
            return -1;
        }
    }

    /** Only the host is logged: the rest of an endpoint identifies one browser. */
    private static String hostOf(URI endpoint) {
        return endpoint.getHost();
    }
}
