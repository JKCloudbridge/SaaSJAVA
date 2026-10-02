package app.platform.security.internal;

import app.platform.sharedkernel.ActorId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Every statement about public groups (ADR-0047). Like the rest of the security module it runs under the tenant context
 * that was open when the transaction began; row level security refuses anything else, and the database guards keep a
 * group from containing itself, a person from being in a group without being an active member, and a policy that needs
 * a licence from being given to a group. Walking the nesting uses a recursive query that removes repeats, so even a
 * loop that should never exist could not make it run forever.
 */
@Repository
class GroupStore {

    /** A group. */
    record GroupRow(UUID id, String name, String description) {
    }

    /** One link: a group holds a person, a group or an access policy. */
    record Link(UUID groupId, UUID target) {
    }

    private static final String SOFT_DELETE = "set deleted_at = now(), deleted_by = :actor, version = version + 1, "
            + "updated_by = :actor where deleted_at is null";

    /** The groups that contain the person, directly (true) or through nested groups (false); live groups only. */
    private static final String GROUPS_OF_PERSON = "with recursive up(id, direct) as ("
            + "select gm.group_id, true from public_group_member gm "
            + "join public_group g on g.id = gm.group_id and g.deleted_at is null "
            + "where gm.member_membership_id = :membership and gm.deleted_at is null "
            + "union "
            + "select gm.group_id, false from public_group_member gm "
            + "join up on gm.member_group_id = up.id "
            + "join public_group g on g.id = gm.group_id and g.deleted_at is null "
            + "where gm.deleted_at is null) ";

    private final JdbcClient jdbc;

    GroupStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ---- groups ----

    List<GroupRow> groups() {
        return jdbc.sql("select id, name, description from public_group where deleted_at is null "
                        + "order by lower(name), id")
                .query((rs, row) -> group(rs)).list();
    }

    Optional<GroupRow> group(UUID id) {
        return jdbc.sql("select id, name, description from public_group where id = :id and deleted_at is null")
                .param("id", id).query((rs, row) -> group(rs)).optional();
    }

    Optional<GroupRow> groupForUpdate(UUID id) {
        return jdbc.sql("select id, name, description from public_group where id = :id and deleted_at is null "
                        + "for update")
                .param("id", id).query((rs, row) -> group(rs)).optional();
    }

    UUID insertGroup(String name, String description, ActorId actor) {
        return jdbc.sql("insert into public_group (name, description, created_by, updated_by) "
                        + "values (:name, :description, :actor, :actor) returning id")
                .param("name", name).param("description", description).param("actor", actor.value())
                .query(UUID.class).single();
    }

    void updateGroup(UUID id, String name, String description, ActorId actor) {
        jdbc.sql("update public_group set name = :name, description = :description, version = version + 1, "
                        + "updated_by = :actor where id = :id and deleted_at is null")
                .param("name", name).param("description", description).param("actor", actor.value())
                .param("id", id).update();
    }

    void deleteGroup(UUID id, ActorId actor) {
        jdbc.sql("update public_group " + SOFT_DELETE + " and id = :id")
                .param("actor", actor.value()).param("id", id).update();
    }

    // ---- links: people, nested groups, policies ----

    /** Every person link of every group: group and membership. */
    List<Link> personLinks() {
        return jdbc.sql("select group_id, member_membership_id as target from public_group_member "
                        + "where member_membership_id is not null and deleted_at is null order by created_at, id")
                .query((rs, row) -> link(rs)).list();
    }

    /** Every nested-group link: the outer group and the group inside it. */
    List<Link> groupLinks() {
        return jdbc.sql("select group_id, member_group_id as target from public_group_member "
                        + "where member_group_id is not null and deleted_at is null order by created_at, id")
                .query((rs, row) -> link(rs)).list();
    }

    /** Every policy link: the group and the policy given to it. */
    List<Link> policyLinks() {
        return jdbc.sql("select group_id, access_policy_id as target from public_group_access_policy "
                        + "where deleted_at is null order by created_at, id")
                .query((rs, row) -> link(rs)).list();
    }

    boolean holdsPerson(UUID groupId, UUID membershipId) {
        return jdbc.sql("select count(*) from public_group_member where group_id = :group "
                        + "and member_membership_id = :membership and deleted_at is null")
                .param("group", groupId).param("membership", membershipId).query(Long.class).single() > 0;
    }

    boolean holdsGroup(UUID groupId, UUID innerGroupId) {
        return jdbc.sql("select count(*) from public_group_member where group_id = :group "
                        + "and member_group_id = :inner and deleted_at is null")
                .param("group", groupId).param("inner", innerGroupId).query(Long.class).single() > 0;
    }

    boolean holdsPolicy(UUID groupId, UUID policyId) {
        return jdbc.sql("select count(*) from public_group_access_policy where group_id = :group "
                        + "and access_policy_id = :policy and deleted_at is null")
                .param("group", groupId).param("policy", policyId).query(Long.class).single() > 0;
    }

    void insertPerson(UUID groupId, UUID membershipId, ActorId actor) {
        jdbc.sql("insert into public_group_member (group_id, member_membership_id, created_by, updated_by) "
                        + "values (:group, :membership, :actor, :actor)")
                .param("group", groupId).param("membership", membershipId).param("actor", actor.value()).update();
    }

    void insertGroupMember(UUID groupId, UUID innerGroupId, ActorId actor) {
        jdbc.sql("insert into public_group_member (group_id, member_group_id, created_by, updated_by) "
                        + "values (:group, :inner, :actor, :actor)")
                .param("group", groupId).param("inner", innerGroupId).param("actor", actor.value()).update();
    }

    void insertPolicy(UUID groupId, UUID policyId, ActorId actor) {
        jdbc.sql("insert into public_group_access_policy (group_id, access_policy_id, created_by, updated_by) "
                        + "values (:group, :policy, :actor, :actor)")
                .param("group", groupId).param("policy", policyId).param("actor", actor.value()).update();
    }

    /** @return whether the person was in the group */
    boolean deletePerson(UUID groupId, UUID membershipId, ActorId actor) {
        return jdbc.sql("update public_group_member " + SOFT_DELETE
                        + " and group_id = :group and member_membership_id = :membership")
                .param("actor", actor.value()).param("group", groupId).param("membership", membershipId)
                .update() > 0;
    }

    /** @return whether the inner group was in the group */
    boolean deleteGroupMember(UUID groupId, UUID innerGroupId, ActorId actor) {
        return jdbc.sql("update public_group_member " + SOFT_DELETE
                        + " and group_id = :group and member_group_id = :inner")
                .param("actor", actor.value()).param("group", groupId).param("inner", innerGroupId)
                .update() > 0;
    }

    /** @return whether the policy was given to the group */
    boolean deletePolicy(UUID groupId, UUID policyId, ActorId actor) {
        return jdbc.sql("update public_group_access_policy " + SOFT_DELETE
                        + " and group_id = :group and access_policy_id = :policy")
                .param("actor", actor.value()).param("group", groupId).param("policy", policyId)
                .update() > 0;
    }

    /**
     * Ends every link of a group that is being removed: what is in it, where it is in, and the policies given to it.
     *
     * @return how many links ended
     */
    int endLinksOfGroup(UUID groupId, ActorId actor) {
        int members = jdbc.sql("update public_group_member " + SOFT_DELETE
                        + " and (group_id = :group or member_group_id = :group)")
                .param("actor", actor.value()).param("group", groupId).update();
        int policies = jdbc.sql("update public_group_access_policy " + SOFT_DELETE + " and group_id = :group")
                .param("actor", actor.value()).param("group", groupId).update();
        return members + policies;
    }

    /**
     * Takes a person out of every group (the membership ended).
     *
     * @return the groups the person was directly in
     */
    List<UUID> endLinksOfPerson(UUID membershipId, ActorId actor) {
        return jdbc.sql("update public_group_member " + SOFT_DELETE + " and member_membership_id = :membership "
                        + "returning group_id")
                .param("actor", actor.value()).param("membership", membershipId).query(UUID.class).list();
    }

    /** Takes an access policy away from every group (the policy is being removed). */
    int endLinksOfPolicy(UUID policyId, ActorId actor) {
        return jdbc.sql("update public_group_access_policy " + SOFT_DELETE + " and access_policy_id = :policy")
                .param("actor", actor.value()).param("policy", policyId).update();
    }

    // ---- walking the nesting ----

    /** Whether {@code target} is {@code from} or is reached by going down from it through nested groups. */
    boolean reachesDown(UUID from, UUID target) {
        return jdbc.sql("with recursive below(id) as (select cast(:from as uuid) "
                        + "union select gm.member_group_id from public_group_member gm "
                        + "join below b on gm.group_id = b.id "
                        + "where gm.member_group_id is not null and gm.deleted_at is null) "
                        + "select count(*) from below where id = :target")
                .param("from", from).param("target", target).query(Long.class).single() > 0;
    }

    /** Every group that contains the person, directly or through nested groups, with whether it is direct. */
    Map<UUID, Boolean> groupsOfPerson(UUID membershipId) {
        Map<UUID, Boolean> result = new LinkedHashMap<>();
        jdbc.sql(GROUPS_OF_PERSON + "select id, bool_or(direct) as direct from up group by id")
                .param("membership", membershipId)
                .query((rs, row) -> result.put(rs.getObject("id", UUID.class), rs.getBoolean("direct"))).list();
        return result;
    }

    /** Every person in the group, directly or through nested groups. */
    Set<UUID> peopleIn(UUID groupId) {
        Set<UUID> result = new HashSet<>();
        jdbc.sql("with recursive below(id) as (select g.id from public_group g "
                        + "where g.id = :group and g.deleted_at is null "
                        + "union select gm.member_group_id from public_group_member gm "
                        + "join below b on gm.group_id = b.id "
                        + "join public_group g on g.id = gm.member_group_id and g.deleted_at is null "
                        + "where gm.member_group_id is not null and gm.deleted_at is null) "
                        + "select gm.member_membership_id from public_group_member gm "
                        + "join below b on gm.group_id = b.id "
                        + "where gm.member_membership_id is not null and gm.deleted_at is null")
                .param("group", groupId)
                .query((rs, row) -> result.add(rs.getObject("member_membership_id", UUID.class))).list();
        return result;
    }

    /** The access policies that reach the person through the groups they are in (each policy once). */
    List<AccessStore.AssignedPolicy> policiesViaGroups(UUID membershipId) {
        Map<UUID, AccessStore.AssignedPolicy> byPolicy = new HashMap<>();
        jdbc.sql(GROUPS_OF_PERSON + "select cast(:membership as uuid) as membership_id, a.id, a.name, "
                        + "a.required_licence_type_id, a.abilities from up "
                        + "join public_group_access_policy gp on gp.group_id = up.id and gp.deleted_at is null "
                        + "join access_policy a on a.id = gp.access_policy_id and a.deleted_at is null")
                .param("membership", membershipId)
                .query(AccessStore::assignedPolicy).list()
                .forEach(policy -> byPolicy.putIfAbsent(policy.policyId(), policy));
        return new ArrayList<>(byPolicy.values());
    }

    // ---- mapping ----

    private static GroupRow group(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new GroupRow(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("description"));
    }

    private static Link link(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Link(rs.getObject("group_id", UUID.class), rs.getObject("target", UUID.class));
    }
}
