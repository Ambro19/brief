package net.onetechly.brief.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * What a member may see of their own account: GET /me (2.4). Unlike PublicAccount it includes
 * the email address, so it is only ever sent to the account's owner.
 *
 * can_post is false while an address has been given and not yet confirmed. is_owner (2.5) is
 * true for the member who runs the board and can invite people (see BoardOwner).
 */
public record Profile(
        @JsonProperty("account_id") Integer accountId,
        String username,
        String email,
        @JsonProperty("email_verified") boolean emailVerified,
        @JsonProperty("can_post") boolean canPost,
        @JsonProperty("is_owner") boolean isOwner) {
}
