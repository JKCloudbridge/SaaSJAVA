package app.platform.security;

import java.util.Map;
import java.util.UUID;

/**
 * The permission decision API: may this member do this with this object, record or field? (ADR-0050). It is what the
 * data engine asks from Sprint 14 on, and the one place that combines object and field permissions.
 *
 * <p>The tenant is never a parameter: it is the organization of the thread's tenant context (derived from the host and
 * the signed-in member, never from a request), and a member who is not in it gets "no" like a member with nothing. The
 * context must be open before the transaction begins. The answer is computed from the current rows every time: there is
 * no cache (Sprint 9 decides invalidation), so a change takes effect for the next question.
 *
 * <p>The algorithm, in order:
 * <ol>
 *   <li>The object must exist ({@link ObjectCatalog}); for a field, the field must exist on it.</li>
 *   <li>The member's effective permissions ({@link EffectivePermissions}: profile while licensed, access policies,
 *   groups'
 *       access policies, individual grants; a union, no deny rule) must allow the action on the object.</li>
 *   <li>For a field: they must also allow the field action; reading a field needs read on the object and editing one
 *       needs create or update on it.</li>
 *   <li>The record, when given, is accepted and not used yet (record-level rules arrive in Sprint 17).</li>
 * </ol>
 *
 * <p>A "no" never says why to a person: unknown objects and fields, objects the member may not use and members who
 * do not exist all answer the same. {@link Decision#reason()} is for tests and logs of the caller.
 */
public interface Decisions {

    /** May the member do the action with the object? */
    default Decision can(UUID membershipId, String object, ObjectAction action) {
        return can(membershipId, object, action, null);
    }

    /** May the member do the action with the object (the record is accepted and not used until Sprint 17)? */
    Decision can(UUID membershipId, String object, ObjectAction action, RecordRef record);

    /** May the member do the action with the field of the object? */
    default Decision can(UUID membershipId, String object, String field, FieldAction action) {
        return can(membershipId, object, field, action, null);
    }

    /** May the member do the action with the field (the record is accepted and not used until Sprint 17)? */
    Decision can(UUID membershipId, String object, String field, FieldAction action, RecordRef record);

    /** Everything the member may do with the object and its fields, in one answer (the bulk form for lists). */
    ObjectAccess accessTo(UUID membershipId, String object);

    /**
     * The objects the member may use at all, with what they may do with each, by object key. Objects the member has
     * no permission on are absent.
     */
    Map<String, ObjectAccess> accessToAll(UUID membershipId);
}
