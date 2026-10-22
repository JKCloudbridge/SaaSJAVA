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

    /** The header record of a template. */
    static final String OBJECT = "InspectionTemplate";
    /** The number series that numbers the templates (numbering.yml). */
    static final String SERIES = "document";
    static final String NAME = "name";
    static final String NUMBER = "number";
    static final String CATEGORY = "category";
    static final String SUB_CATEGORY = "subCategory";
    static final String PART_CODE = "partCode";
    static final String PLAN_YEAR = "planYear";
    static final String RETIRED = "retired";

    /** The application and the entry of it that opens a template with its change requests (a notice leads there). */
    static final String APPLICATION = "inspection";
    static final String ENTRY = "s-templates";

    /** The object that says which group belongs to a department of a location. */
    static final String DEPARTMENT_OBJECT = "DepartmentGroup";
    static final String LOCATION = "location";
    static final String DEPARTMENT = "department";
    static final String GROUP_ID = "groupId";

    /** The longest sentence a hook refusal may carry. */
    private static final int MOST_WORDS = 300;
    private static final Pattern YEAR = Pattern.compile("[0-9]{4}");
    /** What an author is told to fill in, by the field the number is made of. */
    private static final Map<String, String> PART_WORDS = Map.of(
            SUB_CATEGORY, "the sub-category",
            PART_CODE, "the part code (letters, digits, hyphen or underscore, up to 10)",
            PLAN_YEAR, "the plan year");

    private Templates() {
    }

    /** Whether a stored value says nothing: no value or an empty text. */
    static boolean isBlank(Object value) {
        return value == null || value instanceof String text && text.isBlank();
    }

    /** Whether the value is a four-digit year. */
    static boolean isYear(Object value) {
        return value instanceof String text && YEAR.matcher(text).matches();
    }

    /** The sentence that says which parts of the number are still to fill in. */
    static String neededWords(List<String> fields) {
        String parts = String.join(" and ", fields.stream()
                .map(field -> PART_WORDS.getOrDefault(field, "a part of the number")).toList());
        return fit("Fill in " + parts + " before sending the template for approval: its number is made from "
                + (fields.size() == 1 ? "it." : "them."));
    }

    /** The words cut to the most a refusal may carry. */
    static String fit(String words) {
        return words.length() <= MOST_WORDS ? words : words.substring(0, MOST_WORDS - 3) + "...";
    }
}
