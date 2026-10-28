package net.onetechly.brief.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.onetechly.brief.entity.Account;
import net.onetechly.brief.entity.AccountSession;
import net.onetechly.brief.entity.Message;
import net.onetechly.brief.entity.PushSubscription;
import net.onetechly.brief.exception.BadRequestException;
import net.onetechly.brief.exception.ConflictException;
import net.onetechly.brief.exception.NotFoundException;
import net.onetechly.brief.exception.TooManyRequestsException;
import net.onetechly.brief.push.PushEndpoints;
import net.onetechly.brief.push.Vapid;
import net.onetechly.brief.push.WebPushCrypto;
import net.onetechly.brief.push.WebPushSender;
import net.onetechly.brief.repository.AccountRepository;
import net.onetechly.brief.repository.AccountSessionRepository;
import net.onetechly.brief.repository.PushSubscriptionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.net.URI;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Notifications for mentions, delivered to members' devices even when Brief! is closed (2.8).
 *
 * How it fits together:
 *
 *   1. A member turns notifications on in the web app. The browser asks permission, subscribes
 *      at its push service with Brief!'s public key (Vapid), and the web app sends the
 *      subscription here (subscribe). It is stored with the member's current session.
 *   2. Someone posts, or edits a post, so that it mentions that member. MessageService tells
 *      this class (mentioned) which members are newly mentioned.
 *   3. Once the post is saved (after the transaction commits: a post that fails to save alerts
 *      nobody), each of their subscriptions gets an encrypted notification through WebPushSender.
 *   4. On the device, the service worker (public/sw.js) shows it; a tap opens the Mentions page.
 *
 * What the notification holds: "<name> mentioned you" and the start of the post (140
 * characters). It is encrypted for the member's browser; the push service only carries it.
 *
 * Limits: at most 10 devices per member (the oldest goes); at most 10 notifications per member
 * per 10 minutes, so a member mentioned in a burst of posts gets a few notifications, not a
 * flood (each one replaces the last on the device anyway); at most 30 notified members per author
 * per 10 minutes, so editing names in and out of a post cannot fill the queue; a test
 * notification at most once a minute per session.
 *
 * Only a "Stay signed in" session can turn notifications on, and only a session that has not
 * ended receives them (independent review of 2.8.0). A 12-hour session on a shared computer whose
 * tab was closed without signing out would otherwise go on receiving the member's mentions, on a
 * device the member can no longer see in "Where you're signed in"; and a tap on the notification
 * would open Brief! signed out, because such a session lives only in its own tab. A subscription
 * of a session that has ended is deleted, with the session, when it is next found.
 *
 * Signing out of the session that subscribed, a new password, "Sign out everywhere else" and
 * deleting the account all delete the subscription with the session (on delete cascade, V6).
 */
@Service
public class PushNotifications implements MentionAlerts {

    private static final Logger log = LoggerFactory.getLogger(PushNotifications.class);

    public static final int MAX_DEVICES = 10;
    static final int BODY_CHARACTERS = 140;
    static final int BURST_LIMIT = 10;
    static final int AUTHOR_LIMIT = 30;
    static final long BURST_WINDOW_SECONDS = 10L * 60;
    static final long TEST_INTERVAL_SECONDS = 60;
    /** Where a tap on a mention notification leads. */
    static final String MENTIONS_URL = "/#/mentions";

    private final PushSubscriptionRepository subscriptions;
    private final AccountSessionRepository sessions;
    private final AccountRepository accounts;
    private final Vapid vapid;
    private final PushEndpoints endpoints;
    private final WebPushSender sender;
    private final LongSupplier clockSeconds;
    private final ObjectMapper mapper = new ObjectMapper();
    /** Member -> when their recent notifications went out (seconds), for the burst limit. */
    private final Map<Integer, Deque<Long>> recent = new ConcurrentHashMap<>();
    /** Author -> when the members their posts notified were notified, for the author limit. */
    private final Map<Integer, Deque<Long>> byAuthor = new ConcurrentHashMap<>();
    /** Session -> when it last asked for a test notification. */
    private final Map<Integer, Long> lastTest = new ConcurrentHashMap<>();

    @Autowired
    public PushNotifications(PushSubscriptionRepository subscriptions, AccountSessionRepository sessions,
                             AccountRepository accounts, Vapid vapid, PushEndpoints endpoints, WebPushSender sender) {
        this(subscriptions, sessions, accounts, vapid, endpoints, sender, () -> System.currentTimeMillis() / 1000);
    }

    public PushNotifications(PushSubscriptionRepository subscriptions, AccountSessionRepository sessions,
                             AccountRepository accounts, Vapid vapid, PushEndpoints endpoints, WebPushSender sender,
                             LongSupplier clockSeconds) {
        this.subscriptions = subscriptions;
        this.sessions = sessions;
        this.accounts = accounts;
        this.vapid = vapid;
        this.endpoints = endpoints;
        this.sender = sender;
        this.clockSeconds = clockSeconds;
    }

    /** True when the server has its VAPID keys: members can turn notifications on. */
    public boolean isOn() {
        return vapid.isConfigured();
    }

    /** The public key the browser subscribes with. 404 when notifications are off. */
    public String publicKey() {
        requireOn();
        return vapid.publicKey();
    }

    // ------------------------------------------------------------------ subscriptions

    /**
     * Stores a browser's subscription for this member and session. A browser has one endpoint.
     * If it was stored before for another session of this member, or for another member who used
     * the same browser, it now belongs to this one; for another member only when the request carries
     * the same auth secret, which only that browser knows: a member who learned someone's endpoint
     * cannot take their notifications over (independent review of 2.8.0).
     *
     * @throws NotFoundException (404) when notifications are off on this server
     * @throws ConflictException (409) for a session without "Stay signed in", or a token from 2.7 or earlier, which has no session
     * @throws BadRequestException (400) for an endpoint at an unknown push service, keys that are not P-256 / 16 bytes,
     *                             or an endpoint stored for another member with another secret
     */
    @Transactional
    public void subscribe(int accountId, Integer sessionId, String endpoint, String p256dh, String auth) {
        requireOn();
        long now = clockSeconds.getAsLong();
        if (sessionId == null || sessions.findById(sessionId).filter(s -> s.isStay() && s.isLiveAt(now)).isEmpty()) {
            throw new ConflictException(); // sign in again, with "Stay signed in on this device"
        }
        URI uri = endpoints.check(endpoint);
        if (uri == null || !validKeys(p256dh, auth)) {
            throw new BadRequestException();
        }
        String address = uri.toString();
        java.util.Optional<PushSubscription> existing = subscriptions.findByEndpoint(address);
        if (existing.isPresent()) {
            boolean sameBrowser = existing.get().getAccountId() == accountId || existing.get().getAuth().equals(auth.trim());
            if (!sameBrowser) {
                throw new BadRequestException();
            }
            subscriptions.delete(existing.get());
            subscriptions.flush(); // the old row must be gone before the new one claims the unique endpoint
        }
        subscriptions.save(new PushSubscription(accountId, sessionId, address, p256dh.trim(), auth.trim(),
                clockSeconds.getAsLong()));
        List<PushSubscription> mine = subscriptions.findByAccountId(accountId);
        if (mine.size() > MAX_DEVICES) {
            mine.sort(Comparator.comparing(PushSubscription::getSubscriptionId));
            subscriptions.deleteAll(mine.subList(0, mine.size() - MAX_DEVICES)); // the oldest go
        }
    }

    /** True if this browser endpoint is stored for this member's current session. */
    @Transactional
    public boolean isSubscribed(int accountId, Integer sessionId, String endpoint) {
        URI uri = endpoints.check(endpoint);
        if (uri == null || sessionId == null) {
            return false;
        }
        return subscriptions.findByEndpoint(uri.toString())
                .filter(found -> found.getAccountId() == accountId && found.getSessionId().equals(sessionId))
                .isPresent();
    }

    /** Forgets a browser's subscription, if it is this member's. Forgetting one that is not there is not an error. */
    @Transactional
    public void unsubscribe(int accountId, String endpoint) {
        URI uri = endpoints.check(endpoint);
        if (uri == null) {
            return;
        }
        subscriptions.findByEndpoint(uri.toString())
                .filter(found -> found.getAccountId() == accountId)
                .ifPresent(subscriptions::delete);
    }

    /** The push service said this subscription no longer exists. Called on the push thread. */
    void forget(String endpoint) {
        // Repository methods run in a transaction of their own, so no @Transactional is needed here
        // (and this is called on the bean itself, where it would not apply).
        subscriptions.findByEndpoint(endpoint).ifPresent(subscriptions::delete);
    }

    // ------------------------------------------------------------------ sending

    /**
     * MentionAlerts: queues a notification for each newly mentioned member, to be sent once the
     * post is saved. Called inside MessageService's transaction.
     */
    @Override
    public void mentioned(Message message, Set<Integer> accountIds) {
        if (!isOn() || accountIds.isEmpty()) {
            return;
        }
        Set<Integer> who = Set.copyOf(accountIds);
        // Prepared on the push thread: the work reads and deletes rows, which must not happen in the
        // request's transaction once it has committed (independent review of 2.8.0).
        Runnable send = () -> sender.run(() -> {
            try {
                sendMentions(message, who);
            } catch (RuntimeException e) {
                log.warn("Mention notifications for post {} not sent: {}", message.getMessageId(), e.toString());
            }
        });
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    send.run();
                }
            });
        } else {
            send.run();
        }
    }

    private void sendMentions(Message message, Set<Integer> accountIds) {
        String author = accounts.findById(message.getPostedBy()).map(Account::getUsername).orElse("Someone");
        byte[] payload = payload(author + " mentioned you", shorten(message.getMessageText()),
                "brief-mention", message.getMessageId());
        for (Integer accountId : accountIds) {
            if (!withinLimit(byAuthor, message.getPostedBy(), AUTHOR_LIMIT)) {
                log.info("Mention notifications for post {} skipped: its author notified {} members in the last {} minutes.",
                        message.getMessageId(), AUTHOR_LIMIT, BURST_WINDOW_SECONDS / 60);
                return;
            }
            if (!withinLimit(recent, accountId, BURST_LIMIT)) {
                log.info("Mention notification for member {} skipped: {} in the last {} minutes.",
                        accountId, BURST_LIMIT, BURST_WINDOW_SECONDS / 60);
                continue;
            }
            for (PushSubscription subscription : subscriptions.findByAccountId(accountId)) {
                if (sessionLive(subscription)) {
                    deliver(subscription, payload);
                }
            }
        }
    }

    /**
     * Sends a test notification to the devices of this session. Returns how many were sent.
     * 429 if the session asked less than a minute ago; 409 for a token without a session.
     */
    @Transactional
    public int test(int accountId, Integer sessionId) {
        requireOn();
        if (sessionId == null) {
            throw new ConflictException();
        }
        long now = clockSeconds.getAsLong();
        Long last = lastTest.get(sessionId);
        if (last != null && now - last < TEST_INTERVAL_SECONDS) {
            throw new TooManyRequestsException(TEST_INTERVAL_SECONDS - (now - last));
        }
        lastTest.values().removeIf(at -> now - at >= TEST_INTERVAL_SECONDS); // keep the map small
        lastTest.put(sessionId, now);
        byte[] payload = payload("Brief!", "Notifications are on. This is how a mention will look.", "brief-test", null);
        int sent = 0;
        for (PushSubscription subscription : subscriptions.findBySessionId(sessionId)) {
            if (subscription.getAccountId() == accountId) {
                deliver(subscription, payload);
                sent++;
            }
        }
        return sent;
    }

    private void deliver(PushSubscription subscription, byte[] payload) {
        URI uri = endpoints.check(subscription.getEndpoint()); // checked again: the allowed list may have changed
        if (uri == null) {
            forget(subscription.getEndpoint());
            return;
        }
        sender.send(new WebPushSender.Delivery(uri, subscription.getP256dh(), subscription.getAuth(), payload), this::forget);
    }

    /** Forgets the burst and test counts, as LoginRateLimiter.reset() does. For the tests, which reuse account ids. */
    public void resetLimits() {
        recent.clear();
        byAuthor.clear();
        lastTest.clear();
    }

    /**
     * True if the session that turned this subscription on has not ended. If it has, the session
     * is deleted, and the subscription with it (on delete cascade): that device gets nothing more.
     */
    private boolean sessionLive(PushSubscription subscription) {
        long now = clockSeconds.getAsLong();
        java.util.Optional<AccountSession> session = sessions.findById(subscription.getSessionId());
        if (session.isPresent() && session.get().isLiveAt(now)) {
            return true;
        }
        session.ifPresentOrElse(sessions::delete, () -> subscriptions.delete(subscription));
        return false;
    }

    /** At most {@code limit} events per key per BURST_WINDOW_SECONDS; records this one if it is allowed. */
    private boolean withinLimit(Map<Integer, Deque<Long>> times, int key, int limit) {
        long now = clockSeconds.getAsLong();
        Deque<Long> list = times.computeIfAbsent(key, id -> new ArrayDeque<>());
        synchronized (list) {
            while (!list.isEmpty() && now - list.peekFirst() >= BURST_WINDOW_SECONDS) {
                list.pollFirst();
            }
            if (list.size() >= limit) {
                return false;
            }
            list.addLast(now);
            return true;
        }
    }

    /** The JSON the service worker reads: title, body, where a tap leads, and a tag (a newer one replaces an older). */
    private byte[] payload(String title, String body, String tag, Integer messageId) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("title", title);
        content.put("body", body);
        content.put("url", MENTIONS_URL);
        content.put("tag", tag);
        if (messageId != null) {
            content.put("message_id", messageId);
        }
        try {
            return mapper.writeValueAsBytes(content);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Could not write the notification", e);
        }
    }

    /** The first BODY_CHARACTERS characters of a post, on one line, without splitting a character in two. */
    public static String shorten(String text) {
        String line = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        if (line.codePointCount(0, line.length()) <= BODY_CHARACTERS) {
            return line;
        }
        return line.substring(0, line.offsetByCodePoints(0, BODY_CHARACTERS - 1)).trim() + "…";
    }

    private static boolean validKeys(String p256dh, String auth) {
        try {
            WebPushCrypto.publicKey(WebPushCrypto.unbase64(p256dh)); // a P-256 point, on the curve
            return WebPushCrypto.unbase64(auth).length == 16 && p256dh.trim().length() <= 100;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private void requireOn() {
        if (!isOn()) {
            throw new NotFoundException();
        }
    }
}
