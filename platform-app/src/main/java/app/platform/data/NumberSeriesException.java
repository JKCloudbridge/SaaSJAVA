package app.platform.data;

/**
 * Why a number could not be made (FA2A, ADR-0184). The reason is a stable key and the words are fixed: they never
 * repeat
 * a code, a value or a number of a record, because those are text of the organization.
 */
public final class NumberSeriesException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Why. */
    public enum Reason {
        /** No series has this key. */
        UNKNOWN_SERIES("The number series is not declared."),
        /** The record's selector has no format in the series. */
        NO_FORMAT("The series has no format for this kind of record."),
        /** A part the format needs has no value on the record. */
        PART_MISSING("A part of the number is missing on the record."),
        /** A part is not a short code, so it cannot be a part of a number. */
        PART_NOT_A_CODE("A part of the number is not a short code.");

        private final String words;

        Reason(String words) {
            this.words = words;
        }

        String words() {
            return words;
        }
    }

    private final Reason reason;

    /** The refusal for a reason. */
    public NumberSeriesException(Reason reason) {
        super(reason.words());
        this.reason = reason;
    }

    /** Why the number could not be made. */
    public Reason reason() {
        return reason;
    }
}
