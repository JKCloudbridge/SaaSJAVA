package app.platform.security;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The roles and public groups a record can be shared with, by name (Functional track FA2B, ADR-0185). The record store
 * uses it to check that a role exists, to offer a role or a group to someone who may share a record, and to name the
 * grantees of a share. It answers names and identifiers only: nothing about the people in a role or a group, and no
 * ability is needed to ask, because the caller (the share endpoints of the record store) has already checked that the
 * member may change the record.
 *
 * <p>Roles and groups are the organization's setup, not secrets: any member who may share a record may name any live
 * role or group of their own organization. A role or group of another organization, one that was removed and one that
 * never existed are the same answer (row level security and the live-row filter make them the same), so nothing here
 * tells them apart.
 *
 * <p>Answers are for the organization of the thread's tenant context, are read from the current rows and run in the
 * caller's transaction.
 */
public interface ShareTargets {

    /**
     * One role or group a share can name.
     *
     * @param id the role or group
     * @param label the name the organization gave it
     */
    record Target(UUID id, String label) {

        /** Prints nothing of the organization's text. */
        @Override
        public String toString() {
            return "Target[redacted]";
        }
    }

    /** Whether a live role of the organization has the identifier. */
    boolean roleExists(UUID roleId);

    /**
     * The live roles whose name contains the text (any role for an empty text), by name, at most {@code limit}.
     */
    List<Target> roles(String search, int limit);

    /**
     * The live public groups whose name contains the text (any group for an empty text), by name, at most
     * {@code limit}.
     */
    List<Target> groups(String search, int limit);

    /** The names of these roles; a role that is not live is not in the answer. */
    Map<UUID, String> roleLabels(Collection<UUID> roleIds);

    /** The names of these groups; a group that is not live is not in the answer. */
    Map<UUID, String> groupLabels(Collection<UUID> groupIds);
}
