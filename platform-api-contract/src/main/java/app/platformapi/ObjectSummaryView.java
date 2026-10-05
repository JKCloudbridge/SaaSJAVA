package app.platformapi;

import jakarta.validation.constraints.NotNull;

/**
 * One line of the list of objects (Sprint 10).
 *
 * @param apiName the permanent name used by code and integrations
 * @param label the label people read
 * @param pluralLabel the label for many
 * @param kind {@code STANDARD} or {@code CUSTOM}
 * @param managedBy the part of the platform that owns the records of a standard object, or null
 * @param fieldCount how many fields the object has (system, standard and custom)
 * @param customFieldCount how many of them the organization made
 */
public record ObjectSummaryView(@NotNull String apiName, @NotNull String label, @NotNull String pluralLabel,
        @NotNull String kind, String managedBy, @NotNull Integer fieldCount, @NotNull Integer customFieldCount) {

    @Override
    public String toString() {
        return "ObjectSummaryView[redacted]";
    }
}
