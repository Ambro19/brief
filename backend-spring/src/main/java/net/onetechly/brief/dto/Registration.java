package net.onetechly.brief.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * The JSON body of POST /register (2.4). Until 2.3 registering used the same two fields as
 * signing in (Credentials). It now has two more, both optional in the JSON:
 *
 *   email        required in strict mode, optional in compat mode (see EmailPolicy)
 *   invitation   the secret from an invitation link (2.5): required when the board is closed
 *                (see BoardAccess and InvitationService). In 2.4 this was "invite_code", one
 *                code shared by everyone; a request that still sends that field has it ignored.
 *
 * A body with only username and password is still valid JSON for this record, so the original
 * training contract keeps working unchanged in compat mode.
 *
 * The constraints on username and password are the ones Credentials has. Whether the email
 * has the shape of an address is checked by EmailPolicy, in the service: the answer depends
 * on the mode, which an annotation cannot know.
 */
public record Registration(
        @NotBlank @Size(max = 255) @Pattern(regexp = MessageRequest.NO_NUL) String username,
        @NotNull @Size(min = 4, max = 1000) String password,
        @Size(max = 254) String email,
        @Size(max = 200) String invitation) {

    /** A registration with a name and a password only, as in the original contract. */
    public static Registration of(String username, String password) {
        return new Registration(username, password, null, null);
    }

    /** A registration with a name, a password and an email address, on an open board. */
    public static Registration of(String username, String password, String email) {
        return new Registration(username, password, email, null);
    }
}
