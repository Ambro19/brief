package net.onetechly.brief.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** The body of POST /push/subscription/check and /push/subscription/deletion (2.8): which browser. */
public record PushEndpointRequest(@NotBlank @Size(max = 1000) String endpoint) {
}
