package app.platform.security;

/**
 * The refusal that an organization must always keep one active member who can manage access (ADR-0044). The database
 * enforces it when a transaction commits, so a caller that changes what members hold can meet it as an exception from
 * the commit; this class recognizes that exception and gives the words for the person.
 */
public final class LastAccessManager {

    /** What the person is told. */
    public static final String MESSAGE =
            "The organization must keep at least one active member who can manage access. Give another member that "
                    + "ability first.";

    /** The text the database guard puts in its error; never shown to a person. */
    private static final String GUARD_TEXT = "last member who can manage access";

    private LastAccessManager() {
    }

    /** Whether the throwable, or one of its causes, is the database guard refusing to remove the last holder. */
    public static boolean isViolation(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause() == cause ? null : cause.getCause()) {
            String text = cause.getMessage();
            if (text != null && text.contains(GUARD_TEXT)) {
                return true;
            }
        }
        return false;
    }
}
