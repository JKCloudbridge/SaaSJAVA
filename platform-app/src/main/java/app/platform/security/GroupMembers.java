package app.platform.security;

import java.util.Set;
import java.util.UUID;

/**
 * Who is in which public group, with nesting resolved (ADR-0047). It is what record sharing (Sprint 17), approvals
 * (Sprint 24), assignment and notifications read; today the security module itself uses the same resolution to find the
 * access policies that reach a member through groups. Answers are for the organization of the thread's tenant context,
 * are read from the current rows every time and run in the caller's transaction.
 */
public interface GroupMembers {

    /** Every group the member is in, directly or through nested groups. Empty for a member who is in none. */
    Set<UUID> groupsOf(UUID membershipId);

    /** Every person in the group, directly or through nested groups, by membership. Empty for an unknown group. */
    Set<UUID> membersOf(UUID groupId);

    /**
     * The users behind {@link #membersOf}: every person in the group, directly or through nested groups, by user.
     * Whether a person is an active member is the identity module's fact, which the caller asks of the directory of
     * users (MC2A, ADR-0260: a notice goes only to people who can read it). Empty for an unknown group.
     */
    Set<UUID> usersOf(UUID groupId);

    /** Whether a live group of the organization has the identifier (sharing a record with a group asks it). */
    boolean exists(UUID groupId);
}
