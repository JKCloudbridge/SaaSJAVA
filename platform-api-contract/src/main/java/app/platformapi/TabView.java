package app.platformapi;

import jakarta.validation.constraints.NotNull;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One a tab as the setup screens show it (Sprint 18).
 *
 * @param apiName the permanent name, for example {@code Reports__c}
 * @param label the label people read
 * @param description what it is for
 * @param definition the content (schema {@code TabDefinition})
 * @param version the version to send back when changing it
 */
public record TabView(@NotNull String apiName, @NotNull String label, @NotNull String description,
        @NotNull Map<String, Object> definition, @NotNull Long version) {

    /** Copies the definition, so that it cannot be changed from outside. */
    public TabView {
        definition = definition == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(definition));
    }

    @Override
    public String toString() {
        return "TabView[redacted]";
    }
}
