package net.onetechly.brief.push;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.onetechly.brief.entity.Account;
import net.onetechly.brief.entity.AccountSession;
import net.onetechly.brief.repository.AccountSessionRepository;
import net.onetechly.brief.entity.Message;
import net.onetechly.brief.entity.PushSubscription;
import net.onetechly.brief.exception.BadRequestException;
import net.onetechly.brief.exception.ConflictException;
import net.onetechly.brief.exception.NotFoundException;
import net.onetechly.brief.exception.TooManyRequestsException;
import net.onetechly.brief.repository.AccountRepository;
import net.onetechly.brief.repository.PushSubscriptionRepository;
import net.onetechly.brief.service.PushNotifications;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.security.KeyPair;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Notifications for mentions (2.8), without a database or a network: the push_subscription table
 * is a list, and the sender only records what it was asked to deliver.
 */
class PushNotificationsTest {

    /** What WebPushSender was asked to send. */
    static final class RecordingSender extends WebPushSender {
        final List<Delivery> sent = new ArrayList<>();
        final List<Consumer<String>> goneCallbacks = new ArrayList<>();

        RecordingSender(Vapid vapid) {
            super(vapid);
        }

        @Override
        public void send(Delivery delivery, Consumer<String> gone) {
            sent.add(delivery);
            goneCallbacks.add(gone);
        }

        @Override
        public void run(Runnable task) {
            task.run(); // the push thread, played by the test's own
        }
    }

    private final AtomicLong now = new AtomicLong(1_800_000_000L);
    private final List<PushSubscription> rows = new ArrayList<>();
    private final PushSubscriptionRepository table = mock(PushSubscriptionRepository.class);
    private final AccountRepository accounts = mock(AccountRepository.class);
    /** Sessions 1 to 99: "Stay signed in", live; a test ends or shortens some of them. */
    private final AccountSessionRepository sessionTable = mock(AccountSessionRepository.class);
    private final java.util.Map<Integer, AccountSession> sessionRows = new java.util.HashMap<>();
    private final Vapid vapid = new Vapid(VapidTest.PUBLIC, VapidTest.PRIVATE, "https://briefmessage.net", now::get);
    private final RecordingSender sender = new RecordingSender(vapid);
    private final PushNotifications push = new PushNotifications(table, sessionTable, accounts, vapid, new PushEndpoints(""), sender, now::get);
    private final ObjectMapper mapper = new ObjectMapper();

    /** A browser: its key pair and secret, as PushManager.subscribe makes them. */
    private final KeyPair browser = WebPushCrypto.newKeyPair();
    private final String p256dh = WebPushCrypto.base64(WebPushCrypto.encode((ECPublicKey) browser.getPublic()));
    private final byte[] authBytes = new byte[16];
    private final String auth = WebPushCrypto.base64(authBytes);

    @BeforeEach
    void table() {
        when(table.save(any(PushSubscription.class))).thenAnswer(call -> {
            PushSubscription row = call.getArgument(0);
            Field id = PushSubscription.class.getDeclaredField("subscriptionId");
            id.setAccessible(true);
            id.set(row, rows.size() + 100);
            rows.add(row);
            return row;
        });
        when(table.findByEndpoint(anyString())).thenAnswer(call ->
                rows.stream().filter(r -> r.getEndpoint().equals(call.getArgument(0))).findFirst());
        when(table.findByAccountId(anyInt())).thenAnswer(call -> {
            List<PushSubscription> found = new ArrayList<>();
            rows.stream().filter(r -> r.getAccountId().equals(call.getArgument(0))).forEach(found::add);
            return found;
        });
        when(table.findBySessionId(anyInt())).thenAnswer(call -> {
            List<PushSubscription> found = new ArrayList<>();
            rows.stream().filter(r -> r.getSessionId().equals(call.getArgument(0))).forEach(found::add);
            return found;
        });
        doAnswer(call -> rows.remove((PushSubscription) call.getArgument(0))).when(table).delete(any(PushSubscription.class));
        doAnswer(call -> {
            ((Iterable<?>) call.getArgument(0)).forEach(rows::remove);
            return null;
        }).when(table).deleteAll(any(Iterable.class));
        for (int id = 1; id < 100; id++) {
            sessionRows.put(id, session(id, true, now.get() + 30L * 24 * 3600));
        }
        when(sessionTable.findById(anyInt())).thenAnswer(call -> Optional.ofNullable(sessionRows.get((Integer) call.getArgument(0))));
        doAnswer(call -> {
            AccountSession gone = call.getArgument(0);
            sessionRows.remove(gone.getSessionId());
            rows.removeIf(r -> r.getSessionId().equals(gone.getSessionId())); // on delete cascade
            return null;
        }).when(sessionTable).delete(any(AccountSession.class));
        Account gilberto = new Account("Gilberto", "hash");
        gilberto.setAccountId(2);
        when(accounts.findById(2)).thenReturn(Optional.of(gilberto));
    }

    private static AccountSession session(int id, boolean stay, long expiresAt) {
        AccountSession session = new AccountSession(3, stay, "Chrome on Windows", expiresAt - 1000, expiresAt);
        try {
            Field f = AccountSession.class.getDeclaredField("sessionId");
            f.setAccessible(true);
            f.set(session, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        return session;
    }

    private static Message post(int id, int author, String text) {
        Message message = new Message(author, text, 1_800_000_000L);
        message.setMessageId(id);
        return message;
    }

    @Test
    void aNewMentionGoesToEveryDeviceOfTheMemberAsAnEncryptedNotification() throws Exception {
        push.subscribe(3, 11, "https://fcm.googleapis.com/fcm/send/phone", p256dh, auth);
        push.subscribe(3, 12, "https://updates.push.services.mozilla.com/wpush/v2/laptop", p256dh, auth);
        push.subscribe(4, 13, "https://web.push.apple.com/someone-else", p256dh, auth);

        push.mentioned(post(9, 2, "Hey Mayayo,\n welcome to Brief!"), Set.of(3));

        assertEquals(2, sender.sent.size(), "both of member 3's devices, nobody else's");
        WebPushSender.Delivery delivery = sender.sent.get(0);
        assertEquals("https://fcm.googleapis.com/fcm/send/phone", delivery.endpoint().toString());
        JsonNode content = mapper.readTree(delivery.payload());
        assertEquals("Gilberto mentioned you", content.get("title").asText());
        assertEquals("Hey Mayayo, welcome to Brief!", content.get("body").asText(), "on one line");
        assertEquals("/#/mentions", content.get("url").asText());
        assertEquals(9, content.get("message_id").asInt());

        // What the push service would carry: only the browser can read it.
        byte[] body = WebPushCrypto.encrypt(delivery.payload(), WebPushCrypto.unbase64(delivery.p256dh()), WebPushCrypto.unbase64(delivery.auth()));
        assertEquals(new String(delivery.payload(), java.nio.charset.StandardCharsets.UTF_8),
                WebPushDecrypt.decrypt(body, (ECPrivateKey) browser.getPrivate(), WebPushCrypto.unbase64(p256dh), authBytes));
    }

    @Test
    void nothingIsSentWhenNobodyNewIsMentionedOrNotificationsAreOff() {
        push.subscribe(3, 11, "https://fcm.googleapis.com/fcm/send/phone", p256dh, auth);
        push.mentioned(post(9, 2, "no names here"), Set.of());
        assertTrue(sender.sent.isEmpty());

        Vapid off = new Vapid("", "", "https://briefmessage.net", now::get);
        RecordingSender quiet = new RecordingSender(off);
        new PushNotifications(table, sessionTable, accounts, off, new PushEndpoints(""), quiet, now::get).mentioned(post(9, 2, "Mayayo"), Set.of(3));
        assertTrue(quiet.sent.isEmpty());
    }

    @Test
    void aLongPostIsShortenedToOneLineOf140Characters() throws Exception {
        push.subscribe(3, 11, "https://fcm.googleapis.com/fcm/send/phone", p256dh, auth);
        push.mentioned(post(9, 2, "Mayayo " + "é".repeat(240)), Set.of(3));
        String body = mapper.readTree(sender.sent.get(0).payload()).get("body").asText();
        assertEquals(140, body.codePointCount(0, body.length()));
        assertTrue(body.endsWith("…"));
    }

    @Test
    void aBurstOfMentionsGivesAFewNotificationsNotAFlood() {
        push.subscribe(3, 11, "https://fcm.googleapis.com/fcm/send/phone", p256dh, auth);
        for (int i = 0; i < 25; i++) {
            push.mentioned(post(10 + i, 2, "Mayayo " + i), Set.of(3));
        }
        assertEquals(10, sender.sent.size(), "at most 10 in 10 minutes");
        now.addAndGet(10 * 60);
        push.mentioned(post(99, 2, "Mayayo again"), Set.of(3));
        assertEquals(11, sender.sent.size(), "and more once the 10 minutes have passed");
    }

    @Test
    void aSubscriptionTheServiceNoLongerKnowsIsDeleted() {
        push.subscribe(3, 11, "https://fcm.googleapis.com/fcm/send/phone", p256dh, auth);
        push.mentioned(post(9, 2, "Mayayo"), Set.of(3));
        sender.goneCallbacks.get(0).accept("https://fcm.googleapis.com/fcm/send/phone"); // the push service said 410
        assertTrue(rows.isEmpty());
    }

    @Test
    void subscribingNeedsNotificationsOnASessionAPushServiceAndRealKeys() {
        Vapid off = new Vapid("", "", "https://briefmessage.net", now::get);
        assertThrows(NotFoundException.class, () -> new PushNotifications(table, sessionTable, accounts, off, new PushEndpoints(""),
                new RecordingSender(off), now::get).subscribe(3, 11, "https://fcm.googleapis.com/x", p256dh, auth));
        assertThrows(ConflictException.class, () -> push.subscribe(3, null, "https://fcm.googleapis.com/x", p256dh, auth),
                "a token from 2.7 has no session to tie the device to");
        assertThrows(BadRequestException.class, () -> push.subscribe(3, 11, "https://evil.example/x", p256dh, auth));
        assertThrows(BadRequestException.class, () -> push.subscribe(3, 11, "https://fcm.googleapis.com/x", "BAAA", auth));
        assertThrows(BadRequestException.class, () -> push.subscribe(3, 11, "https://fcm.googleapis.com/x", p256dh, "c2hvcnQ"));
        assertTrue(rows.isEmpty());
    }

    @Test
    void aBrowserBelongsToWhoeverSubscribedItLastAndAMemberKeepsTenDevices() {
        push.subscribe(3, 11, "https://fcm.googleapis.com/fcm/send/shared", p256dh, auth);
        push.subscribe(4, 14, "https://fcm.googleapis.com/fcm/send/shared", p256dh, auth); // another member, same browser
        assertEquals(1, rows.size());
        assertEquals(4, rows.get(0).getAccountId());
        assertFalse(push.isSubscribed(3, 11, "https://fcm.googleapis.com/fcm/send/shared"));
        assertTrue(push.isSubscribed(4, 14, "https://fcm.googleapis.com/fcm/send/shared"));

        for (int i = 0; i < PushNotifications.MAX_DEVICES + 2; i++) {
            push.subscribe(5, 20 + i, "https://fcm.googleapis.com/fcm/send/device" + i, p256dh, auth);
        }
        assertEquals(PushNotifications.MAX_DEVICES, table.findByAccountId(5).size());
        assertFalse(push.isSubscribed(5, 20, "https://fcm.googleapis.com/fcm/send/device0"), "the oldest went");
    }

    @Test
    void aMemberCanOnlyTurnOffTheirOwnDevice() {
        push.subscribe(3, 11, "https://fcm.googleapis.com/fcm/send/phone", p256dh, auth);
        push.unsubscribe(4, "https://fcm.googleapis.com/fcm/send/phone");
        assertEquals(1, rows.size());
        push.unsubscribe(3, "https://fcm.googleapis.com/fcm/send/phone");
        assertTrue(rows.isEmpty());
        assertDoesNotThrow(() -> push.unsubscribe(3, "https://fcm.googleapis.com/fcm/send/phone"), "twice is fine");
    }

    @Test
    void aTestNotificationGoesToThisSessionsDeviceOnceAMinute() {
        push.subscribe(3, 11, "https://fcm.googleapis.com/fcm/send/phone", p256dh, auth);
        push.subscribe(3, 12, "https://fcm.googleapis.com/fcm/send/laptop", p256dh, auth);
        assertEquals(1, push.test(3, 11));
        assertEquals("https://fcm.googleapis.com/fcm/send/phone", sender.sent.get(0).endpoint().toString());
        TooManyRequestsException tooSoon = assertThrows(TooManyRequestsException.class, () -> push.test(3, 11));
        assertEquals(60, tooSoon.getRetryAfterSeconds());
        now.addAndGet(60);
        assertEquals(1, push.test(3, 11));
        assertThrows(ConflictException.class, () -> push.test(3, null));
    }

    @Test
    void shorteningNeverSplitsACharacter() {
        assertEquals("short", PushNotifications.shorten("  short  "));
        String emoji = "😀".repeat(200); // 200 characters outside the basic plane, two Java chars each
        String cut = PushNotifications.shorten(emoji);
        assertEquals(140, cut.codePointCount(0, cut.length()));
        assertFalse(Character.isHighSurrogate(cut.charAt(cut.length() - 2)) && !Character.isLowSurrogate(cut.charAt(cut.length() - 1)));
    }

    // ---- independent review of 2.8.0 ----

    @Test
    void onlyAStaySignedInSessionCanTurnNotificationsOn() {
        sessionRows.put(11, session(11, false, now.get() + 12 * 3600));
        assertThrows(ConflictException.class, () -> push.subscribe(3, 11, "https://fcm.googleapis.com/fcm/send/x", p256dh, auth),
                "a 12-hour session, as on a shared computer: sign in again with Stay signed in");
        sessionRows.put(12, session(12, true, now.get() - 1));
        assertThrows(ConflictException.class, () -> push.subscribe(3, 12, "https://fcm.googleapis.com/fcm/send/x", p256dh, auth),
                "a session that has ended");
        assertTrue(rows.isEmpty());
    }

    @Test
    void aDeviceWhoseSessionEndedGetsNothingAndIsForgotten() {
        push.subscribe(3, 11, "https://fcm.googleapis.com/fcm/send/old-laptop", p256dh, auth);
        push.subscribe(3, 12, "https://fcm.googleapis.com/fcm/send/phone", p256dh, auth);
        sessionRows.put(11, session(11, true, now.get() - 1)); // the laptop's session ran out, nobody signed out

        push.mentioned(post(9, 2, "Mayayo"), Set.of(3));

        assertEquals(1, sender.sent.size());
        assertEquals("https://fcm.googleapis.com/fcm/send/phone", sender.sent.get(0).endpoint().toString());
        assertFalse(sessionRows.containsKey(11), "the ended session is deleted when it is found...");
        assertEquals(1, rows.size(), "...and its subscription with it");
    }

    @Test
    void someoneWhoLearnedAnEndpointCannotTakeItOver() {
        push.subscribe(3, 11, "https://fcm.googleapis.com/fcm/send/mayayo", p256dh, auth);
        String otherAuth = WebPushCrypto.base64("another-secret!!".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        assertThrows(BadRequestException.class, () -> push.subscribe(4, 14, "https://fcm.googleapis.com/fcm/send/mayayo", p256dh, otherAuth));
        assertEquals(3, rows.get(0).getAccountId(), "still hers");
        push.subscribe(3, 12, "https://fcm.googleapis.com/fcm/send/mayayo", p256dh, otherAuth);
        assertEquals(12, rows.get(0).getSessionId(), "her own browser, subscribed again from a new session, is fine");
    }

    @Test
    void anAuthorCannotFloodTheQueueByEditingNamesInAndOut() {
        for (int member = 100; member < 140; member++) {
            push.subscribe(member, 1 + member % 90, "https://fcm.googleapis.com/fcm/send/m" + member, p256dh, auth);
        }
        for (int member = 100; member < 140; member++) {
            push.mentioned(post(9, 2, "hi"), Set.of(member));
        }
        assertEquals(30, sender.sent.size(), "at most 30 notified members per author per 10 minutes");
    }

    @Test
    void anEndpointIsStoredInOneSpelling() {
        push.subscribe(3, 11, "https://FCM.googleapis.com:443/fcm/send/Case-Kept", p256dh, auth);
        assertEquals("https://fcm.googleapis.com/fcm/send/Case-Kept", rows.get(0).getEndpoint(), "host in lower case, no :443, path as it was");
        assertTrue(push.isSubscribed(3, 11, "https://fcm.googleapis.com/fcm/send/Case-Kept"));
    }

    @Test
    void nothingIsSentUntilThePostIsSavedAndNothingIfItIsNot() {
        push.subscribe(3, 11, "https://fcm.googleapis.com/fcm/send/phone", p256dh, auth);
        // As Spring does inside MessageService's transaction: callbacks are collected, and run only after the commit.
        org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
        try {
            push.mentioned(post(9, 2, "Mayayo"), Set.of(3));
            assertTrue(sender.sent.isEmpty(), "not while the post may still fail to save");
            for (var sync : org.springframework.transaction.support.TransactionSynchronizationManager.getSynchronizations()) {
                sync.afterCommit();
            }
            assertEquals(1, sender.sent.size(), "once it is saved");
        } finally {
            org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization();
        }
        // A rolled-back post: Spring never calls afterCommit, so nothing more goes out.
        org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
        try {
            push.mentioned(post(10, 2, "Mayayo"), Set.of(3));
        } finally {
            org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization();
        }
        assertEquals(1, sender.sent.size());
    }

    @Test
    void whetherADeviceIsOnIsAnsweredForThisSignInOnly() {
        push.subscribe(3, 11, "https://fcm.googleapis.com/fcm/send/laptop", p256dh, auth);
        assertTrue(push.isSubscribed(3, 11, "https://fcm.googleapis.com/fcm/send/laptop"));
        assertFalse(push.isSubscribed(3, 12, "https://fcm.googleapis.com/fcm/send/laptop"),
                "the same member signed in again: this sign-in has not turned them on");
    }
}
