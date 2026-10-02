package app.platformapi;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Gives a member a licence of a type, moving them from the type they hold, if any.
 *
 * @param licenceType the licence type key
 */
public record SetLicenceRequest(@NotBlank @Size(max = 40) String licenceType) {
}
