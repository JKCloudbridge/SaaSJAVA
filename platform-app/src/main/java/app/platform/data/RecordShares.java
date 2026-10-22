package app.platform.data;

import app.platform.security.Grantee;
import java.util.UUID;

/**
 * Sharing a record from Java, for a module that has already decided that it may (FA4, ADR-0188): the publishing of a
 * template is a read share of its header to the people of a department. The endpoints of the record store
 * ({@code /records/{id}/shares}) are for a person who may change the record; this is for a module's own act, which is
 * decided by the module's ability and not by the record's.
 *
 * <p>Same rules as the endpoint, same table, same reader of shares ({@code JsonbQuerySql}): the grantee must be a
 * member, a public group or a role of this organization, a record has one share per grantee and a bounded number of
 * shares, and the owner is never a grantee. It works on a record that a pending approval locks, because a share
 * changes who reads the record and not the record (ADR-0148 decision 2). Only a read share can be made from here: the
 * level is not a parameter, so nothing this contract does can give anyone the right to change a record.
 *
 * <p><strong>It never overwrites.</strong> A share that exists (made by hand with the share panel, or by an earlier
 * call) is left exactly as it is and reported as {@link Granted#ALREADY_SHARED}, so a caller that wants to take back
 * only what it gave can tell which is which. It runs in the caller's transaction, for the organization of the thread's
 * tenant context, and writes an audit record by the kind of grantee and the level, never a name. Only the module that
 * owns the act may call it (an architecture test names the module).
 */
public interface RecordShares {

    /** What {@link #grantRead} did. */
    enum Granted {
        /** A new read share now exists. */
        CREATED,
        /** The grantee already had a share of this record, of any level; nothing was changed. */
        ALREADY_SHARED
    }

    /**
     * Gives the grantee read access to the record.
     *
     * @param object the API name of the object of the record
     * @param recordId the record
     * @param grantee whom to share with
     * @param actor the person on whose behalf the module acts, for the audit record
     * @return whether a share was made
     * @throws app.platformapi.ApiException {@code NOT_FOUND} when the record does not exist,
     *         {@code VALIDATION_ERROR} when the grantee is not of this organization or is the owner, {@code CONFLICT}
     *         when the record has the most shares it can have
     */
    Granted grantRead(String object, UUID recordId, Grantee grantee, UUID actor);

    /**
     * Takes back the share with the grantee, whatever its level. The caller is responsible for calling it only for a
     * share it made itself.
     *
     * @return whether a share was removed (false when there was none, for instance because the group was removed and
     *         its shares ended with it)
     */
    boolean revoke(String object, UUID recordId, Grantee grantee, UUID actor);
}
