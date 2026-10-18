package app.platform.testsupport.tenancy;

import app.platform.sharedkernel.ActorId;
import java.util.List;
import java.util.UUID;

/**
 * The register of every tenant-scoped table of the platform (a table with a {@code tenant_id} column).
 * Each entry is run through {@link CrossTenantLeakHarness} by {@code TenantIsolationIT}, and a second test fails when a
 * table with a {@code tenant_id} column exists that is not listed here, so a new table cannot skip its isolation
 * test.
 *
 * <p><strong>Adding a table:</strong> add one {@link TenantScopedTable} below (the table name and how to insert one
 * valid row for a given tenant) and run {@code ./mvnw verify}. See {@code docs/tenant-isolation-testing.md}.
 */
public final class TenantScopedTables {

    /** The outbox (Sprint 2). */
    public static final TenantScopedTable OUTBOX_EVENT = new TenantScopedTable("outbox_event",
            (connection, tenant) -> TenantFixtures.update(connection,
                    "insert into outbox_event (tenant_id, event_type, payload, created_by, updated_by) "
                            + "values (?, 'probe.created', '{}'::jsonb, ?, ?)",
                    tenant, ActorId.SYSTEM.value(), ActorId.SYSTEM.value()));

    /** The idempotency markers of consumers (Sprint 2). */
    public static final TenantScopedTable PROCESSED_EVENT = new TenantScopedTable("processed_event",
            (connection, tenant) -> TenantFixtures.update(connection,
                    "insert into processed_event (tenant_id, consumer, event_id, created_by, updated_by) "
                            + "values (?, 'probe', ?, ?, ?)",
                    tenant, UUID.randomUUID(), ActorId.SYSTEM.value(), ActorId.SYSTEM.value()));

    /** The memberships of users in organizations (Sprint 4). Each probe row needs a user, which it creates. */
    public static final TenantScopedTable MEMBERSHIP = new TenantScopedTable("membership", (connection, tenant) -> {
        UUID user = UUID.randomUUID();
        TenantFixtures.update(connection,
                "insert into platform_user (id, email, display_name, created_by, updated_by) "
                        + "values (?, ?, 'Probe', ?, ?)",
                user, "probe-" + user + "@example.test", ActorId.SYSTEM.value(), ActorId.SYSTEM.value());
        TenantFixtures.update(connection,
                "insert into membership (tenant_id, user_id, created_by, updated_by) values (?, ?, ?, ?)",
                tenant, user, ActorId.SYSTEM.value(), ActorId.SYSTEM.value());
    });

    /** The invitations of organizations (Sprint 5). */
    public static final TenantScopedTable INVITATION = new TenantScopedTable("invitation",
            (connection, tenant) -> TenantFixtures.update(connection,
                    "insert into invitation (tenant_id, email, expires_at, created_by, updated_by) "
                            + "values (?, ?, now() + interval '1 day', ?, ?)",
                    tenant, "probe-" + UUID.randomUUID() + "@example.test", ActorId.SYSTEM.value(),
                    ActorId.SYSTEM.value()));

    /**
     * The licence pools of organizations (Sprint 6). There is one pool per licence type and organization, so every
     * probe row first makes a licence type of its own in the (platform-level) catalogue.
     */
    public static final TenantScopedTable LICENCE_POOL = new TenantScopedTable("licence_pool",
            (connection, tenant) -> {
                UUID type = probeLicenceType(connection);
                TenantFixtures.update(connection,
                        "insert into licence_pool (tenant_id, licence_type_id, quantity, created_by, updated_by) "
                                + "values (?, ?, 3, ?, ?)",
                        tenant, type, ActorId.SYSTEM.value(), ActorId.SYSTEM.value());
            });

    /**
     * The licences held by members (Sprint 6). A probe row needs a licence type, a pool, a user and a membership, which
     * it creates.
     */
    public static final TenantScopedTable LICENCE_ASSIGNMENT = new TenantScopedTable("licence_assignment",
            (connection, tenant) -> {
                UUID user = UUID.randomUUID();
                UUID membership = UUID.randomUUID();
                UUID type = probeLicenceType(connection);
                TenantFixtures.update(connection,
                        "insert into platform_user (id, email, display_name, created_by, updated_by) "
                                + "values (?, ?, 'Probe', ?, ?)",
                        user, "probe-" + user + "@example.test", ActorId.SYSTEM.value(), ActorId.SYSTEM.value());
                TenantFixtures.update(connection,
                        "insert into membership (id, tenant_id, user_id, created_by, updated_by) "
                                + "values (?, ?, ?, ?, ?)",
                        membership, tenant, user, ActorId.SYSTEM.value(), ActorId.SYSTEM.value());
                TenantFixtures.update(connection,
                        "insert into licence_pool (tenant_id, licence_type_id, quantity, created_by, updated_by) "
                                + "values (?, ?, 3, ?, ?)",
                        tenant, type, ActorId.SYSTEM.value(), ActorId.SYSTEM.value());
                TenantFixtures.update(connection,
                        "insert into licence_assignment (tenant_id, membership_id, licence_type_id, created_by, "
                                + "updated_by) values (?, ?, ?, ?, ?)",
                        tenant, membership, type, ActorId.SYSTEM.value(), ActorId.SYSTEM.value());
            });

    /** The support-access requests and grants of organizations (Sprint 6). The probe needs a platform person. */
    public static final TenantScopedTable SUPPORT_ACCESS_GRANT = new TenantScopedTable("support_access_grant",
            (connection, tenant) -> {
                UUID person = UUID.randomUUID();
                TenantFixtures.update(connection,
                        "insert into platform_user (id, email, display_name, created_by, updated_by) "
                                + "values (?, ?, 'Probe', ?, ?)",
                        person, "probe-" + person + "@example.test", ActorId.SYSTEM.value(), ActorId.SYSTEM.value());
                TenantFixtures.update(connection,
                        "insert into support_access_grant (tenant_id, requested_by, reason, requested_minutes, "
                                + "request_expires_at, created_by, updated_by) "
                                + "values (?, ?, 'probe', 30, now() + interval '1 day', ?, ?)",
                        tenant, person, ActorId.SYSTEM.value(), ActorId.SYSTEM.value());
            });

    /** The profiles of organizations (Sprint 7). A probe row needs a licence type of its own. */
    public static final TenantScopedTable PROFILE = new TenantScopedTable("profile", (connection, tenant) -> {
        UUID type = probeLicenceType(connection);
        TenantFixtures.update(connection,
                "insert into profile (tenant_id, name, licence_type_id, created_by, updated_by) "
                        + "values (?, ?, ?, ?, ?)",
                tenant, "probe-" + UUID.randomUUID(), type, ActorId.SYSTEM.value(), ActorId.SYSTEM.value());
    });

    /** The access policies of organizations (Sprint 7). */
    public static final TenantScopedTable ACCESS_POLICY = new TenantScopedTable("access_policy",
            (connection, tenant) -> TenantFixtures.update(connection,
                    "insert into access_policy (tenant_id, name, created_by, updated_by) values (?, ?, ?, ?)",
                    tenant, "probe-" + UUID.randomUUID(), ActorId.SYSTEM.value(), ActorId.SYSTEM.value()));

    /** The role hierarchies of organizations (Sprint 7). */
    public static final TenantScopedTable SECURITY_ROLE = new TenantScopedTable("security_role",
            (connection, tenant) -> TenantFixtures.update(connection,
                    "insert into security_role (tenant_id, name, created_by, updated_by) values (?, ?, ?, ?)",
                    tenant, "probe-" + UUID.randomUUID(), ActorId.SYSTEM.value(), ActorId.SYSTEM.value()));

    /** The profile and role of a member (Sprint 7). A probe row needs a profile, a user and a membership. */
    public static final TenantScopedTable MEMBER_ACCESS = new TenantScopedTable("member_access",
            (connection, tenant) -> {
                UUID membership = probeMembership(connection, tenant);
                UUID profile = probeProfile(connection, tenant);
                TenantFixtures.update(connection,
                        "insert into member_access (tenant_id, membership_id, profile_id, created_by, updated_by) "
                                + "values (?, ?, ?, ?, ?)",
                        tenant, membership, profile, ActorId.SYSTEM.value(), ActorId.SYSTEM.value());
            });

    /** The access policies assigned to members (Sprint 7). */
    public static final TenantScopedTable MEMBER_ACCESS_POLICY = new TenantScopedTable("member_access_policy",
            (connection, tenant) -> {
                UUID membership = probeMembership(connection, tenant);
                UUID policy = UUID.randomUUID();
                TenantFixtures.update(connection,
                        "insert into access_policy (id, tenant_id, name, created_by, updated_by) "
                                + "values (?, ?, ?, ?, ?)",
                        policy, tenant, "probe-" + policy, ActorId.SYSTEM.value(), ActorId.SYSTEM.value());
                TenantFixtures.update(connection,
                        "insert into member_access_policy (tenant_id, membership_id, access_policy_id, created_by, "
                                + "updated_by) values (?, ?, ?, ?, ?)",
                        tenant, membership, policy, ActorId.SYSTEM.value(), ActorId.SYSTEM.value());
            });

    /** The abilities given to single members (Sprint 7). */
    public static final TenantScopedTable MEMBER_GRANT = new TenantScopedTable("member_grant",
            (connection, tenant) -> {
                UUID membership = probeMembership(connection, tenant);
                TenantFixtures.update(connection,
                        "insert into member_grant (tenant_id, membership_id, ability, created_by, updated_by) "
                                + "values (?, ?, 'members.view', ?, ?)",
                        tenant, membership, ActorId.SYSTEM.value(), ActorId.SYSTEM.value());
            });

    /** The public groups of organizations (Sprint 8). */
    public static final TenantScopedTable PUBLIC_GROUP = new TenantScopedTable("public_group",
            (connection, tenant) -> TenantFixtures.update(connection,
                    "insert into public_group (tenant_id, name, created_by, updated_by) values (?, ?, ?, ?)",
                    tenant, "probe-" + UUID.randomUUID(), ActorId.SYSTEM.value(), ActorId.SYSTEM.value()));

    /** The people and nested groups in public groups (Sprint 8). A probe row needs a group and a membership. */
    public static final TenantScopedTable PUBLIC_GROUP_MEMBER = new TenantScopedTable("public_group_member",
            (connection, tenant) -> {
                UUID membership = probeMembership(connection, tenant);
                UUID group = probeGroup(connection, tenant);
                TenantFixtures.update(connection,
                        "insert into public_group_member (tenant_id, group_id, member_membership_id, created_by, "
                                + "updated_by) values (?, ?, ?, ?, ?)",
                        tenant, group, membership, ActorId.SYSTEM.value(), ActorId.SYSTEM.value());
            });

    /** The access policies given to public groups (Sprint 8). */
    public static final TenantScopedTable PUBLIC_GROUP_ACCESS_POLICY = new TenantScopedTable(
            "public_group_access_policy", (connection, tenant) -> {
                UUID group = probeGroup(connection, tenant);
                UUID policy = UUID.randomUUID();
                TenantFixtures.update(connection,
                        "insert into access_policy (id, tenant_id, name, created_by, updated_by) "
                                + "values (?, ?, ?, ?, ?)",
                        policy, tenant, "probe-" + policy, ActorId.SYSTEM.value(), ActorId.SYSTEM.value());
                TenantFixtures.update(connection,
                        "insert into public_group_access_policy (tenant_id, group_id, access_policy_id, created_by, "
                                + "updated_by) values (?, ?, ?, ?, ?)",
                        tenant, group, policy, ActorId.SYSTEM.value(), ActorId.SYSTEM.value());
            });

    /** What a profile allows on objects (Sprint 8). */
    public static final TenantScopedTable OBJECT_PERMISSION = new TenantScopedTable("object_permission",
            (connection, tenant) -> TenantFixtures.update(connection,
                    "insert into object_permission (tenant_id, profile_id, object_key, actions, created_by, "
                            + "updated_by) values (?, ?, 'object-a', array['read'], ?, ?)",
                    tenant, probeProfile(connection, tenant), ActorId.SYSTEM.value(), ActorId.SYSTEM.value()));

    /** What a profile allows on fields (Sprint 8). */
    public static final TenantScopedTable FIELD_PERMISSION = new TenantScopedTable("field_permission",
            (connection, tenant) -> TenantFixtures.update(connection,
                    "insert into field_permission (tenant_id, profile_id, field_key, actions, created_by, "
                            + "updated_by) values (?, ?, 'object-a.field-a', array['read'], ?, ?)",
                    tenant, probeProfile(connection, tenant), ActorId.SYSTEM.value(), ActorId.SYSTEM.value()));

    /**
     * The security version of an organization (Sprint 9). There is one live row per organization and other probe rows
     * may have created it already, so each call retires the live one and adds a new one: every call adds one row, as
     * the harness expects.
     */
    public static final TenantScopedTable SECURITY_VERSION = new TenantScopedTable("security_version",
            (connection, tenant) -> {
                TenantFixtures.update(connection,
                        "update security_version set deleted_at = now(), deleted_by = ?, version = version + 1, "
                                + "updated_by = ? where tenant_id = ? and deleted_at is null",
                        ActorId.SYSTEM.value(), ActorId.SYSTEM.value(), tenant);
                TenantFixtures.update(connection,
                        "insert into security_version (tenant_id, created_by, updated_by) values (?, ?, ?)",
                        tenant, ActorId.SYSTEM.value(), ActorId.SYSTEM.value());
            });

    /** The custom objects of organizations (Sprint 10). Each probe row has a name of its own. */
    public static final TenantScopedTable OBJECT_DEFINITION = new TenantScopedTable("object_definition",
            (connection, tenant) -> probeObject(connection, tenant));

    /** The custom fields of organizations (Sprint 10). A probe row needs a custom object, which it creates. */
    public static final TenantScopedTable FIELD_DEFINITION = new TenantScopedTable("field_definition",
            (connection, tenant) -> {
                String object = probeObject(connection, tenant);
                TenantFixtures.update(connection,
                        "insert into field_definition (tenant_id, object_api_name, api_name, label, data_type, "
                                + "created_by, updated_by) values (?, ?, 'probe__c', 'Probe', 'TEXT', ?, ?)",
                        tenant, object, ActorId.SYSTEM.value(), ActorId.SYSTEM.value());
            });

    /**
     * The metadata version of an organization (Sprint 10). There is one live row per organization and the probe rows
     * of the definition tables raise it, so each call retires the live one and adds a new one.
     */
    public static final TenantScopedTable METADATA_VERSION = new TenantScopedTable("metadata_version",
            (connection, tenant) -> {
                TenantFixtures.update(connection,
                        "update metadata_version set deleted_at = now(), deleted_by = ?, version = version + 1, "
                                + "updated_by = ? where tenant_id = ? and deleted_at is null",
                        ActorId.SYSTEM.value(), ActorId.SYSTEM.value(), tenant);
                TenantFixtures.update(connection,
                        "insert into metadata_version (tenant_id, created_by, updated_by) values (?, ?, ?)",
                        tenant, ActorId.SYSTEM.value(), ActorId.SYSTEM.value());
            });

    /** The record types of organizations (Sprint 11). A probe row needs a custom object, which it creates. */
    public static final TenantScopedTable RECORD_TYPE = new TenantScopedTable("record_type",
            (connection, tenant) -> {
                String object = probeObject(connection, tenant);
                TenantFixtures.update(connection,
                        "insert into record_type (tenant_id, object_api_name, api_name, label, created_by, "
                                + "updated_by) values (?, ?, ?, 'Probe', ?, ?)",
                        tenant, object, probeName("Probe"), ActorId.SYSTEM.value(), ActorId.SYSTEM.value());
            });

    /** The change sets of organizations (Sprint 11). Each probe row is an open set with a name of its own. */
    public static final TenantScopedTable METADATA_CHANGE_SET = new TenantScopedTable("metadata_change_set",
            (connection, tenant) -> probeChangeSet(connection, tenant));

    /** The changes inside change sets (Sprint 11). A probe row needs an open change set, which it creates. */
    public static final TenantScopedTable METADATA_CHANGE = new TenantScopedTable("metadata_change",
            (connection, tenant) -> {
                UUID set = probeChangeSet(connection, tenant);
                TenantFixtures.update(connection,
                        "insert into metadata_change (tenant_id, change_set_id, position, kind, object_api_name, "
                                + "item_api_name, payload, created_by, updated_by) values (?, ?, 1, 'DELETE_FIELD', "
                                + "'Account', 'probe__c', '{}'::jsonb, ?, ?)",
                        tenant, set, ActorId.SYSTEM.value(), ActorId.SYSTEM.value());
            });

    /** The releases of organizations (Sprint 11). Each probe row takes the next release number of the tenant. */
    public static final TenantScopedTable METADATA_RELEASE = new TenantScopedTable("metadata_release",
            (connection, tenant) -> TenantFixtures.update(connection,
                    "insert into metadata_release (tenant_id, release_number, kind, summary, undo, "
                            + "metadata_version, created_by, updated_by) select ?, coalesce(max(release_number), 0) "
                            + "+ 1, 'QUICK', '[]'::jsonb, '[]'::jsonb, 0, ?, ? from metadata_release "
                            + "where tenant_id = ?",
                    tenant, ActorId.SYSTEM.value(), ActorId.SYSTEM.value(), tenant));

    /** The page layouts of organizations (Sprint 12). A probe row needs a custom object, which it creates. */
    public static final TenantScopedTable PAGE_LAYOUT = new TenantScopedTable("page_layout",
            (connection, tenant) -> {
                String object = probeObject(connection, tenant);
                TenantFixtures.update(connection,
                        "insert into page_layout (tenant_id, object_api_name, api_name, label, definition, "
                                + "created_by, updated_by) values (?, ?, ?, 'Probe', '{\"sections\": []}'::jsonb, "
                                + "?, ?)",
                        tenant, object, probeName("Probe"), ActorId.SYSTEM.value(), ActorId.SYSTEM.value());
            });

    /** The record pages of organizations (Sprint 12). A probe row needs a custom object, which it creates. */
    public static final TenantScopedTable RECORD_PAGE = new TenantScopedTable("record_page",
            (connection, tenant) -> {
                String object = probeObject(connection, tenant);
                TenantFixtures.update(connection,
                        "insert into record_page (tenant_id, object_api_name, api_name, label, definition, "
                                + "created_by, updated_by) values (?, ?, ?, 'Probe', '{\"components\": []}'::jsonb, "
                                + "?, ?)",
                        tenant, object, probeName("Probe"), ActorId.SYSTEM.value(), ActorId.SYSTEM.value());
            });

    /** The applications of organizations (Sprint 12). Each probe row has a name of its own. */
    public static final TenantScopedTable APPLICATION = new TenantScopedTable("application",
            (connection, tenant) -> TenantFixtures.update(connection,
                    "insert into application (tenant_id, api_name, label, definition, created_by, updated_by) "
                            + "values (?, ?, 'Probe', '{\"items\": []}'::jsonb, ?, ?)",
                    tenant, probeName("Probe"), ActorId.SYSTEM.value(), ActorId.SYSTEM.value()));

    /** The tabs applications can list (Sprint 18). Each probe row has a name of its own. */
    public static final TenantScopedTable TAB = new TenantScopedTable("tab",
            (connection, tenant) -> TenantFixtures.update(connection,
                    "insert into tab (tenant_id, api_name, label, definition, created_by, updated_by) "
                            + "values (?, ?, 'Probe', '{\"kind\": \"CUSTOM\"}'::jsonb, ?, ?)",
                    tenant, probeName("Probe"), ActorId.SYSTEM.value(), ActorId.SYSTEM.value()));

    /** The custom pages of organizations (Sprint 18). Each probe row has a name of its own. */
    public static final TenantScopedTable CUSTOM_PAGE = new TenantScopedTable("custom_page",
            (connection, tenant) -> TenantFixtures.update(connection,
                    "insert into custom_page (tenant_id, api_name, label, definition, created_by, updated_by) "
                            + "values (?, ?, 'Probe', '{\"frontend\": {}}'::jsonb, ?, ?)",
                    tenant, probeName("Probe"), ActorId.SYSTEM.value(), ActorId.SYSTEM.value()));

    /** The uploaded scripts of custom pages (Sprint 18). Each probe row has a hash of its own. */
    public static final TenantScopedTable CUSTOM_PAGE_ASSET = new TenantScopedTable("custom_page_asset",
            (connection, tenant) -> TenantFixtures.update(connection,
                    "insert into custom_page_asset (tenant_id, sha256, size_bytes, created_by, updated_by) "
                            + "values (?, ?, 10, ?, ?)",
                    tenant, UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString()
                            .replace("-", ""), ActorId.SYSTEM.value(), ActorId.SYSTEM.value()));

    /** The layout assignments of organizations (Sprint 12). A probe row needs a custom object, which it creates. */
    public static final TenantScopedTable LAYOUT_ASSIGNMENT = new TenantScopedTable("layout_assignment",
            (connection, tenant) -> {
                String object = probeObject(connection, tenant);
                TenantFixtures.update(connection,
                        "insert into layout_assignment (tenant_id, object_api_name, layout_api_name, created_by, "
                                + "updated_by) values (?, ?, 'Probe__c', ?, ?)",
                        tenant, object, ActorId.SYSTEM.value(), ActorId.SYSTEM.value());
            });

    /** The record page assignments of organizations (Sprint 20). A probe row needs a custom object it creates. */
    public static final TenantScopedTable RECORD_PAGE_ASSIGNMENT = new TenantScopedTable("record_page_assignment",
            (connection, tenant) -> {
                String object = probeObject(connection, tenant);
                TenantFixtures.update(connection,
                        "insert into record_page_assignment (tenant_id, object_api_name, record_page_api_name, "
                                + "created_by, updated_by) values (?, ?, 'Probe__c', ?, ?)",
                        tenant, object, ActorId.SYSTEM.value(), ActorId.SYSTEM.value());
            });

    /** The validation rules of organizations (Sprint 13). A probe row needs a custom object, which it creates. */
    public static final TenantScopedTable VALIDATION_RULE = new TenantScopedTable("validation_rule",
            (connection, tenant) -> {
                String object = probeObject(connection, tenant);
                TenantFixtures.update(connection,
                        "insert into validation_rule (tenant_id, object_api_name, api_name, label, expression, "
                                + "error_message, created_by, updated_by) values (?, ?, ?, 'Probe', 'TRUE', "
                                + "'Probe', ?, ?)",
                        tenant, object, probeName("Probe"), ActorId.SYSTEM.value(), ActorId.SYSTEM.value());
            });

    /** The records of the generic store (Sprint 14). A probe row is a record of a made-up object, owned by nobody. */
    public static final TenantScopedTable OBJECT_RECORD = new TenantScopedTable("object_record",
            (connection, tenant) -> TenantFixtures.update(connection,
                    "insert into object_record (tenant_id, object_api_name, owner_id, payload, created_by, updated_by) "
                            + "values (?, 'Probe__c', ?, '{}'::jsonb, ?, ?)",
                    tenant, ActorId.SYSTEM.value(), ActorId.SYSTEM.value(), ActorId.SYSTEM.value()));

    /**
     * The counters of auto-number fields (Sprint 15). There is one live row per organization, object and field (a
     * partial unique index enforces it), so each call retires the live probe row and adds a new one, as for
     * {@link #SECURITY_VERSION} and {@link #METADATA_VERSION}.
     */
    public static final TenantScopedTable AUTO_NUMBER_COUNTER = new TenantScopedTable("auto_number_counter",
            (connection, tenant) -> {
                TenantFixtures.update(connection,
                        "update auto_number_counter set deleted_at = now(), deleted_by = ?, version = version + 1, "
                                + "updated_by = ? where tenant_id = ? and object_api_name = 'Probe__c' "
                                + "and field_api_name = 'probe__c' and deleted_at is null",
                        ActorId.SYSTEM.value(), ActorId.SYSTEM.value(), tenant);
                TenantFixtures.update(connection,
                        "insert into auto_number_counter (tenant_id, object_api_name, field_api_name, next_value, "
                                + "created_by, updated_by) values (?, 'Probe__c', 'probe__c', 1, ?, ?)",
                        tenant, ActorId.SYSTEM.value(), ActorId.SYSTEM.value());
            });

    /**
     * Which profiles may use a record type (Sprint 15A). A probe row is the assignment of a made-up profile to a
     * made-up record type of a standard object (so no custom object is needed); each call uses a profile of its own
     * because there is one live row per object, record type and profile.
     */
    public static final TenantScopedTable RECORD_TYPE_ASSIGNMENT = new TenantScopedTable("record_type_assignment",
            (connection, tenant) -> TenantFixtures.update(connection,
                    "insert into record_type_assignment (tenant_id, object_api_name, record_type_api_name, "
                            + "profile_id, created_by, updated_by) values (?, 'Account', 'Probe__c', ?, ?, ?)",
                    tenant, UUID.randomUUID(), ActorId.SYSTEM.value(), ActorId.SYSTEM.value()));

    /**
     * Which field holds which slot of the record table (Sprint 16). One live row per organization, object and slot, so
     * each call retires the live probe row and adds a new one.
     */
    public static final TenantScopedTable FIELD_SLOT = new TenantScopedTable("field_slot",
            (connection, tenant) -> {
                TenantFixtures.update(connection,
                        "update field_slot set deleted_at = now(), deleted_by = ?, version = version + 1, "
                                + "updated_by = ? where tenant_id = ? and object_api_name = 'Probe__c' "
                                + "and deleted_at is null",
                        ActorId.SYSTEM.value(), ActorId.SYSTEM.value(), tenant);
                TenantFixtures.update(connection,
                        "insert into field_slot (tenant_id, object_api_name, field_api_name, slot, created_by, "
                                + "updated_by) values (?, 'Probe__c', 'probe__c', 's_text_1', ?, ?)",
                        tenant, ActorId.SYSTEM.value(), ActorId.SYSTEM.value());
            });

    /** Who points at whom (Sprint 16). Each probe row is a pointer of a record of its own. */
    public static final TenantScopedTable RECORD_LINK = new TenantScopedTable("record_link",
            (connection, tenant) -> TenantFixtures.update(connection,
                    "insert into record_link (tenant_id, source_object, source_record, field_api_name, target_object, "
                            + "target_record, created_by, updated_by) values (?, 'Probe__c', ?, 'probe__c', "
                            + "'Probe__c', ?, ?, ?)",
                    tenant, UUID.randomUUID(), UUID.randomUUID(), ActorId.SYSTEM.value(), ActorId.SYSTEM.value()));

    /** Whether the pointers of a field are complete (Sprint 16). One live row per field: retire, then add. */
    public static final TenantScopedTable LINK_INDEX = new TenantScopedTable("link_index",
            (connection, tenant) -> {
                TenantFixtures.update(connection,
                        "update link_index set deleted_at = now(), deleted_by = ?, version = version + 1, "
                                + "updated_by = ? where tenant_id = ? and object_api_name = 'Probe__c' "
                                + "and deleted_at is null",
                        ActorId.SYSTEM.value(), ActorId.SYSTEM.value(), tenant);
                TenantFixtures.update(connection,
                        "insert into link_index (tenant_id, object_api_name, field_api_name, created_by, updated_by) "
                                + "values (?, 'Probe__c', 'probe__c', ?, ?)",
                        tenant, ActorId.SYSTEM.value(), ActorId.SYSTEM.value());
            });

    /**
     * Which unique field or junction an object enforces (Sprint 16A). One live row per organization, object and bit, so
     * each call retires the live probe row and adds a new one.
     */
    public static final TenantScopedTable UNIQUE_ENFORCEMENT = new TenantScopedTable("unique_enforcement",
            (connection, tenant) -> {
                TenantFixtures.update(connection,
                        "update unique_enforcement set deleted_at = now(), deleted_by = ?, version = version + 1, "
                                + "updated_by = ? where tenant_id = ? and object_api_name = 'Probe__c' "
                                + "and deleted_at is null",
                        ActorId.SYSTEM.value(), ActorId.SYSTEM.value(), tenant);
                TenantFixtures.update(connection,
                        "insert into unique_enforcement (tenant_id, object_api_name, kind, field_api_name, "
                                + "constraint_bit, created_by, updated_by) values (?, 'Probe__c', 'VALUE', "
                                + "'probe__c', 0, ?, ?)",
                        tenant, ActorId.SYSTEM.value(), ActorId.SYSTEM.value());
            });

    /**
     * Whether the stored formula results of an object are current (Sprint 16A). One live row per organization and
     * object: retire, then add.
     */
    public static final TenantScopedTable FORMULA_RECALCULATION = new TenantScopedTable("formula_recalculation",
            (connection, tenant) -> {
                TenantFixtures.update(connection,
                        "update formula_recalculation set deleted_at = now(), deleted_by = ?, version = version + 1, "
                                + "updated_by = ? where tenant_id = ? and object_api_name = 'Probe__c' "
                                + "and deleted_at is null",
                        ActorId.SYSTEM.value(), ActorId.SYSTEM.value(), tenant);
                TenantFixtures.update(connection,
                        "insert into formula_recalculation (tenant_id, object_api_name, created_by, updated_by) "
                                + "values (?, 'Probe__c', ?, ?)",
                        tenant, ActorId.SYSTEM.value(), ActorId.SYSTEM.value());
            });

    /** The organization-wide default of an object (Sprint 17). */
    public static final TenantScopedTable OBJECT_SHARING_SETTING = new TenantScopedTable("object_sharing_setting",
            (connection, tenant) -> TenantFixtures.update(connection,
                    "insert into object_sharing_setting (tenant_id, object_key, created_by, updated_by) "
                            + "values (?, ?, ?, ?)",
                    tenant, probeName("S"), ActorId.SYSTEM.value(), ActorId.SYSTEM.value()));

    /** A sharing rule of an object (Sprint 17). A probe row needs a public group to name. */
    public static final TenantScopedTable SHARING_RULE = new TenantScopedTable("sharing_rule",
            (connection, tenant) -> {
                UUID group = probeGroup(connection, tenant);
                TenantFixtures.update(connection,
                        "insert into sharing_rule (tenant_id, object_key, name, kind, criterion, target_type, "
                                + "target_id, access_level, created_by, updated_by) values (?, ?, ?, 'CRITERIA', "
                                + "cast('{\"op\":\"EQ\"}' as jsonb), 'GROUP', ?, 'READ', ?, ?)",
                        tenant, probeName("R"), "probe-" + UUID.randomUUID(), group, ActorId.SYSTEM.value(),
                        ActorId.SYSTEM.value());
            });

    /** A record shared with a user or a group (Sprint 17). A probe row needs a record to share. */
    public static final TenantScopedTable RECORD_SHARE = new TenantScopedTable("record_share",
            (connection, tenant) -> {
                UUID record = UUID.randomUUID();
                TenantFixtures.update(connection,
                        "insert into object_record (id, tenant_id, object_api_name, owner_id, payload, created_by, "
                                + "updated_by) values (?, ?, 'Probe__c', ?, '{}'::jsonb, ?, ?)",
                        record, tenant, ActorId.SYSTEM.value(), ActorId.SYSTEM.value(), ActorId.SYSTEM.value());
                TenantFixtures.update(connection,
                        "insert into record_share (tenant_id, object_api_name, record_id, grantee_type, grantee_id, "
                                + "access_level, created_by, updated_by) values (?, 'Probe__c', ?, 'GROUP', "
                                + "?, 1, ?, ?)",
                        tenant, record, UUID.randomUUID(), ActorId.SYSTEM.value(), ActorId.SYSTEM.value());
            });

    /** A file attached to a record (Sprint 17). A probe row needs a record to hold it. */
    public static final TenantScopedTable RECORD_FILE = new TenantScopedTable("record_file",
            (connection, tenant) -> {
                UUID record = probeRecord(connection, tenant);
                TenantFixtures.update(connection,
                        "insert into record_file (tenant_id, object_api_name, record_id, name, content_type, "
                                + "storage_key, created_by, updated_by) values (?, 'Probe__c', ?, 'probe.bin', "
                                + "'application/zip', ?, ?, ?)",
                        tenant, record, "files/" + tenant + "/" + UUID.randomUUID(), ActorId.SYSTEM.value(),
                        ActorId.SYSTEM.value());
            });

    /** The bytes of a removed file that the object store must still forget (Sprint 17). */
    public static final TenantScopedTable FILE_OBJECT_CLEANUP = new TenantScopedTable("file_object_cleanup",
            (connection, tenant) -> TenantFixtures.update(connection,
                    "insert into file_object_cleanup (tenant_id, storage_key, created_by, updated_by) "
                            + "values (?, ?, ?, ?)",
                    tenant, "files/" + tenant + "/" + UUID.randomUUID(), ActorId.SYSTEM.value(),
                    ActorId.SYSTEM.value()));

    /** An import of a file of rows (Sprint 17). */
    public static final TenantScopedTable IMPORT_JOB = new TenantScopedTable("import_job",
            (connection, tenant) -> TenantFixtures.update(connection,
                    "insert into import_job (tenant_id, object_api_name, started_by, membership_id, operation, "
                            + "created_by, updated_by) values (?, 'Probe__c', ?, ?, 'CREATE', ?, ?)",
                    tenant, UUID.randomUUID(), UUID.randomUUID(), ActorId.SYSTEM.value(),
                    ActorId.SYSTEM.value()));

    /** A row of an import that failed (Sprint 17). A probe row needs an import to belong to. */
    public static final TenantScopedTable IMPORT_ERROR = new TenantScopedTable("import_error",
            (connection, tenant) -> {
                UUID job = UUID.randomUUID();
                TenantFixtures.update(connection,
                        "insert into import_job (id, tenant_id, object_api_name, started_by, membership_id, "
                                + "operation, created_by, updated_by) values (?, ?, 'Probe__c', ?, ?, 'CREATE', "
                                + "?, ?)",
                        job, tenant, UUID.randomUUID(), UUID.randomUUID(), ActorId.SYSTEM.value(),
                        ActorId.SYSTEM.value());
                TenantFixtures.update(connection,
                        "insert into import_error (tenant_id, job_id, row_number, reason, created_by, updated_by) "
                                + "values (?, ?, 1, 'probe', ?, ?)",
                        tenant, job, ActorId.SYSTEM.value(), ActorId.SYSTEM.value());
            });

    /** A word of a record that search finds it by (Sprint 17). A probe row needs a record. */
    public static final TenantScopedTable SEARCH_TERM = new TenantScopedTable("search_term",
            (connection, tenant) -> {
                UUID record = probeRecord(connection, tenant);
                TenantFixtures.update(connection,
                        "insert into search_term (tenant_id, object_api_name, record_id, field_api_name, term, "
                                + "created_by, updated_by) values (?, 'Probe__c', ?, 'probe__c', 'probe', ?, ?)",
                        tenant, record, ActorId.SYSTEM.value(), ActorId.SYSTEM.value());
            });

    /** How far the words of a searchable field are filled in (Sprint 17). */
    public static final TenantScopedTable SEARCH_FIELD = new TenantScopedTable("search_field",
            (connection, tenant) -> TenantFixtures.update(connection,
                    "insert into search_field (tenant_id, object_api_name, field_api_name, created_by, updated_by) "
                            + "values (?, 'Probe__c', ?, ?, ?)",
                    tenant, "probe" + UUID.randomUUID().toString().replace("-", "").substring(0, 8) + "__c",
                    ActorId.SYSTEM.value(), ActorId.SYSTEM.value()));

    /** A saved list view of an object (Sprint 19, ADR-0130). */
    public static final TenantScopedTable LIST_VIEW = new TenantScopedTable("list_view",
            (connection, tenant) -> TenantFixtures.update(connection,
                    "insert into list_view (tenant_id, object_api_name, label, scope, definition, created_by, "
                            + "updated_by) values (?, 'Probe__c', ?, 'SHARED', cast('{}' as jsonb), ?, ?)",
                    tenant, probeName("V"), ActorId.SYSTEM.value(), ActorId.SYSTEM.value()));

    /** The workflows of organizations (Sprint 22). A probe row needs a custom object, which it creates. */
    public static final TenantScopedTable WORKFLOW = new TenantScopedTable("workflow",
            (connection, tenant) -> probeWorkflow(connection, tenant));

    /** One definition of a workflow (Sprint 22). A probe row needs a workflow, which it creates. */
    public static final TenantScopedTable WORKFLOW_VERSION = new TenantScopedTable("workflow_version",
            (connection, tenant) -> probeWorkflowVersion(connection, tenant, probeWorkflow(connection, tenant), 1));

    /** The executions of workflows (Sprint 22). A probe row needs a workflow and a version, which it creates. */
    public static final TenantScopedTable WORKFLOW_EXECUTION = new TenantScopedTable("workflow_execution",
            (connection, tenant) -> probeExecution(connection, tenant));

    /** The steps of executions (Sprint 22). A probe row needs an execution, which it creates. */
    public static final TenantScopedTable WORKFLOW_STEP = new TenantScopedTable("workflow_step",
            (connection, tenant) -> TenantFixtures.update(connection,
                    "insert into workflow_step (tenant_id, execution_id, position, name, action_type, created_by, "
                            + "updated_by) values (?, ?, 1, 'Probe', 'PUBLISH_EVENT', ?, ?)",
                    tenant, probeExecution(connection, tenant), ActorId.SYSTEM.value(), ActorId.SYSTEM.value()));

    /** The schedules of timed workflows (Sprint 22). A probe row needs a workflow, which it creates. */
    public static final TenantScopedTable WORKFLOW_SCHEDULE = new TenantScopedTable("workflow_schedule",
            (connection, tenant) -> {
                UUID workflow = probeWorkflow(connection, tenant);
                TenantFixtures.update(connection,
                        "insert into workflow_schedule (tenant_id, workflow_id, trigger_kind, definition_version, "
                                + "next_fire_at, created_by, updated_by) values (?, ?, 'EVERY_DAY', 1, now(), ?, ?)",
                        tenant, workflow, ActorId.SYSTEM.value(), ActorId.SYSTEM.value());
            });

    /** The approval processes of organizations (Sprint 24). A probe row needs a custom object, which it creates. */
    public static final TenantScopedTable APPROVAL_PROCESS = new TenantScopedTable("approval_process",
            (connection, tenant) -> probeApprovalProcess(connection, tenant));

    /** One definition of an approval process (Sprint 24). A probe row needs a process, which it creates. */
    public static final TenantScopedTable APPROVAL_PROCESS_VERSION = new TenantScopedTable("approval_process_version",
            (connection, tenant) -> probeApprovalVersion(connection, tenant, probeApprovalProcess(connection, tenant)));

    /** The requests made under approval processes (Sprint 24). A probe row needs a process and a version. */
    public static final TenantScopedTable APPROVAL_INSTANCE = new TenantScopedTable("approval_instance",
            (connection, tenant) -> probeApprovalInstance(connection, tenant));

    /** The steps of requests (Sprint 24). A probe row needs a request, which it creates. */
    public static final TenantScopedTable APPROVAL_STEP_INSTANCE = new TenantScopedTable("approval_step_instance",
            (connection, tenant) -> probeApprovalStep(connection, tenant, probeApprovalInstance(connection, tenant)));

    /** The tasks of requests (Sprint 24). A probe row needs a request and a step, which it creates. */
    public static final TenantScopedTable APPROVAL_ASSIGNMENT = new TenantScopedTable("approval_assignment",
            (connection, tenant) -> {
                UUID instance = probeApprovalInstance(connection, tenant);
                UUID step = probeApprovalStep(connection, tenant, instance);
                TenantFixtures.update(connection,
                        "insert into approval_assignment (tenant_id, instance_id, step_id, assignee_id, created_by, "
                                + "updated_by) values (?, ?, ?, ?, ?, ?)",
                        tenant, instance, step, UUID.randomUUID(), ActorId.SYSTEM.value(), ActorId.SYSTEM.value());
            });

    /** The append-only history of requests (Sprint 24). A probe row needs a request, which it creates. */
    public static final TenantScopedTable APPROVAL_HISTORY = new TenantScopedTable("approval_history",
            (connection, tenant) -> TenantFixtures.update(connection,
                    "insert into approval_history (tenant_id, instance_id, kind, process_version_number, created_by, "
                            + "updated_by) values (?, ?, 'SUBMITTED', 1, ?, ?)",
                    tenant, probeApprovalInstance(connection, tenant), ActorId.SYSTEM.value(),
                    ActorId.SYSTEM.value()));

    /** The delegations of approvals (Sprint 24). */
    public static final TenantScopedTable APPROVAL_DELEGATION = new TenantScopedTable("approval_delegation",
            (connection, tenant) -> TenantFixtures.update(connection,
                    "insert into approval_delegation (tenant_id, delegator_id, delegate_id, starts_on, ends_on, "
                            + "created_by, updated_by) values (?, ?, ?, current_date, current_date + 1, ?, ?)",
                    tenant, UUID.randomUUID(), UUID.randomUUID(), ActorId.SYSTEM.value(), ActorId.SYSTEM.value()));

    /** Every tenant-scoped table of the platform. Extend this list in the sprint that adds a table. */
    public static final List<TenantScopedTable> ALL = List.of(OUTBOX_EVENT, PROCESSED_EVENT, MEMBERSHIP, INVITATION,
            LICENCE_POOL, LICENCE_ASSIGNMENT, SUPPORT_ACCESS_GRANT, PROFILE, ACCESS_POLICY, SECURITY_ROLE,
            MEMBER_ACCESS, MEMBER_ACCESS_POLICY, MEMBER_GRANT, PUBLIC_GROUP, PUBLIC_GROUP_MEMBER,
            PUBLIC_GROUP_ACCESS_POLICY, OBJECT_PERMISSION, FIELD_PERMISSION, SECURITY_VERSION, OBJECT_DEFINITION,
            FIELD_DEFINITION, METADATA_VERSION, RECORD_TYPE, METADATA_CHANGE_SET, METADATA_CHANGE,
            METADATA_RELEASE, PAGE_LAYOUT, RECORD_PAGE, APPLICATION, TAB, CUSTOM_PAGE, CUSTOM_PAGE_ASSET,
            LAYOUT_ASSIGNMENT, VALIDATION_RULE,
            OBJECT_RECORD, AUTO_NUMBER_COUNTER, RECORD_TYPE_ASSIGNMENT, FIELD_SLOT, RECORD_LINK, LINK_INDEX,
            UNIQUE_ENFORCEMENT, FORMULA_RECALCULATION, OBJECT_SHARING_SETTING, SHARING_RULE, RECORD_SHARE,
            RECORD_FILE, FILE_OBJECT_CLEANUP, IMPORT_JOB, IMPORT_ERROR, SEARCH_TERM, SEARCH_FIELD, LIST_VIEW,
            RECORD_PAGE_ASSIGNMENT, WORKFLOW, WORKFLOW_VERSION, WORKFLOW_EXECUTION, WORKFLOW_STEP,
            WORKFLOW_SCHEDULE, APPROVAL_PROCESS, APPROVAL_PROCESS_VERSION, APPROVAL_INSTANCE,
            APPROVAL_STEP_INSTANCE, APPROVAL_ASSIGNMENT, APPROVAL_HISTORY, APPROVAL_DELEGATION);

    private TenantScopedTables() {
    }

    /** A workflow of the tenant for a probe row, on a custom object of its own; returns it. */
    private static UUID probeWorkflow(java.sql.Connection connection, UUID tenant) throws java.sql.SQLException {
        String object = probeObject(connection, tenant);
        UUID workflow = UUID.randomUUID();
        TenantFixtures.update(connection,
                "insert into workflow (id, tenant_id, object_api_name, api_name, label, created_by, updated_by) "
                        + "values (?, ?, ?, ?, 'Probe', ?, ?)",
                workflow, tenant, object, probeName("Probe"), ActorId.SYSTEM.value(), ActorId.SYSTEM.value());
        return workflow;
    }

    /** A definition of the workflow for a probe row; returns it. */
    private static UUID probeWorkflowVersion(java.sql.Connection connection, UUID tenant, UUID workflow, int number)
            throws java.sql.SQLException {
        UUID version = UUID.randomUUID();
        TenantFixtures.update(connection,
                "insert into workflow_version (id, tenant_id, workflow_id, version_number, trigger_kind, trigger, "
                        + "actions, created_by, updated_by) values (?, ?, ?, ?, 'RECORD_CREATED', "
                        + "cast('{\"kind\":\"RECORD_CREATED\"}' as jsonb), cast('[]' as jsonb), ?, ?)",
                version, tenant, workflow, number, ActorId.SYSTEM.value(), ActorId.SYSTEM.value());
        return version;
    }

    /** An execution of the tenant for a probe row, with its workflow and version; returns it. */
    private static UUID probeExecution(java.sql.Connection connection, UUID tenant) throws java.sql.SQLException {
        UUID workflow = probeWorkflow(connection, tenant);
        UUID version = probeWorkflowVersion(connection, tenant, workflow, 1);
        UUID execution = UUID.randomUUID();
        TenantFixtures.update(connection,
                "insert into workflow_execution (id, tenant_id, workflow_id, workflow_version_id, "
                        + "workflow_api_name, workflow_version_number, object_api_name, record_id, trigger_kind, "
                        + "event_id, idempotency_key, created_by, updated_by) values (?, ?, ?, ?, 'Probe__c', 1, "
                        + "'Probe__c', ?, 'RECORD_CREATED', ?, ?, ?, ?)",
                execution, tenant, workflow, version, UUID.randomUUID(), UUID.randomUUID(),
                "probe-" + execution, ActorId.SYSTEM.value(), ActorId.SYSTEM.value());
        return execution;
    }

    /** An approval process of the tenant for a probe row, on a custom object of its own; returns it. */
    private static UUID probeApprovalProcess(java.sql.Connection connection, UUID tenant)
            throws java.sql.SQLException {
        String object = probeObject(connection, tenant);
        UUID process = UUID.randomUUID();
        TenantFixtures.update(connection,
                "insert into approval_process (id, tenant_id, object_api_name, api_name, label, created_by, "
                        + "updated_by) values (?, ?, ?, ?, 'Probe', ?, ?)",
                process, tenant, object, probeName("Probe"), ActorId.SYSTEM.value(), ActorId.SYSTEM.value());
        return process;
    }

    /** A definition of the approval process for a probe row (one step, no outcome updates); returns it. */
    private static UUID probeApprovalVersion(java.sql.Connection connection, UUID tenant, UUID process)
            throws java.sql.SQLException {
        UUID version = UUID.randomUUID();
        TenantFixtures.update(connection,
                "insert into approval_process_version (id, tenant_id, process_id, version_number, steps, outcomes, "
                        + "created_by, updated_by) values (?, ?, ?, 1, "
                        + "cast('[{\"name\":\"Probe\",\"label\":\"Probe\",\"completion\":\"ANY\",\"approvers\":[]}]' "
                        + "as jsonb), cast('{}' as jsonb), ?, ?)",
                version, tenant, process, ActorId.SYSTEM.value(), ActorId.SYSTEM.value());
        return version;
    }

    /** A pending request of the tenant for a probe row, with its process and version; returns it. */
    private static UUID probeApprovalInstance(java.sql.Connection connection, UUID tenant)
            throws java.sql.SQLException {
        UUID process = probeApprovalProcess(connection, tenant);
        UUID version = probeApprovalVersion(connection, tenant, process);
        UUID instance = UUID.randomUUID();
        TenantFixtures.update(connection,
                "insert into approval_instance (id, tenant_id, process_id, process_version_id, process_api_name, "
                        + "process_label, process_version_number, object_api_name, record_id, record_version, "
                        + "status, current_step, created_by, updated_by) values (?, ?, ?, ?, 'Probe__c', 'Probe', 1, "
                        + "'Probe__c', ?, 1, 'PENDING', 1, ?, ?)",
                instance, tenant, process, version, UUID.randomUUID(), ActorId.SYSTEM.value(),
                ActorId.SYSTEM.value());
        return instance;
    }

    /** An active step of the request for a probe row; returns it. */
    private static UUID probeApprovalStep(java.sql.Connection connection, UUID tenant, UUID instance)
            throws java.sql.SQLException {
        UUID step = UUID.randomUUID();
        TenantFixtures.update(connection,
                "insert into approval_step_instance (id, tenant_id, instance_id, position, name, label, completion, "
                        + "status, activated_at, created_by, updated_by) values (?, ?, ?, 1, 'Probe', 'Probe', 'ANY', "
                        + "'ACTIVE', now(), ?, ?)",
                step, tenant, instance, ActorId.SYSTEM.value(), ActorId.SYSTEM.value());
        return step;
    }

    /** A custom object of the tenant for a probe row, with a name of its own; returns its API name. */
    private static String probeObject(java.sql.Connection connection, UUID tenant) throws java.sql.SQLException {
        String name = "P" + UUID.randomUUID().toString().replace("-", "").substring(0, 12) + "__c";
        TenantFixtures.update(connection,
                "insert into object_definition (tenant_id, api_name, label, plural_label, created_by, updated_by) "
                        + "values (?, ?, 'Probe', 'Probes', ?, ?)",
                tenant, name, ActorId.SYSTEM.value(), ActorId.SYSTEM.value());
        return name;
    }

    /** A name for a probe row that no other probe row has. */
    private static String probeName(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 12) + "__c";
    }

    /** An open change set of the tenant for a probe row, with a name of its own; returns it. */
    private static UUID probeChangeSet(java.sql.Connection connection, UUID tenant) throws java.sql.SQLException {
        UUID set = UUID.randomUUID();
        TenantFixtures.update(connection,
                "insert into metadata_change_set (id, tenant_id, name, created_by, updated_by) "
                        + "values (?, ?, ?, ?, ?)",
                set, tenant, "probe-" + set, ActorId.SYSTEM.value(), ActorId.SYSTEM.value());
        return set;
    }

    /** A user and an active membership of the tenant for a probe row; returns the membership. */
    private static UUID probeMembership(java.sql.Connection connection, UUID tenant) throws java.sql.SQLException {
        UUID user = UUID.randomUUID();
        UUID membership = UUID.randomUUID();
        TenantFixtures.update(connection,
                "insert into platform_user (id, email, display_name, created_by, updated_by) "
                        + "values (?, ?, 'Probe', ?, ?)",
                user, "probe-" + user + "@example.test", ActorId.SYSTEM.value(), ActorId.SYSTEM.value());
        TenantFixtures.update(connection,
                "insert into membership (id, tenant_id, user_id, created_by, updated_by) values (?, ?, ?, ?, ?)",
                membership, tenant, user, ActorId.SYSTEM.value(), ActorId.SYSTEM.value());
        return membership;
    }

    /** A public group of the tenant for a probe row; returns it. */
    private static UUID probeGroup(java.sql.Connection connection, UUID tenant) throws java.sql.SQLException {
        UUID group = UUID.randomUUID();
        TenantFixtures.update(connection,
                "insert into public_group (id, tenant_id, name, created_by, updated_by) values (?, ?, ?, ?, ?)",
                group, tenant, "probe-" + group, ActorId.SYSTEM.value(), ActorId.SYSTEM.value());
        return group;
    }

    /** A record of the tenant for a probe row; returns its identifier. */
    private static UUID probeRecord(java.sql.Connection connection, UUID tenant) throws java.sql.SQLException {
        UUID record = UUID.randomUUID();
        TenantFixtures.update(connection,
                "insert into object_record (id, tenant_id, object_api_name, owner_id, payload, created_by, "
                        + "updated_by) values (?, ?, 'Probe__c', ?, '{}'::jsonb, ?, ?)",
                record, tenant, ActorId.SYSTEM.value(), ActorId.SYSTEM.value(), ActorId.SYSTEM.value());
        return record;
    }

    /** A profile of the tenant for a probe row; returns it. */
    private static UUID probeProfile(java.sql.Connection connection, UUID tenant) throws java.sql.SQLException {
        UUID profile = UUID.randomUUID();
        TenantFixtures.update(connection,
                "insert into profile (id, tenant_id, name, licence_type_id, created_by, updated_by) "
                        + "values (?, ?, ?, ?, ?, ?)",
                profile, tenant, "probe-" + profile, probeLicenceType(connection), ActorId.SYSTEM.value(),
                ActorId.SYSTEM.value());
        return profile;
    }

    /** A licence type of its own in the catalogue (platform-level), so that probe pools never collide. */
    private static UUID probeLicenceType(java.sql.Connection connection) throws java.sql.SQLException {
        UUID id = UUID.randomUUID();
        String key = "p" + id.toString().replace("-", "").substring(0, 12);
        TenantFixtures.update(connection,
                "insert into licence_type (id, key, name, created_by, updated_by) values (?, ?, 'Probe', ?, ?)",
                id, key, ActorId.SYSTEM.value(), ActorId.SYSTEM.value());
        return id;
    }
}
