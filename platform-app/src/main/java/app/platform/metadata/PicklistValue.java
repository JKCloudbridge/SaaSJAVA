package app.platform.metadata;

/**
 * One choice of a picklist. The value is what records store and never changes; the label is shown to people; an
 * inactive value stays known (existing records may hold it) but cannot be chosen for new ones.
 *
 * @param value the stored value
 * @param label the label shown to people
 * @param active whether new records can choose it
 */
public record PicklistValue(String value, String label, boolean active) {

    /** Prints nothing typed: a label is text a person chose (ADR-0012). */
    @Override
    public String toString() {
        return "PicklistValue[redacted]";
    }
}
