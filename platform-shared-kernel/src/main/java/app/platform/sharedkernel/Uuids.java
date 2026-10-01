package app.platform.sharedkernel;

import java.time.Instant;
import java.util.UUID;

/**
 * Identifier conventions (ADR-0010): every row identifier is a version 7 UUID. The database default
 * generates them for rows created in SQL; this class creates them where the identifier is needed before the
 * insert (events, correlation, test data).
 */
public final class Uuids {

    private static final UuidV7Generator GENERATOR = UuidV7Generator.system();

    private Uuids() {
    }

    /** A new time-ordered identifier. */
    public static UUID v7() {
        return GENERATOR.next();
    }

    /** True when the value is a version 7 UUID with the standard variant. */
    public static boolean isV7(UUID value) {
        return value.version() == 7 && value.variant() == 2;
    }

    /**
     * The creation time embedded in a version 7 UUID, with millisecond precision.
     *
     * @throws IllegalArgumentException when the value is not a version 7 UUID
     */
    public static Instant timestampOf(UUID value) {
        if (!isV7(value)) {
            throw new IllegalArgumentException("Not a version 7 UUID");
        }
        return Instant.ofEpochMilli(value.getMostSignificantBits() >>> 16);
    }
}
