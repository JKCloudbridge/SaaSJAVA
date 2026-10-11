package app.platform.metadata;

import java.util.Optional;

/**
 * The layout an object uses for a profile and a record type, and which rule chose it (Sprint 12, ADR-0071).
 *
 * @param layout the API name of the layout, empty when the object has no layout
 * @param rule the first rule of the fixed order that applied
 */
public record LayoutResolution(Optional<String> layout, Rule rule) {

    /** The rules in the order they are tried: the most specific first. */
    public enum Rule { PROFILE_AND_RECORD_TYPE, RECORD_TYPE, PROFILE, DEFAULT, NONE }
}
