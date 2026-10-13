package app.platform.security.internal;

import app.platform.sharedkernel.ActorId;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Every statement about the organization-wide defaults, the sharing rules and the people a hierarchy or a rule names
 * (Sprint 17, ADR-0116). Like the rest of the security module it runs under the tenant context that was open when the
 * transaction began; row level security refuses any other organization, so the organization is never a parameter.
 *
 * <p>The role tree and the groups are walked by recursive queries that remove repeats, so even a loop that the
 * database should never allow could not make them run for ever. The people are returned as <em>users</em> (the owner
 * of a record is a user): the user of a member is on the member's own access row (V044), so nothing here reads the
 * membership table.
 */
@Repository
class SharingStore {

    /** The setting of an object: the default and the hierarchy switch. */
    record SettingRow(UUID id, String object, String defaultAccess, boolean grantViaHierarchy, long version) {
    }

    /** A sharing rule as stored. */
    record RuleRow(UUID id, String object, String name, String kind, String sourceType, UUID sourceId,
            String criterion, String targetType, UUID targetId, String accessLevel, boolean active, long version) {

        @Override
        public String toString() {
            return "RuleRow[redacted]";
        }
    }

    /** The user and role of a member who holds access. */
    record Viewer(UUID userId, UUID roleId) {
    }

    private static final String SOFT_DELETE = "set deleted_at = now(), deleted_by = :actor, version = version + 1, "
            + "updated_by = :actor where deleted_at is null";

    private static final String SETTING_COLUMNS = "id, object_key, default_access, grant_via_hierarchy, version";

    private static final String RULE_COLUMNS = "id, object_key, name, kind, source_type, source_id, "
            + "cast(criterion as text) as criterion, target_type, target_id, access_level, active, version";

    /** The roles below a role (not the role itself), live ones only. */
    private static final String ROLES_BELOW = "with recursive below(id) as ("
            + "select r.id from security_role r where r.parent_id = :role and r.deleted_at is null "
            + "union select r.id from security_role r join below b on r.parent_id = b.id "
            + "where r.deleted_at is null) ";

    /** The role and the roles above it, live ones only. */
    private static final String ROLES_ABOVE = "with recursive above(id, parent_id) as ("
            + "select r.id, r.parent_id from security_role r where r.id = :role and r.deleted_at is null "
            + "union select r.id, r.parent_id from security_role r join above a on r.id = a.parent_id "
            + "where r.deleted_at is null) ";

    /** The groups inside a group, at any depth, and the group itself; live groups only. */
    private static final String GROUPS_BELOW = "with recursive below(id) as ("
            + "select g.id from public_group g where g.id = :group and g.deleted_at is null "
            + "union select gm.member_group_id from public_group_member gm join below b on gm.group_id = b.id "
            + "join public_group g on g.id = gm.member_group_id and g.deleted_at is null "
            + "where gm.member_group_id is not null and gm.deleted_at is null) ";

    private final JdbcClient jdbc;

    SharingStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ---- defaults ----

    Optional<SettingRow> setting(String object) {
        return jdbc.sql("select " + SETTING_COLUMNS + " from object_sharing_setting "
                        + "where object_key = :object and deleted_at is null")
                .param("object", object).query((rs, row) -> setting(rs)).optional();
    }

    Optional<SettingRow> settingForUpdate(String object) {
        return jdbc.sql("select " + SETTING_COLUMNS + " from object_sharing_setting "
                        + "where object_key = :object and deleted_at is null for update")
                .param("object", object).query((rs, row) -> setting(rs)).optional();
    }

    List<SettingRow> settings() {
        return jdbc.sql("select " + SETTING_COLUMNS + " from object_sharing_setting where deleted_at is null "
                        + "order by object_key")
                .query((rs, row) -> setting(rs)).list();
    }

    void insertSetting(String object, String defaultAccess, boolean hierarchy, ActorId actor) {
        jdbc.sql("insert into object_sharing_setting (object_key, default_access, grant_via_hierarchy, created_by, "
                        + "updated_by) values (:object, :access, :hierarchy, :actor, :actor)")
                .param("object", object).param("access", defaultAccess).param("hierarchy", hierarchy)
                .param("actor", actor.value()).update();
    }

    boolean updateSetting(String object, long version, String defaultAccess, boolean hierarchy, ActorId actor) {
        return jdbc.sql("update object_sharing_setting set default_access = :access, grant_via_hierarchy = "
                        + ":hierarchy, version = version + 1, updated_by = :actor "
                        + "where object_key = :object and version = :version and deleted_at is null")
                .param("access", defaultAccess).param("hierarchy", hierarchy).param("actor", actor.value())
                .param("object", object).param("version", version).update() == 1;
    }

    /** Removes the setting of an object (the object is gone): the default applies again. */
    int deleteSettingsOfObject(String object, ActorId actor) {
        return jdbc.sql("update object_sharing_setting " + SOFT_DELETE + " and object_key = :object")
                .param("actor", actor.value()).param("object", object).update();
    }

    // ---- rules ----

    List<RuleRow> rules(String object) {
        return jdbc.sql("select " + RULE_COLUMNS + " from sharing_rule where object_key = :object "
                        + "and deleted_at is null order by lower(name), id")
                .param("object", object).query((rs, row) -> rule(rs)).list();
    }

    List<RuleRow> activeRules(String object) {
        return jdbc.sql("select " + RULE_COLUMNS + " from sharing_rule where object_key = :object "
                        + "and active and deleted_at is null order by id")
                .param("object", object).query((rs, row) -> rule(rs)).list();
    }

    List<RuleRow> allRules() {
        return jdbc.sql("select " + RULE_COLUMNS + " from sharing_rule where deleted_at is null "
                        + "order by object_key, lower(name), id")
                .query((rs, row) -> rule(rs)).list();
    }

    Optional<RuleRow> rule(UUID id) {
        return jdbc.sql("select " + RULE_COLUMNS + " from sharing_rule where id = :id and deleted_at is null")
                .param("id", id).query((rs, row) -> rule(rs)).optional();
    }

    int countRules(String object) {
        return jdbc.sql("select count(*) from sharing_rule where object_key = :object and deleted_at is null")
                .param("object", object).query(Integer.class).single();
    }

    UUID insertRule(RuleRow rule, ActorId actor) throws DuplicateKeyException {
        return jdbc.sql("insert into sharing_rule (object_key, name, kind, source_type, source_id, criterion, "
                        + "target_type, target_id, access_level, active, created_by, updated_by) values (:object, "
                        + ":name, :kind, :sourceType, :sourceId, cast(:criterion as jsonb), :targetType, :targetId, "
                        + ":level, :active, :actor, :actor) returning id")
                .param("object", rule.object()).param("name", rule.name()).param("kind", rule.kind())
                .param("sourceType", rule.sourceType()).param("sourceId", rule.sourceId())
                .param("criterion", rule.criterion()).param("targetType", rule.targetType())
                .param("targetId", rule.targetId()).param("level", rule.accessLevel())
                .param("active", rule.active()).param("actor", actor.value()).query(UUID.class).single();
    }

    boolean updateRule(RuleRow rule, long version, ActorId actor) throws DuplicateKeyException {
        return jdbc.sql("update sharing_rule set name = :name, source_type = :sourceType, source_id = :sourceId, "
                        + "criterion = cast(:criterion as jsonb), target_type = :targetType, target_id = :targetId, "
                        + "access_level = :level, active = :active, version = version + 1, updated_by = :actor "
                        + "where id = :id and version = :version and deleted_at is null")
                .param("name", rule.name()).param("sourceType", rule.sourceType())
                .param("sourceId", rule.sourceId()).param("criterion", rule.criterion())
                .param("targetType", rule.targetType()).param("targetId", rule.targetId())
                .param("level", rule.accessLevel()).param("active", rule.active()).param("actor", actor.value())
                .param("id", rule.id()).param("version", version).update() == 1;
    }

    boolean deleteRule(UUID id, ActorId actor) {
        return jdbc.sql("update sharing_rule " + SOFT_DELETE + " and id = :id")
                .param("actor", actor.value()).param("id", id).update() == 1;
    }

    int deleteRulesOfObject(String object, ActorId actor) {
        return jdbc.sql("update sharing_rule " + SOFT_DELETE + " and object_key = :object")
                .param("actor", actor.value()).param("object", object).update();
    }

    /** The name of a live rule that names the role or the group, or empty when none does. */
    Optional<String> ruleUsing(UUID roleOrGroup) {
        return jdbc.sql("select name from sharing_rule where deleted_at is null "
                        + "and (source_id = :id or target_id = :id) order by id limit 1")
                .param("id", roleOrGroup).query(String.class).optional();
    }

    // ---- people ----

    Optional<Viewer> viewer(UUID membershipId) {
        return jdbc.sql("select user_id, role_id from member_access where membership_id = :membership "
                        + "and deleted_at is null")
                .param("membership", membershipId)
                .query((rs, row) -> new Viewer(rs.getObject("user_id", UUID.class),
                        rs.getObject("role_id", UUID.class)))
                .optional();
    }

    /** The role and every role above it: a rule aimed at "a role and its subordinates" reaches the member. */
    Set<UUID> rolesAboveOrSelf(UUID role) {
        return new LinkedHashSet<>(jdbc.sql(ROLES_ABOVE + "select id from above").param("role", role)
                .query(UUID.class).list());
    }

    /** The users whose role is below the role (not the role itself), active members only. */
    Set<UUID> usersBelow(UUID role) {
        return new LinkedHashSet<>(jdbc.sql(ROLES_BELOW + "select distinct ma.user_id from member_access ma "
                        + "join below b on ma.role_id = b.id where ma.deleted_at is null order by ma.user_id")
                .param("role", role).query(UUID.class).list());
    }

    /** The users whose role is the role. */
    Set<UUID> usersInRole(UUID role) {
        return new LinkedHashSet<>(jdbc.sql("select distinct user_id from member_access where role_id = :role "
                        + "and deleted_at is null order by user_id")
                .param("role", role).query(UUID.class).list());
    }

    /** The users whose role is the role or below it. */
    Set<UUID> usersInSubtree(UUID role) {
        Set<UUID> users = usersInRole(role);
        users.addAll(usersBelow(role));
        return users;
    }

    /** The users who are people in the group or in a group inside it, at any depth. */
    Set<UUID> usersInGroup(UUID group) {
        return new LinkedHashSet<>(jdbc.sql(GROUPS_BELOW + "select distinct ma.user_id from public_group_member gm "
                        + "join below b on gm.group_id = b.id "
                        + "join member_access ma on ma.membership_id = gm.member_membership_id "
                        + "and ma.deleted_at is null "
                        + "where gm.member_membership_id is not null and gm.deleted_at is null "
                        + "order by ma.user_id")
                .param("group", group).query(UUID.class).list());
    }

    // ---- mapping ----

    private static SettingRow setting(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new SettingRow(rs.getObject("id", UUID.class), rs.getString("object_key"),
                rs.getString("default_access"), rs.getBoolean("grant_via_hierarchy"), rs.getLong("version"));
    }

    private static RuleRow rule(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new RuleRow(rs.getObject("id", UUID.class), rs.getString("object_key"), rs.getString("name"),
                rs.getString("kind"), rs.getString("source_type"), rs.getObject("source_id", UUID.class),
                rs.getString("criterion"), rs.getString("target_type"), rs.getObject("target_id", UUID.class),
                rs.getString("access_level"), rs.getBoolean("active"), rs.getLong("version"));
    }
}
