package app.platform.metadata;

import java.util.List;
import java.util.Optional;

/**
 * The kinds of value a field can hold (ADR-0060). The list is the V1 list of the requirements; adding a type later is a
 * new constant here, a new configuration in {@link FieldConfiguration}, a rule in the metadata module and a line in the
 * check of migration V031 (the guide {@code docs/metadata-guide.md} walks through it).
 *
 * <p>Each type says which settings it takes and which constraints make sense for it, so that the rules live in one
 * place and the object manager builds its form from this description instead of repeating it.
 */
public enum FieldType {

    /** One line of text. */
    TEXT("Text", "A short line of text.", List.of("maxLength"), true, true, true, false),

    /** Several lines of text. */
    LONG_TEXT("Long text", "Several lines of text, such as notes.", List.of("maxLength"), true, false, true, false),

    /** A whole number. */
    NUMBER("Number", "A whole number.", List.of("digits"), true, true, true, false),

    /** A number with a fraction. */
    DECIMAL("Decimal", "A number with decimals.", List.of("precision", "scale"), true, true, true, false),

    /** An amount of money. */
    CURRENCY("Currency", "An amount of money.", List.of("precision", "scale"), true, false, true, false),

    /** A percentage. */
    PERCENT("Percent", "A percentage.", List.of("precision", "scale"), true, false, true, false),

    /** Yes or no. */
    BOOLEAN("Checkbox", "Yes or no.", List.of(), false, false, true, false),

    /** A calendar day. */
    DATE("Date", "A calendar day.", List.of(), true, false, true, false),

    /** A day and a time. */
    DATETIME("Date and time", "A day with a time of day.", List.of(), true, false, true, false),

    /** A time of day. */
    TIME("Time", "A time of day.", List.of(), true, false, true, false),

    /** An e-mail address. */
    EMAIL("E-mail", "An e-mail address.", List.of(), true, true, true, false),

    /** A telephone number. */
    PHONE("Phone", "A telephone number.", List.of(), true, true, true, false),

    /** A web address. */
    URL("URL", "A web address.", List.of(), true, true, true, false),

    /** One choice out of a list. */
    PICKLIST("Picklist", "One choice out of a list of values.", List.of("values"), true, false, true, false),

    /** Several choices out of a list. */
    MULTI_PICKLIST("Multi-select picklist", "Several choices out of a list of values.", List.of("values"), true,
            false, true, false),

    /** A reference to a record of another object, which can exist without it. */
    LOOKUP("Lookup", "A link to a record of another object. The record can exist without it.",
            List.of("targetObject"), true, false, false, false),

    /** A reference to a record of another object that owns this one. */
    MASTER_DETAIL("Master-detail", "A link to a record of another object that owns this one: always required.",
            List.of("targetObject"), false, false, false, false),

    /** A value calculated from other fields. Stored only until Sprint 13 and 15. */
    FORMULA("Formula", "A value worked out from other fields (the calculation arrives in a later release).",
            List.of("expression", "resultType"), false, false, false, true),

    /** A number the platform counts up for every new record. Counting arrives with the records. */
    AUTO_NUMBER("Auto-number", "A running number the platform gives every new record, for example INV-000123.",
            List.of("prefix", "startAt", "width"), false, false, false, true);

    private final String label;
    private final String description;
    private final List<String> settings;
    private final boolean allowsRequired;
    private final boolean allowsUnique;
    private final boolean allowsDefault;
    private final boolean calculated;

    FieldType(String label, String description, List<String> settings, boolean allowsRequired, boolean allowsUnique,
            boolean allowsDefault, boolean calculated) {
        this.label = label;
        this.description = description;
        this.settings = settings;
        this.allowsRequired = allowsRequired;
        this.allowsUnique = allowsUnique;
        this.allowsDefault = allowsDefault;
        this.calculated = calculated;
    }

    /** The name people read. */
    public String label() {
        return label;
    }

    /** What the type is for. */
    public String description() {
        return description;
    }

    /** The names of the settings the type takes (the components of the API's field settings). */
    public List<String> settings() {
        return settings;
    }

    /** Whether a field of this type can be required (a master-detail always is, and a calculated value never). */
    public boolean allowsRequired() {
        return allowsRequired;
    }

    /** Whether a field of this type can be unique. */
    public boolean allowsUnique() {
        return allowsUnique;
    }

    /** Whether a field of this type can have a default value. */
    public boolean allowsDefault() {
        return allowsDefault;
    }

    /** Whether the platform works the value out, instead of a person typing it. */
    public boolean calculated() {
        return calculated;
    }

    /** Whether the field points at another object. */
    public boolean isReference() {
        return this == LOOKUP || this == MASTER_DETAIL;
    }

    /** Whether the field holds one of a list of values. */
    public boolean isPicklist() {
        return this == PICKLIST || this == MULTI_PICKLIST;
    }

    /** The type with this code, if the release knows it. */
    public static Optional<FieldType> fromCode(String code) {
        if (code == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(valueOf(code));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
