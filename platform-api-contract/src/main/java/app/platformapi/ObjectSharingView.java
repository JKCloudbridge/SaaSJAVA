package app.platformapi;

import jakarta.validation.constraints.NotNull;

/**
 * How the records of one object are shared by default (Sprint 17): the organization-wide default and whether a manager
 * reads what the people below them own. An object nobody has set up has the default {@code PUBLIC_READ_WRITE} with the
 * hierarchy on, which is how the platform behaved before record-level security.
 *
 * @param object the object's API name
 * @param defaultAccess {@code PRIVATE} (members reach only their own records and what is shared with them),
 *        {@code PUBLIC_READ} (every member reads every record) or {@code PUBLIC_READ_WRITE} (every member reads and
 *        changes every record)
 * @param grantViaHierarchy whether the people above the owner in the role hierarchy read the record
 * @param configured whether an administrator has set the object up (false: the defaults above apply)
 * @param version the version to send when changing it, 0 when not configured yet
 */
public record ObjectSharingView(@NotNull String object, @NotNull String defaultAccess, boolean grantViaHierarchy,
        boolean configured, long version) {
}
