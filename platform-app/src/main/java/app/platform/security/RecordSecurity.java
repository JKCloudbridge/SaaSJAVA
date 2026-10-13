package app.platform.security;

import java.util.UUID;

/**
 * Which records of an object a member may reach (Sprint 17, ADR-0115 to ADR-0118): the one read contract the data
 * module uses for record-level security. It combines the organization-wide default of the object, the object flags
 * view-all and modify-all, the owner, the role hierarchy, the sharing rules and the member's groups into a closed
 * {@link RecordAccess}. The answer is for the organization of the thread's tenant context, runs in the caller's
 * transaction and is kept behind the security version (ADR-0053): a change of a default, a rule, a role, a group or a
 * permission is the answer of the next question on every instance.
 *
 * <p>Explicit shares of single records are not part of the answer's data: the answer says <em>who</em> the member is
 * for sharing (their user and their groups) and the statement reads the live share rows, so a share is visible at
 * once.
 */
public interface RecordSecurity {

    /**
     * What the member may reach in the object. A member who does not exist, does not hold a licence, may not read the
     * object or asks about an object the catalogue does not know gets {@link RecordAccess#nothing}.
     */
    RecordAccess accessTo(UUID membershipId, String object);
}
