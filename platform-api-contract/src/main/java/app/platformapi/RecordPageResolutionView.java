package app.platformapi;

import jakarta.validation.constraints.NotNull;

/**
 * Which record page an object uses for a profile and a record type, and why (Sprint 20).
 *
 * @param recordPage the API name of the record page, or null when the object has none yet
 * @param rule {@code PROFILE_AND_RECORD_TYPE}, {@code RECORD_TYPE}, {@code PROFILE}, {@code DEFAULT} or
 *        {@code NONE}: the first rule of that order that applied
 */
public record RecordPageResolutionView(String recordPage, @NotNull String rule) {
}
