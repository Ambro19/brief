package net.onetechly.brief.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** What other members may see of an account: id and username, never the password. */
public record PublicAccount(
        @JsonProperty("account_id") Integer accountId,
        String username) {
}
