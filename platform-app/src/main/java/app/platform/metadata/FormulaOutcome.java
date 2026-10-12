package app.platform.metadata;

/**
 * The value of one formula for one record (Sprint 13). A formula that could not be calculated (a division by zero, a
 * number that is too large) is reported as such: what the save does about it is the write pipeline's decision
 * (Sprint 15).
 *
 * @param value the value ({@code BigDecimal}, {@code String}, {@code Boolean}, {@code LocalDate}, {@code Instant} or
 *        {@code LocalTime}), or null when the formula has no value for this record or could not be calculated
 * @param problem why it could not be calculated, in words with the place, or null when it could
 */
public record FormulaOutcome(Object value, String problem) {

    /** The formula was calculated (the value may be null: no value). */
    public static FormulaOutcome of(Object value) {
        return new FormulaOutcome(value, null);
    }

    /** The formula could not be calculated. */
    public static FormulaOutcome failed(String problem) {
        return new FormulaOutcome(null, problem);
    }

    /** Whether the formula could be calculated. */
    public boolean calculated() {
        return problem == null;
    }

    /** Prints no value: it is data of the organization (ADR-0012). */
    @Override
    public String toString() {
        return "FormulaOutcome[" + (problem == null ? "calculated" : "failed") + "]";
    }
}
