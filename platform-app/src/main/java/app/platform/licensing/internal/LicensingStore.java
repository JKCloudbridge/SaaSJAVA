package app.platform.licensing.internal;

import app.platform.licensing.FeatureView;
import app.platform.licensing.LicenceTypeView;
import app.platform.licensing.PoolView;
import app.platform.licensing.SubscriptionView;
import app.platform.sharedkernel.ActorId;
import app.platform.sharedkernel.TenantId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.Collection;
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
 * Every statement of the licensing module (ADR-0031). The catalogue, the subscriptions and the overrides are
 * platform-level tables and need no tenant; the pools and assignments are tenant-scoped, so those statements run under
 * the tenant context that was open when the transaction began and row level security refuses anything else. The
 * organization is never passed in for them: the column default and the policy take it from the transaction.
 */
@Repository
class LicensingStore {

    record PlanRow(UUID id, String key, String name, Integer trialDays) {
    }

    record SubscriptionRow(UUID id, UUID planId, SubscriptionView view) {
    }

    record PoolRow(UUID id, int quantity) {
    }

    record AssignmentRow(UUID id, String licenceType) {
    }

    private final JdbcClient jdbc;

    LicensingStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ---- catalogue: licence types, features, plans (platform-level) ----

    Optional<UUID> licenceTypeId(String key) {
        return jdbc.sql("select id from licence_type where key = :key and deleted_at is null")
                .param("key", key).query(UUID.class).optional();
    }

    Map<UUID, String> licenceTypeKeys() {
        Map<UUID, String> result = new HashMap<>();
        jdbc.sql("select id, key from licence_type where deleted_at is null")
                .query((rs, row) -> result.put(rs.getObject("id", UUID.class), rs.getString("key"))).list();
        return result;
    }

    List<LicenceTypeView> licenceTypes() {
        return jdbc.sql("select key, name from licence_type where deleted_at is null order by key")
                .query((rs, row) -> new LicenceTypeView(rs.getString("key"), rs.getString("name"))).list();
    }

    void insertLicenceType(String key, String name, ActorId actor) {
        jdbc.sql("insert into licence_type (key, name, created_by, updated_by) values (:key, :name, :actor, :actor)")
                .param("key", key).param("name", name).param("actor", actor.value()).update();
    }

    Optional<UUID> featureId(String key) {
        return jdbc.sql("select id from feature where key = :key and deleted_at is null")
                .param("key", key).query(UUID.class).optional();
    }

    List<FeatureView> features() {
        return jdbc.sql("select key, name from feature where deleted_at is null order by key")
                .query((rs, row) -> new FeatureView(rs.getString("key"), rs.getString("name"))).list();
    }

    void insertFeature(String key, String name, ActorId actor) {
        jdbc.sql("insert into feature (key, name, created_by, updated_by) values (:key, :name, :actor, :actor)")
                .param("key", key).param("name", name).param("actor", actor.value()).update();
    }

    Optional<PlanRow> findPlan(String key) {
        return jdbc.sql("select id, key, name, trial_days from plan where key = :key and deleted_at is null")
                .param("key", key).query(LicensingStore::plan).optional();
    }

    Optional<PlanRow> findPlanById(UUID id) {
        return jdbc.sql("select id, key, name, trial_days from plan where id = :id and deleted_at is null")
                .param("id", id).query(LicensingStore::plan).optional();
    }

    List<PlanRow> plans() {
        return jdbc.sql("select id, key, name, trial_days from plan where deleted_at is null order by key")
                .query(LicensingStore::plan).list();
    }

    /** Plan identifier to quantity per licence type key. */
    Map<UUID, Map<String, Integer>> planLicences() {
        Map<UUID, Map<String, Integer>> result = new HashMap<>();
        jdbc.sql("select pl.plan_id, t.key, pl.quantity from plan_licence pl "
                        + "join licence_type t on t.id = pl.licence_type_id "
                        + "where pl.deleted_at is null and t.deleted_at is null order by t.key")
                .query((rs, row) -> {
                    result.computeIfAbsent(rs.getObject("plan_id", UUID.class), id -> new LinkedHashMap<>())
                            .put(rs.getString("key"), rs.getInt("quantity"));
                    return null;
                }).list();
        return result;
    }

    /** Plan identifier to the feature keys it includes. */
    Map<UUID, Set<String>> planFeatures() {
        Map<UUID, Set<String>> result = new HashMap<>();
        jdbc.sql("select pf.plan_id, f.key from plan_feature pf join feature f on f.id = pf.feature_id "
                        + "where pf.deleted_at is null and f.deleted_at is null")
                .query((rs, row) -> {
                    result.computeIfAbsent(rs.getObject("plan_id", UUID.class), id -> new HashSet<>())
                            .add(rs.getString("key"));
                    return null;
                }).list();
        return result;
    }

    UUID insertPlan(String key, String name, Integer trialDays, ActorId actor) {
        return jdbc.sql("insert into plan (key, name, trial_days, created_by, updated_by) "
                        + "values (:key, :name, :days, :actor, :actor) returning id")
                .param("key", key).param("name", name).param("days", trialDays, Types.INTEGER)
                .param("actor", actor.value()).query(UUID.class).single();
    }

    void updatePlan(UUID id, String name, Integer trialDays, ActorId actor) {
        jdbc.sql("update plan set name = :name, trial_days = :days, version = version + 1, updated_by = :actor "
                        + "where id = :id and deleted_at is null")
                .param("name", name).param("days", trialDays, Types.INTEGER).param("actor", actor.value())
                .param("id", id).update();
    }

    /** Makes the plan's quantities exactly {@code wanted} (licence type key to quantity). */
    void replacePlanLicences(UUID planId, Map<String, Integer> wanted, ActorId actor) {
        Map<String, UUID> existing = new HashMap<>();
        jdbc.sql("select pl.id, t.key from plan_licence pl join licence_type t on t.id = pl.licence_type_id "
                        + "where pl.plan_id = :plan and pl.deleted_at is null")
                .param("plan", planId)
                .query((rs, row) -> existing.put(rs.getString("key"), rs.getObject("id", UUID.class))).list();
        for (Map.Entry<String, UUID> entry : existing.entrySet()) {
            if (!wanted.containsKey(entry.getKey())) {
                jdbc.sql("update plan_licence set deleted_at = now(), deleted_by = :actor, version = version + 1, "
                                + "updated_by = :actor where id = :id")
                        .param("actor", actor.value()).param("id", entry.getValue()).update();
            }
        }
        for (Map.Entry<String, Integer> entry : wanted.entrySet()) {
            UUID known = existing.get(entry.getKey());
            if (known != null) {
                jdbc.sql("update plan_licence set quantity = :quantity, version = version + 1, updated_by = :actor "
                                + "where id = :id")
                        .param("quantity", entry.getValue()).param("actor", actor.value()).param("id", known)
                        .update();
            } else {
                jdbc.sql("insert into plan_licence (plan_id, licence_type_id, quantity, created_by, updated_by) "
                                + "select :plan, t.id, :quantity, :actor, :actor from licence_type t "
                                + "where t.key = :key and t.deleted_at is null")
                        .param("plan", planId).param("quantity", entry.getValue()).param("actor", actor.value())
                        .param("key", entry.getKey()).update();
            }
        }
    }

    /** Makes the plan's features exactly {@code wanted} (feature keys). */
    void replacePlanFeatures(UUID planId, Set<String> wanted, ActorId actor) {
        Map<String, UUID> existing = new HashMap<>();
        jdbc.sql("select pf.id, f.key from plan_feature pf join feature f on f.id = pf.feature_id "
                        + "where pf.plan_id = :plan and pf.deleted_at is null")
                .param("plan", planId)
                .query((rs, row) -> existing.put(rs.getString("key"), rs.getObject("id", UUID.class))).list();
        for (Map.Entry<String, UUID> entry : existing.entrySet()) {
            if (!wanted.contains(entry.getKey())) {
                jdbc.sql("update plan_feature set deleted_at = now(), deleted_by = :actor, version = version + 1, "
                                + "updated_by = :actor where id = :id")
                        .param("actor", actor.value()).param("id", entry.getValue()).update();
            }
        }
        for (String key : wanted) {
            if (!existing.containsKey(key)) {
                jdbc.sql("insert into plan_feature (plan_id, feature_id, created_by, updated_by) "
                                + "select :plan, f.id, :actor, :actor from feature f "
                                + "where f.key = :key and f.deleted_at is null")
                        .param("plan", planId).param("actor", actor.value()).param("key", key).update();
            }
        }
    }

    // ---- subscriptions (platform-level) ----

    private static final String SUBSCRIPTION_COLUMNS = "s.id, s.bound_tenant_id, s.plan_id, p.key, p.name, s.status, "
            + "s.started_at, s.trial_ends_at, s.period_ends_at";

    Optional<SubscriptionRow> subscription(TenantId tenant) {
        return jdbc.sql("select " + SUBSCRIPTION_COLUMNS + " from subscription s join plan p on p.id = s.plan_id "
                        + "where s.bound_tenant_id = :tenant and s.deleted_at is null")
                .param("tenant", tenant.value())
                .query((rs, row) -> subscriptionRow(rs)).optional();
    }

    Map<TenantId, SubscriptionView> subscriptions(Collection<TenantId> tenants) {
        Map<TenantId, SubscriptionView> result = new HashMap<>();
        if (tenants.isEmpty()) {
            return result;
        }
        jdbc.sql("select " + SUBSCRIPTION_COLUMNS + " from subscription s join plan p on p.id = s.plan_id "
                        + "where s.bound_tenant_id in (:tenants) and s.deleted_at is null")
                .param("tenants", tenants.stream().map(TenantId::value).toList())
                .query((rs, row) -> result.put(new TenantId(rs.getObject("bound_tenant_id", UUID.class)),
                        subscriptionRow(rs).view()))
                .list();
        return result;
    }

    void insertSubscription(TenantId tenant, UUID planId, String status, Instant trialEndsAt, ActorId actor) {
        jdbc.sql("insert into subscription (bound_tenant_id, plan_id, status, trial_ends_at, created_by, updated_by) "
                        + "values (:tenant, :plan, :status, :trial, :actor, :actor)")
                .param("tenant", tenant.value()).param("plan", planId).param("status", status)
                .param("trial", ts(trialEndsAt), Types.TIMESTAMP).param("actor", actor.value()).update();
    }

    void updateSubscription(UUID id, UUID planId, String status, Instant trialEndsAt, Instant periodEndsAt,
            ActorId actor) {
        jdbc.sql("update subscription set plan_id = :plan, status = :status, trial_ends_at = :trial, "
                        + "period_ends_at = :period, version = version + 1, updated_by = :actor "
                        + "where id = :id and deleted_at is null")
                .param("plan", planId).param("status", status).param("trial", ts(trialEndsAt), Types.TIMESTAMP)
                .param("period", ts(periodEndsAt), Types.TIMESTAMP).param("actor", actor.value())
                .param("id", id).update();
    }

    // ---- pools and assignments (tenant-scoped: the current tenant context) ----

    List<PoolView> pools() {
        return jdbc.sql("select t.key, t.name, p.quantity, "
                        + "(select count(*) from licence_assignment a where a.licence_type_id = p.licence_type_id "
                        + "and a.deleted_at is null) as assigned "
                        + "from licence_pool p join licence_type t on t.id = p.licence_type_id "
                        + "where p.deleted_at is null order by t.key")
                .query((rs, row) -> new PoolView(rs.getString("key"), rs.getString("name"),
                        rs.getInt("quantity"), rs.getInt("assigned")))
                .list();
    }

    /** The pool of a licence type of the current organization, locked until the transaction ends. */
    Optional<PoolRow> lockPool(UUID licenceTypeId) {
        return jdbc.sql("select id, quantity from licence_pool where licence_type_id = :type and deleted_at is null "
                        + "for update")
                .param("type", licenceTypeId)
                .query((rs, row) -> new PoolRow(rs.getObject("id", UUID.class), rs.getInt("quantity")))
                .optional();
    }

    int used(UUID licenceTypeId) {
        return jdbc.sql("select count(*) from licence_assignment where licence_type_id = :type "
                        + "and deleted_at is null")
                .param("type", licenceTypeId).query(Integer.class).single();
    }

    void insertPool(UUID licenceTypeId, int quantity, ActorId actor) {
        jdbc.sql("insert into licence_pool (licence_type_id, quantity, created_by, updated_by) "
                        + "values (:type, :quantity, :actor, :actor)")
                .param("type", licenceTypeId).param("quantity", quantity).param("actor", actor.value()).update();
    }

    void updatePool(UUID poolId, int quantity, ActorId actor) {
        jdbc.sql("update licence_pool set quantity = :quantity, version = version + 1, updated_by = :actor "
                        + "where id = :id and deleted_at is null")
                .param("quantity", quantity).param("actor", actor.value()).param("id", poolId).update();
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

    /** Licence type key by membership, for the current organization: the licences held for profiles. */
    Map<UUID, String> assigned() {
        Map<UUID, String> result = new HashMap<>();
        jdbc.sql("select a.membership_id, t.key from licence_assignment a "
                        + "join licence_type t on t.id = a.licence_type_id "
                        + "where a.purpose = 'PROFILE' and a.deleted_at is null")
                .query((rs, row) -> result.put(rs.getObject("membership_id", UUID.class), rs.getString("key")))
                .list();
        return result;
    }

    /** The licence type key the member holds for their profile. */
    Optional<String> profileLicenceOf(UUID membershipId) {
        return jdbc.sql("select t.key from licence_assignment a join licence_type t on t.id = a.licence_type_id "
                        + "where a.membership_id = :membership and a.purpose = 'PROFILE' and a.deleted_at is null")
                .param("membership", membershipId).query(String.class).optional();
    }

    /** How many members hold a licence for each access policy. */
    Map<UUID, Integer> policyUses() {
        Map<UUID, Integer> result = new HashMap<>();
        jdbc.sql("select a.source_id, count(*) as uses from licence_assignment a "
                        + "where a.purpose = 'ACCESS_POLICY' and a.deleted_at is null group by a.source_id")
                .query((rs, row) -> result.put(rs.getObject("source_id", UUID.class), rs.getInt("uses")))
                .list();
        return result;
    }

    /** The licence the member holds for their profile, locked until the transaction ends. */
    Optional<AssignmentRow> assignmentOf(UUID membershipId) {
        return jdbc.sql("select a.id, t.key from licence_assignment a join licence_type t on t.id = a.licence_type_id "
                        + "where a.membership_id = :membership and a.purpose = 'PROFILE' and a.deleted_at is null "
                        + "for update of a")
                .param("membership", membershipId)
                .query((rs, row) -> new AssignmentRow(rs.getObject("id", UUID.class), rs.getString("key")))
                .optional();
    }

    /** The licence the member holds for the access policy, locked until the transaction ends. */
    Optional<AssignmentRow> policyAssignmentOf(UUID membershipId, UUID policyId) {
        return jdbc.sql("select a.id, t.key from licence_assignment a join licence_type t on t.id = a.licence_type_id "
                        + "where a.membership_id = :membership and a.purpose = 'ACCESS_POLICY' "
                        + "and a.source_id = :policy and a.deleted_at is null for update of a")
                .param("membership", membershipId).param("policy", policyId)
                .query((rs, row) -> new AssignmentRow(rs.getObject("id", UUID.class), rs.getString("key")))
                .optional();
    }

    void insertAssignment(UUID membershipId, UUID licenceTypeId, ActorId actor) {
        jdbc.sql("insert into licence_assignment (membership_id, licence_type_id, created_by, updated_by) "
                        + "values (:membership, :type, :actor, :actor)")
                .param("membership", membershipId).param("type", licenceTypeId).param("actor", actor.value())
                .update();
    }

    void insertPolicyAssignment(UUID membershipId, UUID licenceTypeId, UUID policyId, ActorId actor) {
        jdbc.sql("insert into licence_assignment (membership_id, licence_type_id, purpose, source_id, created_by, "
                        + "updated_by) values (:membership, :type, 'ACCESS_POLICY', :policy, :actor, :actor)")
                .param("membership", membershipId).param("type", licenceTypeId).param("policy", policyId)
                .param("actor", actor.value()).update();
    }

    /** Takes the licence for the profile back. @return whether the member held one */
    boolean releaseAssignment(UUID membershipId, ActorId actor) {
        return jdbc.sql("update licence_assignment set deleted_at = now(), deleted_by = :actor, "
                        + "version = version + 1, updated_by = :actor "
                        + "where membership_id = :membership and purpose = 'PROFILE' and deleted_at is null")
                .param("actor", actor.value()).param("membership", membershipId).update() > 0;
    }

    /** Takes the licence for one access policy back. @return whether the member held one */
    boolean releasePolicyAssignment(UUID membershipId, UUID policyId, ActorId actor) {
        return jdbc.sql("update licence_assignment set deleted_at = now(), deleted_by = :actor, "
                        + "version = version + 1, updated_by = :actor "
                        + "where membership_id = :membership and purpose = 'ACCESS_POLICY' and source_id = :policy "
                        + "and deleted_at is null")
                .param("actor", actor.value()).param("membership", membershipId).param("policy", policyId)
                .update() > 0;
    }

    /** Takes every licence of the member back. @return how many were held */
    int releaseAllAssignments(UUID membershipId, ActorId actor) {
        return jdbc.sql("update licence_assignment set deleted_at = now(), deleted_by = :actor, "
                        + "version = version + 1, updated_by = :actor "
                        + "where membership_id = :membership and deleted_at is null")
                .param("actor", actor.value()).param("membership", membershipId).update();
    }

    // ---- entitlements (platform-level) ----

    /** Feature key to the explicit switch of one organization. */
    Map<String, Boolean> overrides(TenantId tenant) {
        Map<String, Boolean> result = new HashMap<>();
        jdbc.sql("select f.key, o.enabled from entitlement_override o join feature f on f.id = o.feature_id "
                        + "where o.bound_tenant_id = :tenant and o.deleted_at is null and f.deleted_at is null")
                .param("tenant", tenant.value())
                .query((rs, row) -> result.put(rs.getString("key"), rs.getBoolean("enabled"))).list();
        return result;
    }

    void upsertOverride(TenantId tenant, UUID featureId, boolean enabled, ActorId actor) {
        int changed = jdbc.sql("update entitlement_override set enabled = :enabled, version = version + 1, "
                        + "updated_by = :actor where bound_tenant_id = :tenant and feature_id = :feature "
                        + "and deleted_at is null")
                .param("enabled", enabled).param("actor", actor.value()).param("tenant", tenant.value())
                .param("feature", featureId).update();
        if (changed == 0) {
            jdbc.sql("insert into entitlement_override (bound_tenant_id, feature_id, enabled, created_by, updated_by) "
                            + "values (:tenant, :feature, :enabled, :actor, :actor)")
                    .param("tenant", tenant.value()).param("feature", featureId).param("enabled", enabled)
                    .param("actor", actor.value()).update();
        }
    }

    void removeOverride(TenantId tenant, UUID featureId, ActorId actor) {
        jdbc.sql("update entitlement_override set deleted_at = now(), deleted_by = :actor, version = version + 1, "
                        + "updated_by = :actor where bound_tenant_id = :tenant and feature_id = :feature "
                        + "and deleted_at is null")
                .param("actor", actor.value()).param("tenant", tenant.value()).param("feature", featureId).update();
    }

    // ---- mapping ----

    private static PlanRow plan(ResultSet rs, int row) throws SQLException {
        int days = rs.getInt("trial_days");
        return new PlanRow(rs.getObject("id", UUID.class), rs.getString("key"), rs.getString("name"),
                rs.wasNull() ? null : days);
    }

    private static SubscriptionRow subscriptionRow(ResultSet rs) throws SQLException {
        return new SubscriptionRow(rs.getObject("id", UUID.class), rs.getObject("plan_id", UUID.class),
                new SubscriptionView(rs.getString("key"), rs.getString("name"), rs.getString("status"),
                        rs.getTimestamp("started_at").toInstant(), instant(rs.getTimestamp("trial_ends_at")),
                        instant(rs.getTimestamp("period_ends_at"))));
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static Timestamp ts(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }
}
