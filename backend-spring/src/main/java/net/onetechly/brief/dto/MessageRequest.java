package net.onetechly.brief.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * The JSON body of POST /messages.
 *
 * Bean Validation enforces the text rules before the controller method runs:
 * not blank, at most 255 characters, and (2.3) without the character with code 0.
 * Whether posted_by is a real account is a database question, so MessageService checks that.
 */
public record MessageRequest(
        @JsonProperty("posted_by") Integer postedBy,
        @JsonProperty("message_text") @NotBlank @Size(max = 255) @Pattern(regexp = MessageRequest.NO_NUL) String messageText,
        @JsonProperty("time_posted_epoch") Long timePostedEpoch) {

    /**
     * A pattern that fits any text except one containing the character with code 0 ("NUL").
     * Nobody types that character, but a program can send it, and PostgreSQL cannot store it
     * in a text column: the insert would fail and the caller would get a confusing 409.
     * Refusing it here makes it an ordinary 400 on every database. Line breaks are allowed.
     */
    public static final String NO_NUL = "[^\\u0000]*";
}
