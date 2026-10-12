package app.platform.metadata;

/**
 * A validation rule of an object (Sprint 13, ADR-0078): a condition in the expression language that is <strong>true
 * when the record must be rejected</strong>, and the message the person who is rejected reads. The rules of an object
 * are judged when a record is saved (Sprint 15); the browser may judge them too for immediate feedback (Sprint 19), but
 * the backend is the one that decides.
 *
 * @param objectApiName the object it belongs to
 * @param apiName the permanent name, ending in {@code __c}
 * @param label the label people read
 * @param description what the rule is for
 * @param expression the condition; true rejects the save
 * @param errorMessage the message of the rejection, typed by the organization
 * @param errorField the field the message is shown on, or null for the record as a whole
 * @param active whether the rule is judged
 * @param version the version, for concurrent changes
 */
public record ValidationRule(String objectApiName, String apiName, String label, String description, String expression,
        String errorMessage, String errorField, boolean active, long version) {

    /** Prints the identity only: the expression and the message are text a person typed (ADR-0012). */
    @Override
    public String toString() {
        return "ValidationRule[" + objectApiName + "." + apiName + "]";
    }
}
