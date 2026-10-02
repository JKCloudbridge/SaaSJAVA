package app.platform.identity;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Platform roles (ADR-0030): who may operate the platform itself. The one place the platform asks "does this person
 * hold a platform role?", like {@code Administration} for an organization; platform roles are completely separate from
 * organization authority (a platform administrator who is also a member of an organization is an ordinary member
 * there).
 *
 * <p>The answer is read from the database on every call (nothing is cached), so a revoked role stops working at the
 * next request. The first administrator is created by a documented manual step (M002), never by a default account.
 */
public interface PlatformRoles {

    /** The roles the person holds now (empty for an ordinary person). */
    Set<PlatformRole> of(UUID userId);

    /**
     * Requires that the person holds at least one of the roles. A refusal is audited.
     *
     * @throws app.platformapi.ApiException {@code FORBIDDEN} when the person holds none of them
     */
    void require(UUID userId, PlatformRole... anyOf);

    /** Every role assignment, for the platform administrators (the caller checks who may ask). */
    List<PlatformPerson> people();

    /**
     * Grants a role to the person with this address; audited.
     *
     * @throws app.platformapi.ApiException {@code VALIDATION_ERROR} when no active account has the address,
     *         {@code CONFLICT} when the person already holds the role
     */
    PlatformPerson grant(UUID actor, String email, PlatformRole role);

    /**
     * Revokes a role assignment; audited. The last platform administrator cannot be removed.
     *
     * @throws app.platformapi.ApiException {@code NOT_FOUND} for an unknown assignment, {@code CONFLICT} for the last
     *         platform administrator
     */
    void revoke(UUID actor, UUID assignmentId);
}
