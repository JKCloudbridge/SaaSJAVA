package app.platform.sharedkernel.access;

import java.util.List;

/**
 * The scopes of the public API (Sprint 31, ADR-0270): the one catalogue that the three places that must agree read
 * from. The table of public operations (identity) says which scope an operation needs, the registry of connected
 * applications (identity) says which scopes an application may hold, and the contract document of the public API
 * (publicapi) says which scope each operation needs. A scope that existed in one of them alone would be a hole or a
 * lie, so none of them writes a key of its own.
 *
 * <p><strong>Scope is necessary, never sufficient.</strong> A scope only lets a request reach an operation; the member
 * behind the token still decides every object, field and record. {@link #RECORDS_WRITE} does not include
 * {@link #RECORDS_READ}.
 */
public final class ApiScopes {

    /** List the objects the member may read and describe their fields. */
    public static final String OBJECTS_READ = "objects.read";

    /** Read one record and query records. */
    public static final String RECORDS_READ = "records.read";

    /** Create, change, delete and restore records, singly or in bulk. */
    public static final String RECORDS_WRITE = "records.write";

    /**
     * One scope.
     *
     * @param key the key software asks for
     * @param title a name for people
     * @param description what it allows, in plain words
     */
    public record Scope(String key, String title, String description) {
    }

    /** Every scope, in the order the screens and the contract document show them. */
    public static final List<Scope> ALL = List.of(
            new Scope(OBJECTS_READ, "Read objects",
                    "List the objects the member may read and describe their fields. Reads no record."),
            new Scope(RECORDS_READ, "Read records",
                    "Read one record and query records. Does not include describing objects or writing."),
            new Scope(RECORDS_WRITE, "Write records",
                    "Create, change, delete and restore records, one at a time or in bulk. Does not include reading "
                            + "records or describing objects."));

    private ApiScopes() {
    }
}
