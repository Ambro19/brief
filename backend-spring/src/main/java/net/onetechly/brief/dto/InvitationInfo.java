package net.onetechly.brief.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * What the web app may show to someone who opened an invitation link (2.5): who invited them,
 * to which address (null for a link that was passed on), and until when it works.
 */
public record InvitationInfo(
        String inviter,
        String email,
        @JsonProperty("expires_at") long expiresAt) {
}
