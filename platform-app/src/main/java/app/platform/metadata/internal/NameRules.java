package app.platform.metadata.internal;

import app.platformapi.ApiException;
import java.util.regex.Pattern;

/**
 * Turns the name a person types for a new object or field into its API name, and cleans the labels and descriptions
 * (ADR-0058). The API name is permanent and used by code and integrations, so it is strict: letters and digits with
 * single underscores, an object starting with a capital letter and a field with a lower case one. The platform adds the
 * ending {@code __c} that marks it as the organization's own, which is why a platform name can never clash with it.
 */
final class NameRules {

    /** The ending of every API name an organization makes. */
    static final String CUSTOM_SUFFIX = "__c";

    private static final int NAME_MAX = 40;
    private static final Pattern OBJECT_NAME = Pattern.compile("[A-Z][A-Za-z0-9]*(_[A-Za-z0-9]+)*");
    private static final Pattern FIELD_NAME = Pattern.compile("[a-z][A-Za-z0-9]*(_[A-Za-z0-9]+)*");
    private static final Pattern DEFINITION_API_NAME = Pattern.compile("[A-Z][A-Za-z0-9_]{0,55}__c");
    private static final Pattern FIELD_REFERENCE = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,59}");

    private NameRules() {
    }

    /** The API name of a new custom object, for example {@code Employee__c} for {@code Employee}. */
    static String objectApiName(String typed) {
        return apiName(typed, OBJECT_NAME, "starting with a capital letter, for example Employee");
    }

    /** The API name of a new custom field, for example {@code salary__c} for {@code salary}. */
    static String fieldApiName(String typed) {
        return apiName(typed, FIELD_NAME, "starting with a lower case letter, for example salary");
    }

    /**
     * The API name of the page layout a record type uses, or null for none (blank means none). Only the shape is
     * checked here; that the layout exists is the dependency graph's question (ADR-0065).
     */
    static String layoutReference(String typed) {
        return reference("layout", typed, "Give the API name of a page layout of this object, for example "
                + "EmployeeDetails__c.");
    }

    /** An API name that refers to a layout, a record page or a record type of the organization, or null for none. */
    static String reference(String field, String typed, String problem) {
        String clean = typed == null ? "" : typed.strip();
        if (clean.isEmpty()) {
            return null;
        }
        if (!DEFINITION_API_NAME.matcher(clean).matches()) {
            throw ApiException.validation(field, problem);
        }
        return clean;
    }

    /** A label: not empty, at most 80 characters, no control characters. */
    static String label(String field, String text) {
        String clean = plain(field, text);
        if (clean.isEmpty()) {
            throw ApiException.validation(field, "Must not be empty.");
        }
        if (clean.length() > 80) {
            throw ApiException.validation(field, "Must be at most 80 characters.");
        }
        return clean;
    }

    /**
     * A message a person typed for other people to read (the error message of a validation rule): not empty, at most
     * 255 characters, no control characters.
     */
    static String message(String field, String text) {
        String clean = plain(field, text);
        if (clean.isEmpty()) {
            throw ApiException.validation(field, "Must not be empty.");
        }
        if (clean.length() > 255) {
            throw ApiException.validation(field, "Must be at most 255 characters.");
        }
        return clean;
    }

    /**
     * The API name of a field named by a request (the field a validation rule shows its message on), or null for none
     * (blank means none). Only the shape is checked here; that the field exists is the dependency graph's question
     * (ADR-0065).
     */
    static String fieldReference(String field, String typed) {
        String clean = typed == null ? "" : typed.strip();
        if (clean.isEmpty()) {
            return null;
        }
        if (!FIELD_REFERENCE.matcher(clean).matches()) {
            throw ApiException.validation(field, "Give the API name of a field of this object, for example "
                    + "closeDate or amount__c.");
        }
        return clean;
    }

    /** A description: at most 500 characters, no control characters, may be empty. */
    static String description(String text) {
        String clean = plain("description", text);
        if (clean.length() > 500) {
            throw ApiException.validation("description", "Must be at most 500 characters.");
        }
        return clean;
    }

    private static String apiName(String typed, Pattern shape, String how) {
        String clean = typed == null ? "" : typed.strip();
        if (clean.endsWith(CUSTOM_SUFFIX)) {
            throw ApiException.validation("name", "Type the name without the ending __c: the platform adds it.");
        }
        if (clean.isEmpty() || clean.length() > NAME_MAX || !shape.matcher(clean).matches()) {
            throw ApiException.validation("name", "Use letters and digits, with single underscores between words, "
                    + how + " (at most " + NAME_MAX + " characters).");
        }
        return clean + CUSTOM_SUFFIX;
    }

    private static String plain(String field, String text) {
        String clean = text == null ? "" : text.strip();
        for (int i = 0; i < clean.length(); i++) {
            if (Character.isISOControl(clean.charAt(i))) {
                throw ApiException.validation(field, "Must not contain control characters.");
            }
        }
        return clean;
    }
}
