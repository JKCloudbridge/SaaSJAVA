package app.platform.data;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * How the person behind the current request would name the records they are allowed to see (Sprint 24, ADR-0152): the
 * inbox of an approver shows what a task is about only for the records they can read. It answers with the permissions
 * of
 * the member of the request, exactly as a list does: object permission, record-level security and field security, in
 * one batched read. It never grants anything.
 *
 * <p>Like the lookup labels (ADR-0129) it never says why a record is missing from the answer: it does not exist, it is
 * in
 * the recycle bin, the member may not read the object, or the member may not reach the record all look alike. It throws
 * nothing for a record that cannot be seen, so a caller can use it inside a transaction it goes on using.
 */
public interface RecordTitles {

    /**
     * The titles of the records among the identifiers that the member of the request can read.
     *
     * @param ids at most a few hundred identifiers of records of the object
     * @return a map from the identifier of each record the member can read to its title; the title is empty when the
     *         member may not read the field that names records of the object (or the object has none). A record the
     *         member cannot read is not in the map. Titles are text of the organization: callers never log them
     */
    Map<UUID, String> titlesOf(String objectApiName, Collection<UUID> ids);
}
