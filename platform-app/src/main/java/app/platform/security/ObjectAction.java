package app.platform.security;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * What a member may do with the data of one object (ADR-0049). Stored by {@linkplain #key() key}.
 *
 * <p>Some actions imply others, so that no combination can say something that makes no sense (nobody can update what
 * they cannot see). The implications are applied once, after everything the member holds has been combined
 * ({@link DataAccess#closed()}), so they never make the result depend on the order of the inputs:
 * <ul>
 *   <li>create, update, delete and view-all imply read;</li>
 *   <li>modify-all implies read, create, update, delete and view-all.</li>
 * </ul>
 *
 * <p>{@link #VIEW_ALL} and {@link #MODIFY_ALL} are stored and shown now and change nothing else until record-level
 * security exists (Sprint 17): then they will let a member see, or change, every record of the object whatever the
 * record-level rules say. Today no record rule exists, so read and view-all answer the same question.
 */
public enum ObjectAction {

    /** See records of the object. */
    READ("read", "Read"),

    /** Create records of the object. */
    CREATE("create", "Create"),

    /** Change records of the object. */
    UPDATE("update", "Update"),

    /** Delete records of the object. */
    DELETE("delete", "Delete"),

    /** See every record of the object, whatever the record-level rules say (effective from Sprint 17). */
    VIEW_ALL("view-all", "View all"),

    /** See, change and delete every record of the object, whatever the record-level rules say (from Sprint 17). */
    MODIFY_ALL("modify-all", "Modify all");

    private final String key;
    private final String title;

    ObjectAction(String key, String title) {
        this.key = key;
        this.title = title;
    }

    /** The stable key stored in the database and sent through the API, for example {@code view-all}. */
    public String key() {
        return key;
    }

    /** The name for people. */
    public String title() {
        return title;
    }

    /** The actions this one implies, not including itself. */
    public Set<ObjectAction> implied() {
        return switch (this) {
            case READ -> EnumSet.noneOf(ObjectAction.class);
            case CREATE, UPDATE, DELETE, VIEW_ALL -> EnumSet.of(READ);
            case MODIFY_ALL -> EnumSet.of(READ, CREATE, UPDATE, DELETE, VIEW_ALL);
        };
    }

    /** The action with the key, or empty for a key this release does not know. */
    public static Optional<ObjectAction> fromKey(String key) {
        if (key == null) {
            return Optional.empty();
        }
        String wanted = key.strip().toLowerCase(Locale.ROOT);
        return Arrays.stream(values()).filter(action -> action.key.equals(wanted)).findFirst();
    }
}
