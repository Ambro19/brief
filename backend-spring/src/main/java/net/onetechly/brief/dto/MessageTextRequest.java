package net.onetechly.brief.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** The JSON body of PATCH /messages/{id}: only the new text. Other fields in the body are ignored. */
public record MessageTextRequest(
        @JsonProperty("message_text") @NotBlank @Size(max = 255) @Pattern(regexp = MessageRequest.NO_NUL) String messageText) {
}
