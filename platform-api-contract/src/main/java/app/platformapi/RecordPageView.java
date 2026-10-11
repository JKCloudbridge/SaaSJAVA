package app.platformapi;

import jakarta.validation.constraints.NotNull;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One record page as the setup screens show it (Sprint 12).
 *
 * @param apiName the permanent name, for example {@code EmployeePage__c}
 * @param objectApiName the object it belongs to
 * @param label the label people read
 * @param description what it is for
 * @param definition the structure (the schema {@code RecordPageDefinition} of this document)
 * @param version the version to send back when changing it
 */
public record RecordPageView(@NotNull String apiName, @NotNull String objectApiName, @NotNull String label,
        @NotNull String description, @NotNull Map<String, Object> definition, @NotNull Long version) {

    /** Copies the definition, so that it cannot be changed from outside. */
    public RecordPageView {
        definition = definition == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(definition));
    }

    @Override
    public String toString() {
        return "RecordPageView[redacted]";
    }
}
