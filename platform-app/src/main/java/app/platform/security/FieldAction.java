package app.platform.security;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * What a member may do with one field of an object (ADR-0050). Stored by {@linkplain #key() key}. Edit implies read,
 * applied once after everything the member holds has been combined.
 *
 * <p>A field the member may not read is, for them, a field that does not exist: it is left out of what they read and
 * refused when they write it, with the same answer as for a field that is not there. A field permission works only
 * together with the permission on its object: reading a field needs read on the object, editing one needs create or
 * update on it.
 */
public enum FieldAction {

    /** See the value of the field. */
    READ("read", "Read"),

    /** Change the value of the field (implies read). */
    EDIT("edit", "Edit");

    private final String key;
    private final String title;

    FieldAction(String key, String title) {
        this.key = key;
        this.title = title;
    }

    /** The stable key stored in the database and sent through the API, for example {@code edit}. */
    public String key() {
        return key;
    }

    /** The name for people. */
    public String title() {
        return title;
    }

    /** The actions this one implies, not including itself. */
    public Set<FieldAction> implied() {
        return this == EDIT ? EnumSet.of(READ) : EnumSet.noneOf(FieldAction.class);
    }

    /** The action with the key, or empty for a key this release does not know. */
    public static Optional<FieldAction> fromKey(String key) {
        if (key == null) {
            return Optional.empty();
        }
        String wanted = key.strip().toLowerCase(Locale.ROOT);
        return Arrays.stream(values()).filter(action -> action.key.equals(wanted)).findFirst();
    }
}
