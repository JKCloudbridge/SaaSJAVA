package app.platform.security;

import java.util.UUID;

/**
 * Evaluates an {@link Admission} against one stored record. The security module decides <em>which</em> records a
 * member may reach; only the record store can say whether one record is among them, because the records, their owners,
 * their shares and their values are its. The data module implements this interface and runs the same statement that a
 * list runs, for the one record, so that a single answer and a list can never disagree (ADR-0115, point 2). It is the
 * same arrangement as {@link ObjectCatalog}, which the metadata module implements.
 *
 * <p>The organization is that of the thread's tenant context. The record is looked up by object and identifier;
 * a record that is not there, is in the recycle bin or belongs to another organization is admitted by nothing.
 */
public interface RecordMatcher {

    /** Whether the admission admits the live record. */
    boolean admits(String objectApiName, UUID recordId, Admission admission);
}
