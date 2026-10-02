package app.platformapi;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Gives one member one ability directly (Sprint 7). The note is typed by a person, so it never prints.
 *
 * @param ability the ability key
 * @param reason a short note why, optional
 */
public record GrantAbilityRequest(@NotBlank @Size(max = 60) String ability, @Size(max = 200) String reason) {

    @Override
    public String toString() {
        return "GrantAbilityRequest[redacted]";
    }
}
