package net.onetechly.brief.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * An invitation as its sender sees it in the list (2.5). The secret is never part of it: a
 * mailed invitation's link exists only in the invitee's inbox.
 *
 *   status   "pending" (can still be used), "used" (an account was created with it),
 *            "expired" (not used in time)
 */
public record InvitationView(
        @JsonProperty("invitation_id") Integer invitationId,
        String email,
        @JsonProperty("created_at") long createdAt,
        @JsonProperty("expires_at") long expiresAt,
        String status,
        @JsonProperty("joined_as") String joinedAs) {
}
