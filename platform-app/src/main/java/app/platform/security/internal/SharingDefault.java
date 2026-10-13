package app.platform.security.internal;

import java.util.Arrays;
import java.util.Optional;

/**
 * The organization-wide default of an object (ADR-0116): how much of the records of the object every member who may
 * use the object reaches, before the owner, the hierarchy, the rules and the shares add to it.
 */
enum SharingDefault {

    /** Members reach only what the other mechanisms give them. */
    PRIVATE("PRIVATE"),

    /** Every member reads every record; changing needs the other mechanisms. */
    PUBLIC_READ("PUBLIC_READ"),

    /** Every member reads and changes every record: how the platform behaved before Sprint 17. */
    PUBLIC_READ_WRITE("PUBLIC_READ_WRITE");

    private final String key;

    SharingDefault(String key) {
        this.key = key;
    }

    /** The stored and sent form. */
    String key() {
        return key;
    }

    /** The default with the key. */
    static Optional<SharingDefault> ofKey(String key) {
        return Arrays.stream(values()).filter(value -> value.key.equals(key)).findFirst();
    }
}
