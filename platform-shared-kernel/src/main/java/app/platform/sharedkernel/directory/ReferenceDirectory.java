package app.platform.sharedkernel.directory;

import java.util.UUID;

/**
 * The one question the record store may ask a module about the records that module owns (Sprint 15A, ADR-0099): "is
 * this identifier one of yours?" It exists so that a lookup field of an organization's record can point at a user, a
 * profile or a role, objects whose rows live in the tables of another module, without the record store ever reading
 * those tables and without a dependency from the record store on that module. The owning module implements the
 * interface as a bean; the record store collects every implementation.
 *
 * <p>The answer is a plain yes or no. It never carries a name, a label or a count, and the question cannot list
 * anything: a directory that could be asked to enumerate or to describe would leak what the record store must never
 * show. A missing identifier, one that belongs to another organization and one that is no longer active all answer
 * the same ("no"), so nobody learns which of the three it was.
 *
 * <p>Implementations answer for the organization of the thread's tenant context and run in the caller's transaction,
 * which must have been opened after the context.
 */
public interface ReferenceDirectory {

    /** The API name of the platform-owned object this directory answers for, for example {@code User}. */
    String object();

    /**
     * Whether the identifier names a record of the object that a record of the current organization may link to.
     *
     * @param identifier the identifier the person wrote in the lookup field
     */
    boolean exists(UUID identifier);
}
