package app.platformapi;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * A public group as it appears on a member or inside another group (Sprint 8).
 *
 * @param id the group
 * @param name the group's name
 * @param direct whether the member is in the group themselves; false when they are in it through a nested group
 */
public record GroupRef(@NotNull UUID id, @NotNull String name, boolean direct) {

    @Override
    public String toString() {
        return "GroupRef[redacted]";
    }
}
