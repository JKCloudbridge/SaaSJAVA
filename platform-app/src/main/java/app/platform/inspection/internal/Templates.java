package app.platform.inspection.internal;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The names the cluster uses for its template object and its department object, and the small pure rules about them
 * (FA4, ADR-0188). The fixed words the cluster says live here too, so that a sentence is written once. Nothing here
 * reads a record or a table.
 */
final class Templates {

    // The object of the templates and the series that numbers them are parts the application's solution file declares
    // (FA13B, ADR-0226): RecordSet.template() and RecordSet.series().
    static final String NAME = "name";
    static final String NUMBER = "number";
    static final String LEVEL = "level";
    static final String DOCUMENT_TYPE = "documentType";
    static final String CATEGORY = "category";
    /** The two parts of the number a person types freehand when the identification asks for them (FA11B). */
    static final String PART_A = "partA";
    static final String PART_B = "partB";
    /** The first serial of a new combination, typed once and cleared when the number is made (FA11B). */
    static final String SERIAL = "serial";
    static final String RETIRED = "retired";
    /** The revision in use, two digits; written by the platform when a revision is published (FA8, ADR-0202). */
    static final String REVISION = "revision";
    /** When the revision in use was published; written by the platform at that moment. */
    static final String LAST_REVISED_AT = "lastRevisedAt";
    /**
     * The application the document also belongs to, written by the platform when the number is made from the routing
     * rules of the organization (MC1.1, ADR-0259); empty means Document Management alone.
     */
    static final String APPLICATION = RecordSets.APPLICATION_FIELD;

    /** The entry, in every application of the kind, that opens a template with its change requests. */
    static final String ENTRY = "s-templates";

    /** The object that says which group belongs to a department of a location. */
    static final String DEPARTMENT_OBJECT = "DepartmentGroup";
    static final String LOCATION = "location";
    static final String DEPARTMENT = "department";
    static final String GROUP_ID = "groupId";

    /** The longest sentence a hook refusal may carry. */
    private static final int MOST_WORDS = 300;
    /** What a part may be made of: capital letters, digits and a hyphen, 1 to 10 (the server's one normalization). */
    private static final Pattern PART = Pattern.compile("[A-Z0-9-]{1,10}");
    private static final Pattern DIGITS = Pattern.compile("[0-9]{1,12}");
    /** What an author is told to fill in, by the field the number is made of. */
    private static final Map<String, String> PART_WORDS = Map.of(
            CATEGORY, "the identification",
            PART_A, "Part A (capital letters, digits or hyphen, up to 10)",
            PART_B, "Part B (capital letters, digits or hyphen, up to 10)");

    private Templates() {
    }

    /** Whether a stored value says nothing: no value or an empty text. */
    static boolean isBlank(Object value) {
        return value == null || value instanceof String text && text.isBlank();
    }

    /** Whether the value is a part as the server accepts it: capital letters, digits and hyphen, 1 to 10. */
    static boolean isPart(Object value) {
        return value instanceof String text && PART.matcher(text).matches();
    }

    /** Whether the value is a serial a person may type: 1 to 12 digits, not all zeros. */
    static boolean isSerial(Object value) {
        return value instanceof String text && DIGITS.matcher(text).matches() && Long.parseLong(text) > 0;
    }

    /** The sentence that says which parts of the number are still to fill in. */
    static String neededWords(List<String> fields) {
        String parts = String.join(" and ", fields.stream()
                .map(field -> PART_WORDS.getOrDefault(field, "a part of the number")).toList());
        return fit("Fill in " + parts + " (Edit), then approve again: its number is made from "
                + (fields.size() == 1 ? "it." : "them."));
    }

    /**
     * The revision in use as people read it: the one the platform saved on the template when it was published, else the
     * stored number of the version in use minus one (a template published before the field existed), else null when no
     * revision was ever in use.
     */
    static String revisionShown(Map<String, Object> fields, int activeVersion) {
        Object saved = fields.get(REVISION);
        if (saved instanceof String text && !text.isBlank()) {
            return text;
        }
        return activeVersion > 0 ? TemplateRevisions.show(activeVersion - 1) : null;
    }

    /** When the revision in use was published, or null. */
    static java.time.Instant lastRevisedAt(Map<String, Object> fields) {
        Object saved = fields.get(LAST_REVISED_AT);
        if (saved instanceof java.time.Instant at) {
            return at;
        }
        if (saved instanceof String text && !text.isBlank()) {
            try {
                return java.time.Instant.parse(text);
            } catch (java.time.format.DateTimeParseException notAnInstant) {
                return null;
            }
        }
        return null;
    }

    /** The words cut to the most a refusal may carry. */
    static String fit(String words) {
        return words.length() <= MOST_WORDS ? words : words.substring(0, MOST_WORDS - 3) + "...";
    }
}
