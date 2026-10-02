package app.platformapi;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * One line of a permission matrix (Sprint 8): the actions allowed on one object (the key is the object key) or on one
 * field (the key is {@code <object key>.<field key>}).
 *
 * @param key the object key or the field key
 * @param actions the action keys: {@code read, create, update, delete, view-all, modify-all} for an object,
 *        {@code read, edit} for a field
 */
public record PermissionEntry(@NotBlank @Size(max = 121) String key,
        @NotNull @Size(max = 6) List<@NotBlank @Size(max = 20) String> actions) {

    public PermissionEntry {
        actions = actions == null ? null : List.copyOf(actions.stream().filter(java.util.Objects::nonNull).toList());
    }

    @Override
    public String toString() {
        return "PermissionEntry[redacted]";
    }
}
