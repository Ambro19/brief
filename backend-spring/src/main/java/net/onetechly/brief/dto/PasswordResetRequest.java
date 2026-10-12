package net.onetechly.brief.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** The JSON body of POST /password/reset: the secret of the emailed link, and the new password. */
public record PasswordResetRequest(
        @NotBlank @Size(max = 200) String token,
        @NotNull @Size(min = 4, max = 1000) String password) {
}
