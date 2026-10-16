package app.platform.metadata;

import java.util.Optional;

/**
 * The record page an object uses for a profile and a record type, and which rule chose it (Sprint 20, ADR-0131).
 *
 * @param recordPage the API name of the record page, empty when the object has none
 * @param rule the first rule of the fixed order that applied
 */
public record RecordPageResolution(Optional<String> recordPage, Rule rule) {

    /** The rules in the order they are tried: the most specific first. */
    public enum Rule { PROFILE_AND_RECORD_TYPE, RECORD_TYPE, PROFILE, DEFAULT, NONE }
}
