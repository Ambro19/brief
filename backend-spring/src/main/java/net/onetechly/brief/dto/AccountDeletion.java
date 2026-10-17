package net.onetechly.brief.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** The body of POST /accounts/{id}/deletion (2.5): the password, asked again before anything is deleted. */
public record AccountDeletion(@NotNull @Size(max = 1000) String password) {
}
