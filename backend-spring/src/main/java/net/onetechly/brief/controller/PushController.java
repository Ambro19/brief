package net.onetechly.brief.controller;

import jakarta.validation.Valid;
import net.onetechly.brief.dto.PushEndpointRequest;
import net.onetechly.brief.dto.PushSubscriptionRequest;
import net.onetechly.brief.security.AuthGuard;
import net.onetechly.brief.service.PushNotifications;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Notifications on this device (2.8). See service/PushNotifications for how they work.
 *
 *   GET  /push/key                      200 {"public_key": ...}; 404 when notifications are off
 *   POST /push/subscription             200; stores this browser's subscription for this session
 *   POST /push/subscription/check       200 {"on": true|false}: is this browser stored for this session?
 *   POST /push/subscription/deletion    200; forgets this browser's subscription
 *   POST /push/test                     200 {"sent": n}; a test notification to this session's devices
 *
 * The public key is public by nature: GET /push/key needs no token. Everything else needs one
 * (401). Turning notifications on, and the test, need a token with a session (409 for a token
 * from 2.7 or earlier); 400 for an endpoint that is not at a known push service; 404 when the
 * server has no VAPID keys; 429 for a second test within a minute.
 *
 * POST to a "deletion" address rather than DELETE with a body, as for accounts (2.5): some
 * proxies drop the body of a DELETE.
 */
@RestController
public class PushController {

    private final PushNotifications push;
    private final AuthGuard authGuard;

    public PushController(PushNotifications push, AuthGuard authGuard) {
        this.push = push;
        this.authGuard = authGuard;
    }

    @GetMapping("/push/key")
    public Map<String, String> key() {
        return Map.of("public_key", push.publicKey());
    }

    @PostMapping("/push/subscription")
    public Map<String, Boolean> subscribe(@Valid @RequestBody PushSubscriptionRequest request) {
        int accountId = authGuard.requireSignedIn();
        push.subscribe(accountId, authGuard.currentSessionId(), request.endpoint(), request.keys().p256dh(), request.keys().auth());
        return Map.of("on", true);
    }

    @PostMapping("/push/subscription/check")
    public Map<String, Boolean> check(@Valid @RequestBody PushEndpointRequest request) {
        int accountId = authGuard.requireSignedIn();
        return Map.of("on", push.isOn() && push.isSubscribed(accountId, authGuard.currentSessionId(), request.endpoint()));
    }

    @PostMapping("/push/subscription/deletion")
    public ResponseEntity<Void> unsubscribe(@Valid @RequestBody PushEndpointRequest request) {
        int accountId = authGuard.requireSignedIn();
        push.unsubscribe(accountId, request.endpoint());
        return ResponseEntity.ok().build();
    }

    @PostMapping("/push/test")
    public Map<String, Integer> test() {
        int accountId = authGuard.requireSignedIn();
        return Map.of("sent", push.test(accountId, authGuard.currentSessionId()));
    }
}
