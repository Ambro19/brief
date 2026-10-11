package net.onetechly.brief.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * The JSON body of POST /register and POST /login.
 *
 * The constraints are checked only where the controller asks for it with @Valid - that is,
 * on registration. Login deliberately does not validate: a wrong or missing value there is
 * simply a failed login (401), not a malformed request (400).
 *
 * The @Pattern on the username (2.3) refuses the one character PostgreSQL cannot store in
 * text; see MessageRequest.NO_NUL. The password needs no such rule: only its hash is stored.
 */
public record Credentials(
        @NotBlank @Size(max = 255) @Pattern(regexp = MessageRequest.NO_NUL) String username,
        @NotNull @Size(min = 4, max = 1000) String password) {
}
