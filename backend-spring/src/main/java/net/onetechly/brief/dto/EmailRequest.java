package net.onetechly.brief.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** A JSON body that carries one email address: {"email": "..."}. */
public record EmailRequest(@NotBlank @Size(max = 254) String email) {
}
