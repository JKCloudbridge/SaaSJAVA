package app.platformapi;

import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;

/**
 * The mapping of one object of one connection (Sprint 28, ADR-0217) with its versions.
 *
 * @param id the identifier
 * @param connection the name of the connection
 * @param object the API name of the object
 * @param entity the kind of entity on the external side
 * @param live the version that is live, or null when the mapping is stopped or was never published
 * @param draft the draft, or null when there is none
 * @param history the versions that were live before, newest first
 * @param canPublish whether the draft can be made live now
 */
public record MappingView(@NotNull UUID id, @NotNull String connection, @NotNull String object,
        @NotNull String entity, MappingVersionView live, MappingVersionView draft,
        @NotNull List<MappingVersionView> history, @NotNull Boolean canPublish) {

    public MappingView {
        history = List.copyOf(history);
    }

    @Override
    public String toString() {
        return "MappingView[redacted]";
    }
}
