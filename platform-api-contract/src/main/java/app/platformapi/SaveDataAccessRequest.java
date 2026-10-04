package app.platformapi;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Replaces the whole permission matrix of one container (Sprint 8): what is not listed is not allowed. An object or a
 * field the platform does not know, or an action it does not know, is refused.
 *
 * @param objects the actions on each object (an entry with no action removes the line)
 * @param fields the actions on each field
 */
public record SaveDataAccessRequest(@NotNull @Size(max = 500) List<@Valid PermissionEntry> objects,
        @NotNull @Size(max = 5000) List<@Valid PermissionEntry> fields) {

    public SaveDataAccessRequest {
        objects = objects == null ? null : List.copyOf(objects.stream().filter(java.util.Objects::nonNull).toList());
        fields = fields == null ? null : List.copyOf(fields.stream().filter(java.util.Objects::nonNull).toList());
    }

    @Override
    public String toString() {
        return "SaveDataAccessRequest[redacted]";
    }
}
