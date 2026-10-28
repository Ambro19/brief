package net.onetechly.brief.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * The body of POST /push/subscription (2.8): exactly what the browser's PushSubscription.toJSON()
 * gives, so the web app can send it as it is.
 *
 *     {"endpoint": "https://fcm.googleapis.com/fcm/send/...",
 *      "keys": {"p256dh": "BNc...", "auth": "tBH..."}}
 *
 * Other fields the browser includes (expirationTime) are ignored.
 */
public record PushSubscriptionRequest(
        @NotBlank @Size(max = 1000) String endpoint,
        @NotNull @Valid Keys keys) {

    public record Keys(@NotBlank @Size(max = 100) String p256dh, @NotBlank @Size(max = 40) String auth) {
    }
}
