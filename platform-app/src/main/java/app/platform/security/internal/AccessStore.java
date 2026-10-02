package app.platform.security.internal;

import app.platform.sharedkernel.ActorId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Every statement of the security module (ADR-0039 to ADR-0042). All its tables are tenant-scoped: each statement runs
 * under the tenant context that was open when the transaction began, and row level security refuses anything else, so a
 * row of another organization is never found whatever identifier is asked for. The organization is never passed in; the
 * column default and the policy take it from the transaction.
 *
 * <p>The database also enforces what must always hold (system profiles stay, a role hierarchy has no loop, an
 * assignment
 * points only at rows of the same organization, the last member who can manage access stays), so a bug here cannot
 * break those rules.
 */
@Repository
class AccessStore {

    /** A profile with the number of members who hold it. */
    record ProfileRow(UUID id, String name, String description, UUID licenceTypeId, List<String> abilities,
            String systemKey, boolean fullAccess, boolean defaultProfile, int members) {

        ProfileRow {
            abilities = List.copyOf(abilities);
        }

        boolean system() {
            return systemKey != null;
        }
    }

    /** An access policy with the number of members who hold it. */
    record PolicyRow(UUID id, String name, String description, List<String> abilities, UUID requiredLicenceTypeId,
            int members, int groups) {

        PolicyRow {
            abilities = List.copyOf(abilities);
        }
    }

    /** A role with the number of members who hold it. */
    record RoleRow(UUID id, String name, String description, UUID parentId, int members) {
    }

    /** The profile and role of one member. */
    record MemberAccessRow(UUID id, UUID membershipId, UUID profileId, UUID roleId) {
    }

    /** An access policy assigned to a member. */
    record AssignedPolicy(UUID membershipId, UUID policyId, String name, UUID requiredLicenceTypeId,
            List<String> abilities) {

        AssignedPolicy {
            abilities = List.copyOf(abilities);
        }
    }

    /** An ability given to a member directly. */
    record GrantRow(String ability, String reason, Instant since) {

        @Override
        public String toString() {
            return "GrantRow[redacted]";
        }
    }

    private static final String PROFILE_COLUMNS = "p.id, p.name, p.description, p.licence_type_id, p.abilities, "
            + "p.system_key, p.full_access, p.is_default, "
            + "(select count(*) from member_access ma where ma.profile_id = p.id and ma.deleted_at is null) as members";

    private static final String POLICY_COLUMNS = "a.id, a.name, a.description, a.abilities, "
            + "a.required_licence_type_id, "
            + "(select count(*) from member_access_policy mp where mp.access_policy_id = a.id "
            + "and mp.deleted_at is null) as members, "
            + "(select count(*) from public_group_access_policy gp where gp.access_policy_id = a.id "
            + "and gp.deleted_at is null) as groups";

    private static final String ROLE_COLUMNS = "r.id, r.name, r.description, r.parent_id, "
            + "(select count(*) from member_access ma where ma.role_id = r.id and ma.deleted_at is null) as members";

    private static final String SOFT_DELETE = "set deleted_at = now(), deleted_by = :actor, version = version + 1, "
            + "updated_by = :actor where id = :id and deleted_at is null";

    private final JdbcClient jdbc;

    AccessStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Takes the lock that serializes every change of who may do what in the current organization (the same one the
     * database guard of the last access manager takes, ADR-0044). Taken first, so the order of locks is always the
     * same.
     */
    void lockAccessChanges() {
        jdbc.sql("select pg_advisory_xact_lock(hashtextextended('access-managers:' "
                        + "|| platform_current_tenant()::text, 0))")
                .query().singleRow();
    }

    // ---- profiles ----

    List<ProfileRow> profiles() {
        return jdbc.sql("select " + PROFILE_COLUMNS + " from profile p where p.deleted_at is null "
                        + "order by p.system_key nulls last, lower(p.name), p.id")
                .query(AccessStore::profile).list();
    }

    Optional<ProfileRow> profile(UUID id) {
        return jdbc.sql("select " + PROFILE_COLUMNS + " from profile p where p.id = :id and p.deleted_at is null")
                .param("id", id).query(AccessStore::profile).optional();
    }

    Optional<ProfileRow> profileForUpdate(UUID id) {
        return jdbc.sql("select " + PROFILE_COLUMNS + " from profile p where p.id = :id and p.deleted_at is null "
                        + "for update of p")
                .param("id", id).query(AccessStore::profile).optional();
    }

    Optional<ProfileRow> systemProfile(String systemKey) {
        return jdbc.sql("select " + PROFILE_COLUMNS + " from profile p where p.system_key = :key "
                        + "and p.deleted_at is null")
                .param("key", systemKey).query(AccessStore::profile).optional();
    }

    Optional<ProfileRow> defaultProfile() {
        return jdbc.sql("select " + PROFILE_COLUMNS + " from profile p where p.is_default and p.deleted_at is null")
                .query(AccessStore::profile).optional();
    }

    UUID insertProfile(String name, String description, UUID licenceTypeId, List<String> abilities,
            String systemKey, boolean fullAccess, boolean defaultProfile, ActorId actor) {
        return jdbc.sql("insert into profile (name, description, licence_type_id, abilities, system_key, full_access, "
                        + "is_default, created_by, updated_by) values (:name, :description, :type, "
                        + "cast(:abilities as text[]), :systemKey, :full, :default, :actor, :actor) returning id")
                .param("name", name).param("description", description).param("type", licenceTypeId)
                .param("abilities", abilities.toArray(String[]::new)).param("systemKey", systemKey)
                .param("full", fullAccess).param("default", defaultProfile).param("actor", actor.value())
                .query(UUID.class).single();
    }

    void updateProfile(UUID id, String name, String description, UUID licenceTypeId, List<String> abilities,
            ActorId actor) {
        jdbc.sql("update profile set name = :name, description = :description, licence_type_id = :type, "
                        + "abilities = cast(:abilities as text[]), version = version + 1, updated_by = :actor "
                        + "where id = :id and deleted_at is null")
                .param("name", name).param("description", description).param("type", licenceTypeId)
                .param("abilities", abilities.toArray(String[]::new)).param("actor", actor.value()).param("id", id)
                .update();
    }

    /** Makes the profile the default one (the previous default stops being it first: there is one at most). */
    void makeDefault(UUID id, ActorId actor) {
        jdbc.sql("update profile set is_default = false, version = version + 1, updated_by = :actor "
                        + "where is_default and id <> :id and deleted_at is null")
                .param("actor", actor.value()).param("id", id).update();
        jdbc.sql("update profile set is_default = true, version = version + 1, updated_by = :actor "
                        + "where id = :id and not is_default and deleted_at is null")
                .param("actor", actor.value()).param("id", id).update();
    }

    void deleteProfile(UUID id, ActorId actor) {
        jdbc.sql("update profile " + SOFT_DELETE).param("actor", actor.value()).param("id", id).update();
    }

    // ---- access policies ----

    List<PolicyRow> policies() {
        return jdbc.sql("select " + POLICY_COLUMNS + " from access_policy a where a.deleted_at is null "
                        + "order by lower(a.name), a.id")
                .query(AccessStore::policy).list();
    }

    Optional<PolicyRow> policy(UUID id) {
        return jdbc.sql("select " + POLICY_COLUMNS + " from access_policy a where a.id = :id "
                        + "and a.deleted_at is null")
                .param("id", id).query(AccessStore::policy).optional();
    }

    Optional<PolicyRow> policyForUpdate(UUID id) {
        return jdbc.sql("select " + POLICY_COLUMNS + " from access_policy a where a.id = :id "
                        + "and a.deleted_at is null for update of a")
                .param("id", id).query(AccessStore::policy).optional();
    }

    UUID insertPolicy(String name, String description, List<String> abilities, UUID requiredLicenceTypeId,
            ActorId actor) {
        return jdbc.sql("insert into access_policy (name, description, abilities, required_licence_type_id, "
                        + "created_by, updated_by) values (:name, :description, cast(:abilities as text[]), "
                        + ":type, :actor, :actor) returning id")
                .param("name", name).param("description", description)
                .param("abilities", abilities.toArray(String[]::new))
                .param("type", requiredLicenceTypeId, java.sql.Types.OTHER).param("actor", actor.value())
                .query(UUID.class).single();
    }

    void updatePolicy(UUID id, String name, String description, List<String> abilities, UUID requiredLicenceTypeId,
            ActorId actor) {
        jdbc.sql("update access_policy set name = :name, description = :description, "
                        + "abilities = cast(:abilities as text[]), required_licence_type_id = :type, "
                        + "version = version + 1, updated_by = :actor where id = :id and deleted_at is null")
                .param("name", name).param("description", description)
                .param("abilities", abilities.toArray(String[]::new))
                .param("type", requiredLicenceTypeId, java.sql.Types.OTHER).param("actor", actor.value())
                .param("id", id).update();
    }

    void deletePolicy(UUID id, ActorId actor) {
        jdbc.sql("update access_policy " + SOFT_DELETE).param("actor", actor.value()).param("id", id).update();
    }

    // ---- roles ----

    List<RoleRow> roles() {
        return jdbc.sql("select " + ROLE_COLUMNS + " from security_role r where r.deleted_at is null "
                        + "order by lower(r.name), r.id")
                .query(AccessStore::role).list();
    }

    Optional<RoleRow> role(UUID id) {
        return jdbc.sql("select " + ROLE_COLUMNS + " from security_role r where r.id = :id and r.deleted_at is null")
                .param("id", id).query(AccessStore::role).optional();
    }

    UUID insertRole(String name, String description, UUID parentId, ActorId actor) {
        return jdbc.sql("insert into security_role (name, description, parent_id, created_by, updated_by) "
                        + "values (:name, :description, :parent, :actor, :actor) returning id")
                .param("name", name).param("description", description)
                .param("parent", parentId, java.sql.Types.OTHER).param("actor", actor.value())
                .query(UUID.class).single();
    }

    void updateRole(UUID id, String name, String description, UUID parentId, ActorId actor) {
        jdbc.sql("update security_role set name = :name, description = :description, parent_id = :parent, "
                        + "version = version + 1, updated_by = :actor where id = :id and deleted_at is null")
                .param("name", name).param("description", description)
                .param("parent", parentId, java.sql.Types.OTHER).param("actor", actor.value()).param("id", id)
                .update();
    }

    void deleteRole(UUID id, ActorId actor) {
        jdbc.sql("update security_role " + SOFT_DELETE).param("actor", actor.value()).param("id", id).update();
    }

    // ---- what members hold ----

    Optional<MemberAccessRow> memberAccess(UUID membershipId) {
        return jdbc.sql("select id, membership_id, profile_id, role_id from member_access "
                        + "where membership_id = :membership and deleted_at is null")
                .param("membership", membershipId).query(AccessStore::memberAccess).optional();
    }

    Map<UUID, MemberAccessRow> memberAccessOfAll() {
        Map<UUID, MemberAccessRow> result = new HashMap<>();
        jdbc.sql("select id, membership_id, profile_id, role_id from member_access where deleted_at is null")
                .query(AccessStore::memberAccess).list().forEach(row -> result.put(row.membershipId(), row));
        return result;
    }

    void insertMemberAccess(UUID membershipId, UUID profileId, UUID roleId, ActorId actor) {
        jdbc.sql("insert into member_access (membership_id, profile_id, role_id, created_by, updated_by) "
                        + "values (:membership, :profile, :role, :actor, :actor)")
                .param("membership", membershipId).param("profile", profileId)
                .param("role", roleId, java.sql.Types.OTHER).param("actor", actor.value()).update();
    }

    void updateMemberProfile(UUID membershipId, UUID profileId, ActorId actor) {
        jdbc.sql("update member_access set profile_id = :profile, version = version + 1, updated_by = :actor "
                        + "where membership_id = :membership and deleted_at is null")
                .param("profile", profileId).param("actor", actor.value()).param("membership", membershipId)
                .update();
    }

    void updateMemberRole(UUID membershipId, UUID roleId, ActorId actor) {
        jdbc.sql("update member_access set role_id = :role, version = version + 1, updated_by = :actor "
                        + "where membership_id = :membership and deleted_at is null")
                .param("role", roleId, java.sql.Types.OTHER).param("actor", actor.value())
                .param("membership", membershipId).update();
    }

    List<AssignedPolicy> policiesOf(UUID membershipId) {
        return jdbc.sql(ASSIGNED_POLICIES + " and mp.membership_id = :membership order by lower(a.name), a.id")
                .param("membership", membershipId).query(AccessStore::assignedPolicy).list();
    }

    Map<UUID, List<AssignedPolicy>> policiesOfAll() {
        Map<UUID, List<AssignedPolicy>> result = new HashMap<>();
        jdbc.sql(ASSIGNED_POLICIES + " order by lower(a.name), a.id").query(AccessStore::assignedPolicy).list()
                .forEach(row -> result.computeIfAbsent(row.membershipId(), id -> new ArrayList<>()).add(row));
        return result;
    }

    private static final String ASSIGNED_POLICIES = "select mp.membership_id, a.id, a.name, "
            + "a.required_licence_type_id, a.abilities from member_access_policy mp "
            + "join access_policy a on a.id = mp.access_policy_id and a.deleted_at is null "
            + "where mp.deleted_at is null";

    boolean holdsPolicy(UUID membershipId, UUID policyId) {
        return jdbc.sql("select count(*) from member_access_policy where membership_id = :membership "
                        + "and access_policy_id = :policy and deleted_at is null")
                .param("membership", membershipId).param("policy", policyId).query(Long.class).single() > 0;
    }

    void insertPolicyAssignment(UUID membershipId, UUID policyId, ActorId actor) {
        jdbc.sql("insert into member_access_policy (membership_id, access_policy_id, created_by, updated_by) "
                        + "values (:membership, :policy, :actor, :actor)")
                .param("membership", membershipId).param("policy", policyId).param("actor", actor.value()).update();
    }

    /** Takes the policy from the member. @return whether they held it */
    boolean deletePolicyAssignment(UUID membershipId, UUID policyId, ActorId actor) {
        return jdbc.sql("update member_access_policy set deleted_at = now(), deleted_by = :actor, "
                        + "version = version + 1, updated_by = :actor where membership_id = :membership "
                        + "and access_policy_id = :policy and deleted_at is null")
                .param("actor", actor.value()).param("membership", membershipId).param("policy", policyId)
                .update() > 0;
    }

    List<GrantRow> grantsOf(UUID membershipId) {
        return jdbc.sql("select ability, reason, created_at from member_grant where membership_id = :membership "
                        + "and deleted_at is null order by ability")
                .param("membership", membershipId)
                .query((rs, row) -> new GrantRow(rs.getString("ability"), rs.getString("reason"),
                        rs.getTimestamp("created_at").toInstant()))
                .list();
    }

    boolean holdsGrant(UUID membershipId, String ability) {
        return jdbc.sql("select count(*) from member_grant where membership_id = :membership and ability = :ability "
                        + "and deleted_at is null")
                .param("membership", membershipId).param("ability", ability).query(Long.class).single() > 0;
    }

    void insertGrant(UUID membershipId, String ability, String reason, ActorId actor) {
        jdbc.sql("insert into member_grant (membership_id, ability, reason, created_by, updated_by) "
                        + "values (:membership, :ability, :reason, :actor, :actor)")
                .param("membership", membershipId).param("ability", ability).param("reason", reason)
                .param("actor", actor.value()).update();
    }

    /** Takes the grant back. @return whether the member held it */
    boolean deleteGrant(UUID membershipId, String ability, ActorId actor) {
        return jdbc.sql("update member_grant set deleted_at = now(), deleted_by = :actor, version = version + 1, "
                        + "updated_by = :actor where membership_id = :membership and ability = :ability "
                        + "and deleted_at is null")
                .param("actor", actor.value()).param("membership", membershipId).param("ability", ability)
                .update() > 0;
    }

    // ---- mapping ----

    private static ProfileRow profile(ResultSet rs, int row) throws SQLException {
        return new ProfileRow(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("description"),
                rs.getObject("licence_type_id", UUID.class), strings(rs, "abilities"), rs.getString("system_key"),
                rs.getBoolean("full_access"), rs.getBoolean("is_default"), rs.getInt("members"));
    }

    private static PolicyRow policy(ResultSet rs, int row) throws SQLException {
        return new PolicyRow(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("description"),
                strings(rs, "abilities"), rs.getObject("required_licence_type_id", UUID.class), rs.getInt("members"),
                rs.getInt("groups"));
    }

    private static RoleRow role(ResultSet rs, int row) throws SQLException {
        return new RoleRow(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("description"),
                rs.getObject("parent_id", UUID.class), rs.getInt("members"));
    }

    private static MemberAccessRow memberAccess(ResultSet rs, int row) throws SQLException {
        return new MemberAccessRow(rs.getObject("id", UUID.class), rs.getObject("membership_id", UUID.class),
                rs.getObject("profile_id", UUID.class), rs.getObject("role_id", UUID.class));
    }

    static AssignedPolicy assignedPolicy(ResultSet rs, int row) throws SQLException {
        return new AssignedPolicy(rs.getObject("membership_id", UUID.class), rs.getObject("id", UUID.class),
                rs.getString("name"), rs.getObject("required_licence_type_id", UUID.class), strings(rs, "abilities"));
    }

    static List<String> strings(ResultSet rs, String column) throws SQLException {
        java.sql.Array array = rs.getArray(column);
        if (array == null) {
            return List.of();
        }
        Object[] values = (Object[]) array.getArray();
        List<String> result = new ArrayList<>(values.length);
        for (Object value : values) {
            result.add((String) value);
        }
        return result;
    }
}
