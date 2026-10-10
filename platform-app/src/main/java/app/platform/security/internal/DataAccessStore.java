package app.platform.security.internal;

import app.platform.security.DataAccess;
import app.platform.security.FieldAction;
import app.platform.security.ObjectAction;
import app.platform.sharedkernel.ActorId;
import java.sql.Types;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Every statement about object and field permissions (ADR-0049, ADR-0050). A row says what one container (a profile, an
 * access policy or a member) allows on one object or one field. Like the rest of the security module it runs under the
 * tenant context that was open when the transaction began, and the database guard keeps a row inside its organization.
 * Keys that this release does not know as actions are ignored when read, never an error.
 */
@Repository
class DataAccessStore {

    /** The kind of container a permission row belongs to. */
    enum Holder {
        PROFILE("profile_id"),
        POLICY("access_policy_id"),
        MEMBER("membership_id");

        private final String column;

        Holder(String column) {
            this.column = column;
        }
    }

    /**
     * What the three kinds of containers of one member hold.
     *
     * @param profile what the profile holds
     * @param policies what each access policy holds, by policy
     * @param member what is granted to the member directly
     */
    record Held(DataAccess profile, Map<UUID, DataAccess> policies, DataAccess member) {

        Held {
            policies = Map.copyOf(policies);
        }
    }

    /** What a replacement changed, for the audit record (counts only). */
    record Changes(int objectsAdded, int objectsChanged, int objectsRemoved, int fieldsAdded, int fieldsChanged,
            int fieldsRemoved) {

        boolean any() {
            return objectsAdded + objectsChanged + objectsRemoved + fieldsAdded + fieldsChanged + fieldsRemoved > 0;
        }
    }

    private final JdbcClient jdbc;

    DataAccessStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** What one container holds. */
    DataAccess of(Holder holder, UUID id) {
        return new DataAccess(false, objectsOf(holder, id), fieldsOf(holder, id));
    }

    /**
     * What the containers of one member hold, in two statements: the profile, each access policy and the member.
     *
     * @param profileId the member's profile, or null
     * @param policyIds the access policies that reach the member
     * @param membershipId the member
     */
    Held heldBy(UUID profileId, Collection<UUID> policyIds, UUID membershipId) {
        Map<UUID, Map<String, Set<ObjectAction>>> policyObjects = new HashMap<>();
        Map<UUID, Map<String, Set<FieldAction>>> policyFields = new HashMap<>();
        Map<String, Set<ObjectAction>> profileObjects = new HashMap<>();
        Map<String, Set<FieldAction>> profileFields = new HashMap<>();
        Map<String, Set<ObjectAction>> memberObjects = new HashMap<>();
        Map<String, Set<FieldAction>> memberFields = new HashMap<>();
        String[] policies = policyIds.stream().map(UUID::toString).toArray(String[]::new);
        String where = "where deleted_at is null and (profile_id = :profile "
                + "or access_policy_id = any (cast(:policies as uuid[])) or membership_id = :membership)";
        jdbc.sql("select profile_id, access_policy_id, membership_id, object_key as key, actions "
                        + "from object_permission " + where)
                .param("profile", profileId, Types.OTHER).param("policies", policies)
                .param("membership", membershipId)
                .query((rs, row) -> {
                    Set<ObjectAction> actions = objectActions(AccessStore.strings(rs, "actions"));
                    String key = rs.getString("key");
                    if (rs.getObject("profile_id") != null) {
                        profileObjects.put(key, actions);
                    } else if (rs.getObject("access_policy_id") != null) {
                        policyObjects.computeIfAbsent(rs.getObject("access_policy_id", UUID.class),
                                id -> new HashMap<>()).put(key, actions);
                    } else {
                        memberObjects.put(key, actions);
                    }
                    return row;
                }).list();
        jdbc.sql("select profile_id, access_policy_id, membership_id, field_key as key, actions "
                        + "from field_permission " + where)
                .param("profile", profileId, Types.OTHER).param("policies", policies)
                .param("membership", membershipId)
                .query((rs, row) -> {
                    Set<FieldAction> actions = fieldActions(AccessStore.strings(rs, "actions"));
                    String key = rs.getString("key");
                    if (rs.getObject("profile_id") != null) {
                        profileFields.put(key, actions);
                    } else if (rs.getObject("access_policy_id") != null) {
                        policyFields.computeIfAbsent(rs.getObject("access_policy_id", UUID.class),
                                id -> new HashMap<>()).put(key, actions);
                    } else {
                        memberFields.put(key, actions);
                    }
                    return row;
                }).list();
        Map<UUID, DataAccess> byPolicy = new HashMap<>();
        for (UUID id : policyIds) {
            byPolicy.put(id, DataAccess.of(policyObjects.getOrDefault(id, Map.of()),
                    policyFields.getOrDefault(id, Map.of())));
        }
        return new Held(DataAccess.of(profileObjects, profileFields), byPolicy,
                DataAccess.of(memberObjects, memberFields));
    }

    /**
     * Makes what the container holds equal to the wanted matrix: lines that are new are added, lines that differ are
     * changed, lines that are not wanted are ended.
     */
    Changes replace(Holder holder, UUID id, Map<String, Set<ObjectAction>> objects,
            Map<String, Set<FieldAction>> fields, ActorId actor) {
        int[] objectChanges = replaceRows("object_permission", "object_key", holder, id, toKeys(objects), actor);
        int[] fieldChanges = replaceRows("field_permission", "field_key", holder, id, toKeys(fields), actor);
        return new Changes(objectChanges[0], objectChanges[1], objectChanges[2], fieldChanges[0], fieldChanges[1],
                fieldChanges[2]);
    }

    /** Ends everything the container holds (it is being removed, or the membership ended). */
    int deleteAll(Holder holder, UUID id, ActorId actor) {
        int total = 0;
        for (String table : List.of("object_permission", "field_permission")) {
            total += jdbc.sql("update " + table + " set deleted_at = now(), deleted_by = :actor, "
                            + "version = version + 1, updated_by = :actor where " + holder.column
                            + " = :id and deleted_at is null")
                    .param("actor", actor.value()).param("id", id).update();
        }
        return total;
    }

    /**
     * Ends the permission lines of an object that was removed: the lines on the object and on every one of its fields,
     * whoever holds them. The key is compared as given, never as a pattern.
     */
    int deleteForObject(String objectKey, ActorId actor) {
        int total = jdbc.sql("update object_permission set deleted_at = now(), deleted_by = :actor, "
                        + "version = version + 1, updated_by = :actor where object_key = :key and deleted_at is null")
                .param("actor", actor.value()).param("key", objectKey).update();
        total += jdbc.sql("update field_permission set deleted_at = now(), deleted_by = :actor, "
                        + "version = version + 1, updated_by = :actor "
                        + "where split_part(field_key, '.', 1) = :key and deleted_at is null")
                .param("actor", actor.value()).param("key", objectKey).update();
        return total;
    }

    /** Ends the permission lines of one field that was removed, whoever holds them. */
    int deleteForField(String fieldKey, ActorId actor) {
        return jdbc.sql("update field_permission set deleted_at = now(), deleted_by = :actor, "
                        + "version = version + 1, updated_by = :actor where field_key = :key and deleted_at is null")
                .param("actor", actor.value()).param("key", fieldKey).update();
    }

    // ---- reading ----

    private Map<String, Set<ObjectAction>> objectsOf(Holder holder, UUID id) {
        Map<String, Set<ObjectAction>> result = new HashMap<>();
        jdbc.sql("select object_key as key, actions from object_permission where " + holder.column
                        + " = :id and deleted_at is null")
                .param("id", id)
                .query((rs, row) -> result.put(rs.getString("key"),
                        objectActions(AccessStore.strings(rs, "actions"))))
                .list();
        return result;
    }

    private Map<String, Set<FieldAction>> fieldsOf(Holder holder, UUID id) {
        Map<String, Set<FieldAction>> result = new HashMap<>();
        jdbc.sql("select field_key as key, actions from field_permission where " + holder.column
                        + " = :id and deleted_at is null")
                .param("id", id)
                .query((rs, row) -> result.put(rs.getString("key"),
                        fieldActions(AccessStore.strings(rs, "actions"))))
                .list();
        return result;
    }

    // ---- writing ----

    /** Returns {added, changed, removed}. */
    private int[] replaceRows(String table, String keyColumn, Holder holder, UUID id,
            Map<String, List<String>> wanted, ActorId actor) {
        Map<String, List<String>> existing = new HashMap<>();
        jdbc.sql("select " + keyColumn + " as key, actions from " + table + " where " + holder.column
                        + " = :id and deleted_at is null")
                .param("id", id)
                .query((rs, row) -> existing.put(rs.getString("key"), AccessStore.strings(rs, "actions"))).list();
        int added = 0;
        int changed = 0;
        int removed = 0;
        for (Map.Entry<String, List<String>> entry : wanted.entrySet()) {
            List<String> before = existing.get(entry.getKey());
            if (before == null) {
                jdbc.sql("insert into " + table + " (" + holder.column + ", " + keyColumn
                                + ", actions, created_by, updated_by) values (:id, :key, cast(:actions as text[]), "
                                + ":actor, :actor)")
                        .param("id", id).param("key", entry.getKey())
                        .param("actions", entry.getValue().toArray(String[]::new))
                        .param("actor", actor.value()).update();
                added++;
            } else if (!sameActions(before, entry.getValue())) {
                jdbc.sql("update " + table + " set actions = cast(:actions as text[]), version = version + 1, "
                                + "updated_by = :actor where " + holder.column + " = :id and " + keyColumn
                                + " = :key and deleted_at is null")
                        .param("actions", entry.getValue().toArray(String[]::new)).param("actor", actor.value())
                        .param("id", id).param("key", entry.getKey()).update();
                changed++;
            }
        }
        for (String key : existing.keySet()) {
            if (!wanted.containsKey(key)) {
                jdbc.sql("update " + table + " set deleted_at = now(), deleted_by = :actor, version = version + 1, "
                                + "updated_by = :actor where " + holder.column + " = :id and " + keyColumn
                                + " = :key and deleted_at is null")
                        .param("actor", actor.value()).param("id", id).param("key", key).update();
                removed++;
            }
        }
        return new int[] {added, changed, removed};
    }

    private static boolean sameActions(List<String> left, List<String> right) {
        return Set.copyOf(left).equals(Set.copyOf(right));
    }

    /** The sorted action keys per key, for storage. Empty entries are left out. */
    private static <E extends Enum<E>> Map<String, List<String>> toKeys(Map<String, Set<E>> source) {
        Map<String, List<String>> result = new HashMap<>();
        source.forEach((key, actions) -> {
            if (!actions.isEmpty()) {
                result.put(key, actions.stream().map(DataAccessStore::keyOf).sorted().toList());
            }
        });
        return result;
    }

    private static String keyOf(Enum<?> action) {
        return action instanceof ObjectAction object ? object.key() : ((FieldAction) action).key();
    }

    private static Set<ObjectAction> objectActions(List<String> keys) {
        Set<ObjectAction> result = EnumSet.noneOf(ObjectAction.class);
        keys.forEach(key -> ObjectAction.fromKey(key).ifPresent(result::add));
        return result;
    }

    private static Set<FieldAction> fieldActions(List<String> keys) {
        Set<FieldAction> result = EnumSet.noneOf(FieldAction.class);
        keys.forEach(key -> FieldAction.fromKey(key).ifPresent(result::add));
        return result;
    }
}
