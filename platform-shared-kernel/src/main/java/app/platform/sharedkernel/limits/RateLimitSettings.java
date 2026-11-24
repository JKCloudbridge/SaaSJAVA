package app.platform.sharedkernel.limits;

import java.util.UUID;

/**
 * The limits on the public API of the organization the thread works for, as a contract between the module that counts
 * requests ({@code identity}) and the one that lets a platform person change the numbers ({@code platformadmin}),
 * which may not depend on each other's internals (Sprint 32, ADR-0283).
 *
 * <p>Implementations answer for the organization of the thread's tenant context and run in the caller's transaction,
 * which must have been opened after the context. Only the platform console changes a number: nothing an organization
 * does reaches {@link #set}. The reason a platform person gives is kept by the caller in the audit trail and never
 * reaches this contract.
 */
public interface RateLimitSettings {

    /**
     * The numbers of the limits.
     *
     * @param organizationPerMinute requests a minute for the organization as a whole
     * @param organizationPerDay requests a day for the organization as a whole
     * @param applicationPerMinute requests a minute for each connected application and for each member's own token
     * @param overridden whether any number was set for this organization instead of the default
     */
    record Limits(int organizationPerMinute, long organizationPerDay, int applicationPerMinute, boolean overridden) {
    }

    /** The numbers in force for the organization: its override where it has one, else the default of each. */
    Limits effective();

    /** The platform's defaults, which an override replaces. */
    Limits defaults();

    /**
     * Sets the numbers for the organization, replacing any earlier override. A null keeps the default of that number.
     * At least one number must be given, and every number must be inside the ceiling the platform's settings state.
     *
     * @throws IllegalArgumentException when no number is given or one is outside its range; the message is fixed words
     */
    void set(Integer organizationPerMinute, Long organizationPerDay, Integer applicationPerMinute, UUID actor);

    /**
     * Removes the override so the defaults apply again.
     *
     * @return whether there was one
     */
    boolean clear(UUID actor);
}
