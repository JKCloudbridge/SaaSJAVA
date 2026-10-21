package app.platformapi;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * A role or a public group a record can be shared with (Functional track FA2B): its identifier and the name the
 * organization gave it, and nothing about the people in it.
 *
 * @param id the role or group
 * @param label its name
 */
public record ShareTargetView(@NotNull UUID id, @NotNull String label) {
}
