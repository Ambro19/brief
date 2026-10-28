package net.onetechly.brief.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One sign-in, as GET /sessions shows it to its own member (2.8). Times are seconds since 1970,
 * like time_posted_epoch. "current" marks the session the request itself was made with.
 */
public record SessionView(
        @JsonProperty("session_id") int sessionId,
        String device,
        boolean stay,
        @JsonProperty("created_at") long createdAt,
        @JsonProperty("last_used_at") long lastUsedAt,
        @JsonProperty("expires_at") long expiresAt,
        boolean current) {
}
