package app.platform.security;

import app.platform.sharedkernel.ActorId;
import java.util.UUID;

/**
 * Told by the security module, in the same transaction, when a role or a public group is removed, so that whoever keeps
 * shares addressed to it can end them (Functional track FA2B, ADR-0185). The record store implements it, the same
 * arrangement as {@link RecordMatcher}: security knows roles and groups, the record store knows shares, and neither may
 * read the other's tables.
 *
 * <p>A share to a removed role or group could never match anyone (a role with members or sub-roles is not removed, and
 * the chain of a member holds live roles only), so ending it changes no answer; it keeps the list of shares and the
 * limit of shares of a record free of rows that name nothing.
 *
 * <p>Runs for the organization of the thread's tenant context, in the caller's transaction.
 */
public interface GranteeRemovals {

    /**
     * Ends every share addressed to the removed role or group.
     *
     * @param kind {@link Grantee.Kind#GROUP} for a group, any role kind for a role (both role kinds are ended)
     * @param id the removed role or group
     * @return how many shares were ended
     */
    int removed(Grantee.Kind kind, UUID id, ActorId actor);
}
