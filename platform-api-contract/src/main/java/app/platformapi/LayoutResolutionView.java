package app.platformapi;

import jakarta.validation.constraints.NotNull;

/**
 * Which layout an object uses for a profile and a record type, and why (Sprint 12).
 *
 * @param layout the API name of the layout, or null when the object has none yet
 * @param rule {@code PROFILE_AND_RECORD_TYPE}, {@code RECORD_TYPE}, {@code PROFILE}, {@code DEFAULT} or
 *        {@code NONE}: the first rule of that order that applied
 */
public record LayoutResolutionView(String layout, @NotNull String rule) {
}
