package app.platformapi;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Points an existing assignment at another layout (Sprint 12).
 *
 * @param layout the API name of the layout of the same object
 * @param version the version the caller last read
 */
public record UpdateLayoutAssignmentRequest(@NotBlank @Size(max = 60) String layout, @NotNull Long version) {
}
