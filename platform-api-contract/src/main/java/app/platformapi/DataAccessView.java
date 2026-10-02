package app.platformapi;

import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * What one container (a profile, an access policy, an individual grant) or a whole member may do with data
 * (Sprint 8): the permission matrix.
 *
 * @param everything whether it may do everything with every object and field (the administrator profile); the lists
 *        are then empty
 * @param objects the actions on each object
 * @param fields the actions on each field
 */
public record DataAccessView(boolean everything, @NotNull List<PermissionEntry> objects,
        @NotNull List<PermissionEntry> fields) {

    public DataAccessView {
        objects = objects == null ? List.of() : List.copyOf(objects);
        fields = fields == null ? List.of() : List.copyOf(fields);
    }

    @Override
    public String toString() {
        return "DataAccessView[redacted]";
    }
}
