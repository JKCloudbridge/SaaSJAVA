package app.platform.security;

import java.util.Arrays;
import java.util.Optional;

/**
 * How much a share or a sharing rule gives of one record (ADR-0116, ADR-0117). The stored form is the number, so that
 * "at least this level" is one comparison in the statement of a list.
 */
public enum ShareLevel {

    /** The grantee may read the record. */
    READ(1),

    /** The grantee may read and change the record. */
    READ_WRITE(2);

    private final int value;

    ShareLevel(int value) {
        this.value = value;
    }

    /** The number stored in the database. */
    public int value() {
        return value;
    }

    /** Whether this level gives everything the other gives. */
    public boolean covers(ShareLevel other) {
        return value >= other.value;
    }

    /** The level that is stored as this number. */
    public static Optional<ShareLevel> ofValue(int value) {
        return Arrays.stream(values()).filter(level -> level.value == value).findFirst();
    }

    /** The level a record action needs from a share, or empty when no share gives the action. */
    public static Optional<ShareLevel> neededFor(RecordAction action) {
        return switch (action) {
            case READ -> Optional.of(READ);
            case EDIT -> Optional.of(READ_WRITE);
            case DELETE -> Optional.empty();
            default -> Optional.empty();
        };
    }
}
