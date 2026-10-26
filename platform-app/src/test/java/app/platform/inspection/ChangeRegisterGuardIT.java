package app.platform.inspection;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.sharedkernel.ActorId;
import app.platform.sharedkernel.TenantId;
import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestDatabase;
import app.platform.testsupport.tenancy.TenantFixtures;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * What the database itself holds for the register form of a change request (V080, ADR-0202), whatever the application
 * does: a row is written only by the cluster's own acts (a transaction setting that only the store raises); what the
 * person wrote when they raised the request and the facts the platform wrote then never change; the rest is completed
 * only while the request is open; the new revision and the day it was published are written once, by the publish alone;
 * nothing is deleted or set aside; and a request has one register. Each rule is tried with direct statements as the
 * application role and as the owner of the database, because a revoked grant would not hold.
 */
@PlatformIntegrationTest
class ChangeRegisterGuardIT {

    enum Role { APP, OWNER }

    private static final UUID ACTOR = ActorId.SYSTEM.value();
    private static final String CHECK_VIOLATION = "23514";
    private static final String WRITE = "with allowed as (select set_config('platform.change_register_write', 'on', "
            + "true)) ";
    private static final String BOTH = "with allowed as (select set_config('platform.change_register_write', 'on', "
            + "true), set_config('platform.change_register_result', 'on', true)) ";

    private static Throwable attempt(Role role, TenantId tenant, String sql, Object... parameters) {
        try {
            run(role, tenant, sql, parameters);
            return null;
        } catch (SQLException e) {
            return e;
        }
    }

    private static int run(Role role, TenantId tenant, String sql, Object... parameters) throws SQLException {
        if (role == Role.APP) {
            return TenantFixtures.asTenant(tenant, connection -> TenantFixtures.update(connection, sql, parameters));
        }
        try (Connection connection = TestDatabase.ownerConnection()) {
            connection.setAutoCommit(false);
            try {
                TenantFixtures.setTenant(connection, tenant);
                int changed = TenantFixtures.update(connection, sql, parameters);
                connection.commit();
                return changed;
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            }
        }
    }

    private static String state(Throwable failure) {
        return failure instanceof SQLException sql ? sql.getSQLState() : null;
    }

    private static UUID request(TenantId tenant, UUID template) throws SQLException {
        UUID id = UUID.randomUUID();
        run(Role.APP, tenant, "insert into inspection_change_request (id, tenant_id, template_id, version_no, title, "
                + "created_by, updated_by) values (?, ?, ?, 1, 'Probe', ?, ?)", id, tenant.value(), template, ACTOR,
                ACTOR);
        return id;
    }

    private static void register(TenantId tenant, UUID request, UUID template) throws SQLException {
        run(Role.APP, tenant, WRITE + "insert into inspection_change_register (tenant_id, request_id, template_id, "
                + "request_no, change_requested, document_name, rev_no, created_by, updated_by) select ?, ?, ?, "
                + "'DCN-1', 'Change', 'Document', '00', ?, ? from allowed", tenant.value(), request, template, ACTOR,
                ACTOR);
    }

    private static String valueOf(TenantId tenant, UUID request, String column) throws SQLException {
        return TenantFixtures.asTenant(tenant, connection -> {
            try (var statement = connection.prepareStatement("select " + column + "::text from "
                    + "inspection_change_register where request_id = ?")) {
                statement.setObject(1, request);
                try (var rows = statement.executeQuery()) {
                    rows.next();
                    return rows.getString(1);
                }
            }
        });
    }

    private static String update(String set) {
        return WRITE + "update inspection_change_register set " + set + ", version = version + 1, updated_by = ? "
                + "from allowed where request_id = ?";
    }

    @Test
    void aRowIsWrittenOnlyByTheClustersOwnActs() throws SQLException {
        TenantId tenant = TenantFixtures.createActiveTenant().id();
        UUID request = request(tenant, UUID.randomUUID());
        for (Role role : Role.values()) {
            assertThat(state(attempt(role, tenant, "insert into inspection_change_register (tenant_id, request_id, "
                    + "template_id, request_no, change_requested, document_name, rev_no, created_by, updated_by) "
                    + "values (?, ?, ?, 'DCN-1', 'Change', 'Document', '00', ?, ?)", tenant.value(), request,
                    UUID.randomUUID(), ACTOR, ACTOR))).as(role + " insert without the window")
                    .isEqualTo(CHECK_VIOLATION);
        }
        register(tenant, request, UUID.randomUUID());
        for (Role role : Role.values()) {
            assertThat(state(attempt(role, tenant, "update inspection_change_register set department = 'X', "
                    + "version = version + 1, updated_by = ? where request_id = ?", ACTOR, request)))
                    .as(role + " update without the window").isEqualTo(CHECK_VIOLATION);
        }
        assertThat(valueOf(tenant, request, "department")).isNull();
    }

    @Test
    void whatWasWrittenWhenTheRequestWasRaisedNeverChanges() throws SQLException {
        TenantId tenant = TenantFixtures.createActiveTenant().id();
        UUID request = request(tenant, UUID.randomUUID());
        register(tenant, request, UUID.randomUUID());

        for (Role role : Role.values()) {
            for (String change : new String[] {"request_no = 'DCN-2'", "change_requested = 'Other'",
                "basis_for_change = 'Other'", "horizontal_deployment = 'Other'", "document_name = 'Other'",
                "document_no = 'Other'", "rev_no = '09'", "requested_by_name = 'Other'",
                "requested_at = now() + interval '1 day'", "template_id = gen_random_uuid()",
                "request_id = gen_random_uuid()"}) {
                assertThat(state(attempt(role, tenant, update(change), ACTOR, request))).as(role + " " + change)
                        .isEqualTo(CHECK_VIOLATION);
            }
        }
        assertThat(valueOf(tenant, request, "request_no")).isEqualTo("DCN-1");
    }

    @Test
    void theBottomIsCompletedInPlaceOnlyWhileTheRequestIsOpen() throws SQLException {
        TenantId tenant = TenantFixtures.createActiveTenant().id();
        UUID request = request(tenant, UUID.randomUUID());
        register(tenant, request, UUID.randomUUID());

        for (Role role : Role.values()) {
            run(role, tenant, update("department = 'Plant 1', approved_by = 'Someone', server_updated = 'YES'"), ACTOR,
                    request);
        }
        assertThat(valueOf(tenant, request, "department")).isEqualTo("Plant 1");
        assertThat(valueOf(tenant, request, "version")).isEqualTo("2");

        run(Role.APP, tenant, "update inspection_change_request set status = 'DONE', resulting_version = 2, "
                + "decided_at = now(), decided_by = ?, version = version + 1, updated_by = ? where id = ?", ACTOR,
                ACTOR, request);
        for (Role role : Role.values()) {
            assertThat(state(attempt(role, tenant, update("department = 'Changed'"), ACTOR, request)))
                    .as(role + " completes a published request").isEqualTo(CHECK_VIOLATION);
        }
        assertThat(valueOf(tenant, request, "department")).isEqualTo("Plant 1");
    }

    @Test
    void theNewRevisionAndItsDayAreWrittenOnceByThePublishAlone() throws SQLException {
        TenantId tenant = TenantFixtures.createActiveTenant().id();
        UUID request = request(tenant, UUID.randomUUID());
        register(tenant, request, UUID.randomUUID());

        for (Role role : Role.values()) {
            assertThat(state(attempt(role, tenant, update("new_rev_no = '01'"), ACTOR, request)))
                    .as(role + " writes the new revision as an approver").isEqualTo(CHECK_VIOLATION);
            assertThat(state(attempt(role, tenant, update("new_revision_at = now()"), ACTOR, request)))
                    .as(role + " writes the day as an approver").isEqualTo(CHECK_VIOLATION);
        }
        run(Role.APP, tenant, BOTH + "update inspection_change_register set new_rev_no = '01', new_revision_at = "
                + "now(), version = version + 1, updated_by = ? from allowed where request_id = ?", ACTOR, request);
        assertThat(valueOf(tenant, request, "new_rev_no")).isEqualTo("01");
        for (Role role : Role.values()) {
            assertThat(state(attempt(role, tenant, BOTH + "update inspection_change_register set new_rev_no = '02', "
                    + "version = version + 1, updated_by = ? from allowed where request_id = ?", ACTOR, request)))
                    .as(role + " writes the new revision twice").isEqualTo(CHECK_VIOLATION);
        }
        assertThat(valueOf(tenant, request, "new_rev_no")).isEqualTo("01");
    }

    @Test
    void theNewRevisionCannotBeWrittenWhenTheRegisterIsMade() throws SQLException {
        TenantId tenant = TenantFixtures.createActiveTenant().id();
        UUID request = request(tenant, UUID.randomUUID());

        assertThat(state(attempt(Role.APP, tenant, BOTH + "insert into inspection_change_register (tenant_id, "
                + "request_id, template_id, request_no, change_requested, document_name, rev_no, new_rev_no, "
                + "created_by, updated_by) select ?, ?, ?, 'DCN-1', 'Change', 'Document', '00', '01', ?, ? from "
                + "allowed", tenant.value(), request, UUID.randomUUID(), ACTOR, ACTOR)))
                .isEqualTo(CHECK_VIOLATION);
    }

    @Test
    void nothingIsDeletedNorSetAsideAndARequestHasOneRegister() throws SQLException {
        TenantId tenant = TenantFixtures.createActiveTenant().id();
        UUID request = request(tenant, UUID.randomUUID());
        register(tenant, request, UUID.randomUUID());

        for (Role role : Role.values()) {
            assertThat(state(attempt(role, tenant, "delete from inspection_change_register where request_id = ?",
                    request))).as(role + " delete").isEqualTo(CHECK_VIOLATION);
            assertThat(state(attempt(role, tenant, update("deleted_at = now(), deleted_by = ?"), ACTOR, ACTOR,
                    request))).as(role + " soft delete").isEqualTo(CHECK_VIOLATION);
        }
        assertThat(state(attempt(Role.APP, tenant, WRITE + "insert into inspection_change_register (tenant_id, "
                + "request_id, template_id, request_no, change_requested, document_name, rev_no, created_by, "
                + "updated_by) select ?, ?, ?, 'DCN-2', 'Again', 'Document', '00', ?, ? from allowed",
                tenant.value(), request, UUID.randomUUID(), ACTOR, ACTOR))).as("a second register")
                .isEqualTo("23505");
        assertThat(valueOf(tenant, request, "request_no")).isEqualTo("DCN-1");
    }

    @Test
    void theBoundsOfTheBoxesHoldAtTheDatabase() throws SQLException {
        TenantId tenant = TenantFixtures.createActiveTenant().id();
        UUID request = request(tenant, UUID.randomUUID());
        register(tenant, request, UUID.randomUUID());

        assertThat(state(attempt(Role.APP, tenant, update("server_updated = 'MAYBE'"), ACTOR, request)))
                .as("an answer is Yes or No").isEqualTo(CHECK_VIOLATION);
        assertThat(state(attempt(Role.APP, tenant, update("department = repeat('x', 1001)"), ACTOR, request)))
                .as("a department over its bound").isEqualTo(CHECK_VIOLATION);
        assertThat(state(attempt(Role.OWNER, tenant, update("signature_note = repeat('x', 501)"), ACTOR, request)))
                .as("a signature over its bound").isEqualTo(CHECK_VIOLATION);
    }
}
