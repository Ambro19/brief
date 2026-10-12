package net.onetechly.brief.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** A JSON body that carries the secret of an emailed link: {"token": "..."}. */
public record TokenRequest(@NotBlank @Size(max = 200) String token) {
}
