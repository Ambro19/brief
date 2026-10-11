package net.onetechly.brief.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * What register and login answer with. The contract says to echo the account back, including
 * the password the caller just sent. It is the caller's own input - never the stored hash.
 */
public record AccountResponse(
        @JsonProperty("account_id") Integer accountId,
        String username,
        String password) {
}
