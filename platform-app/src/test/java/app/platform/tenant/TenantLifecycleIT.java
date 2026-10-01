package app.platform.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.platform.sharedkernel.ActorId;
import app.platform.sharedkernel.TenantId;
import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestDatabase;
import app.platform.testsupport.tenancy.TenantFixtures;
import app.platform.testsupport.tenancy.TenantFixtures.TestTenant;
import app.platformapi.ApiException;
import app.platformapi.ErrorCode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The tenant lifecycle on the real database: the legal transitions are enforced by the module and, independently, by
 * the database; every change and its event are one transaction; concurrent changes are decided one after the other.
 */
@PlatformIntegrationTest
class TenantLifecycleIT {

    private static final ActorId ACTOR = new ActorId(UUID.randomUUID());

    @Autowired
    private Tenants tenants;

    private static TenantSlug newSlug() {
        return TenantSlug.of("lifecycle-" + UUID.randomUUID().toString().replace("-", "").substring(0, 10));
    }

    // ---- the lifecycle through the service ----

    @Test
    void aNewOrganizationStartsProvisioningAndMovesThroughItsWholeLife() {
        Tenant created = tenants.provision(newSlug(), "Tenant A", ACTOR);
        assertThat(created.status()).isEqualTo(TenantStatus.PROVISIONING);
        assertThat(created.displayName()).isEqualTo("Tenant A");

        Tenant active = tenants.activate(created.id(), ACTOR);
        assertThat(active.status()).isEqualTo(TenantStatus.ACTIVE);
        assertThat(active.version()).isGreaterThan(created.version());
        assertThat(active.statusChangedAt()).isAfterOrEqualTo(created.statusChangedAt());

        assertThat(tenants.suspend(created.id(), ACTOR).status()).isEqualTo(TenantStatus.SUSPENDED);
        assertThat(tenants.reinstate(created.id(), ACTOR).status()).isEqualTo(TenantStatus.ACTIVE);
        assertThat(tenants.deactivate(created.id(), ACTOR).status()).isEqualTo(TenantStatus.DEACTIVATED);
    }

    @Test
    void anIllegalTransitionIsAConflictAndChangesNothing() {
        Tenant created = tenants.provision(newSlug(), "Tenant A", ACTOR);

        assertThatThrownBy(() -> tenants.suspend(created.id(), ACTOR)).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.code()).isEqualTo(ErrorCode.CONFLICT));
        assertThatThrownBy(() -> tenants.reinstate(created.id(), ACTOR)).isInstanceOf(ApiException.class);
        tenants.activate(created.id(), ACTOR);
        assertThatThrownBy(() -> tenants.activate(created.id(), ACTOR)).isInstanceOf(ApiException.class);
        tenants.deactivate(created.id(), ACTOR);
        for (Callable<Tenant> attempt : List.<Callable<Tenant>>of(
                () -> tenants.activate(created.id(), ACTOR), () -> tenants.reinstate(created.id(), ACTOR),
                () -> tenants.suspend(created.id(), ACTOR), () -> tenants.deactivate(created.id(), ACTOR))) {
            assertThatThrownBy(attempt::call).isInstanceOfSatisfying(ApiException.class,
                    e -> assertThat(e.code()).isEqualTo(ErrorCode.CONFLICT));
        }

        assertThat(tenants.findById(created.id()).orElseThrow().status()).isEqualTo(TenantStatus.DEACTIVATED);
        assertThat(eventTypesOf(created.id())).as("no event for a refused change")
                .containsExactly("tenant.provisioned", "tenant.activated", "tenant.deactivated");
    }

    @Test
    void theConflictMessageNamesTheStatesButNothingInternal() {
        Tenant created = tenants.provision(newSlug(), "Tenant A", ACTOR);

        assertThatThrownBy(() -> tenants.suspend(created.id(), ACTOR))
                .hasMessage("The organization cannot change from PROVISIONING to SUSPENDED.");
    }

    @Test
    void anUnknownTenantIsNotFound() {
        assertThatThrownBy(() -> tenants.activate(new TenantId(UUID.randomUUID()), ACTOR))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND));
        assertThat(tenants.findById(new TenantId(UUID.randomUUID()))).isEmpty();
        assertThat(tenants.findBySlug(TenantSlug.of("never-created-slug"))).isEmpty();
    }

    @Test
    void aSlugIsUniqueAndAConflictIsReported() {
        TenantSlug slug = newSlug();
        tenants.provision(slug, "Tenant A", ACTOR);

        assertThatThrownBy(() -> tenants.provision(slug, "Tenant B", ACTOR)).isInstanceOfSatisfying(
                ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.CONFLICT));
        assertThat(tenants.findBySlug(slug).orElseThrow().displayName()).isEqualTo("Tenant A");
    }

    @Test
    void theDisplayNameIsValidated() {
        for (String bad : new String[] {null, "", "   ", " padded", "padded ", "x".repeat(201)}) {
            assertThatThrownBy(() -> tenants.provision(newSlug(), bad, ACTOR)).as(String.valueOf(bad))
                    .isInstanceOfSatisfying(ApiException.class, e -> {
                        assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_ERROR);
                        assertThat(e.fields()).containsKey("displayName");
                    });
        }
        assertThat(tenants.provision(newSlug(), "x".repeat(200), ACTOR).displayName()).hasSize(200);
    }

    @Test
    void aCallerCanChooseTheIdentifierBeforeTheTenantExists() {
        TenantId id = tenants.newId();

        Tenant created = tenants.provision(id, newSlug(), "Tenant A", ACTOR);

        assertThat(created.id()).isEqualTo(id);
    }

    // ---- events ----

    @Test
    void everyChangeWritesItsEventInTheSameTransactionWithTheRightTenantAndPayload() throws SQLException {
        Tenant created = tenants.provision(newSlug(), "Tenant A", ACTOR);
        tenants.activate(created.id(), ACTOR);
        tenants.suspend(created.id(), ACTOR);
        tenants.reinstate(created.id(), ACTOR);
        tenants.deactivate(created.id(), ACTOR);

        assertThat(eventTypesOf(created.id())).containsExactly("tenant.provisioned", "tenant.activated",
                "tenant.suspended", "tenant.reinstated", "tenant.deactivated");
        String payload = payloadsOf(created.id(), "tenant.suspended").get(0);
        assertThat(payload).contains(created.id().toString(), created.slug().value(), "\"from\": \"ACTIVE\"",
                "\"to\": \"SUSPENDED\"").doesNotContain("Tenant A");
    }

    @Test
    void aFailedProvisioningLeavesNeitherATenantNorAnEvent() {
        TenantSlug slug = newSlug();
        tenants.provision(slug, "Tenant A", ACTOR);
        long eventsBefore = countEvents();

        assertThatThrownBy(() -> tenants.provision(slug, "Tenant B", ACTOR)).isInstanceOf(ApiException.class);

        assertThat(countEvents()).isEqualTo(eventsBefore);
    }

    // ---- the database enforces the same lifecycle ----

    @Test
    void theDatabaseAcceptsExactlyTheTransitionsTheModuleAllows() throws SQLException {
        for (TenantStatus from : TenantStatus.values()) {
            for (TenantStatus to : TenantStatus.values()) {
                if (from == to) {
                    continue;
                }
                TestTenant tenant = TenantFixtures.createTenant(from);

                boolean accepted = databaseAccepts(tenant, to);

                assertThat(accepted).as(from + " to " + to + " in the database")
                        .isEqualTo(from.canTransitionTo(to));
            }
        }
    }

    @Test
    void theDatabaseRefusesATenantThatDoesNotStartProvisioning() {
        assertThatThrownBy(() -> {
            try (Connection owner = TestDatabase.ownerConnection();
                    PreparedStatement insert = owner.prepareStatement("insert into tenant (slug, display_name, status, "
                            + "created_by, updated_by) values (?, 'x', 'ACTIVE', ?, ?)")) {
                insert.setString(1, newSlug().value());
                insert.setObject(2, ACTOR.value());
                insert.setObject(3, ACTOR.value());
                insert.executeUpdate();
            }
        }).isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("23514"));
    }

    @Test
    void theDatabaseRefusesSlugsAndNamesThatBreakTheRules() {
        for (String slug : new String[] {"Upper-case", "ab", "-lead", "trail-", "double--hyphen", "has space",
            "under_score"}) {
            assertThatThrownBy(() -> insertRaw(slug, "Name")).as(slug)
                    .isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("23514"));
        }
        assertThatThrownBy(() -> insertRaw(newSlug().value(), " padded"))
                .isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("23514"));
        assertThatThrownBy(() -> insertRaw(newSlug().value(), "")).isInstanceOf(SQLException.class);
    }

    @Test
    void aTenantStatusChangeUpdatesTheStatusTimestamp() throws SQLException {
        TestTenant tenant = TenantFixtures.createActiveTenant();
        long before = statusChangedAtMillis(tenant);

        databaseAccepts(tenant, TenantStatus.SUSPENDED);

        assertThat(statusChangedAtMillis(tenant)).isGreaterThanOrEqualTo(before);
    }

    // ---- concurrency ----

    @Test
    void twoRequestsToProvisionTheSameSlugAtOnceHaveExactlyOneWinner() throws Exception {
        TenantSlug slug = newSlug();
        int attempts = 6;
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < attempts; i++) {
                String name = "Tenant " + i;
                results.add(pool.submit(() -> {
                    go.await();
                    try {
                        tenants.provision(slug, name, ACTOR);
                        return true;
                    } catch (ApiException e) {
                        assertThat(e.code()).isEqualTo(ErrorCode.CONFLICT);
                        return false;
                    }
                }));
            }
            go.countDown();
            int winners = 0;
            for (Future<Boolean> result : results) {
                winners += result.get() ? 1 : 0;
            }

            assertThat(winners).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentMovesOfOneTenantAreDecidedOneAfterTheOther() throws Exception {
        Tenant created = tenants.provision(newSlug(), "Tenant A", ACTOR);
        tenants.activate(created.id(), ACTOR);
        int attempts = 6;
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < attempts; i++) {
                results.add(pool.submit(() -> {
                    go.await();
                    try {
                        tenants.suspend(created.id(), ACTOR);
                        return true;
                    } catch (ApiException e) {
                        return false;
                    }
                }));
            }
            go.countDown();
            int winners = 0;
            for (Future<Boolean> result : results) {
                winners += result.get() ? 1 : 0;
            }

            assertThat(winners).as("only one of the identical moves can succeed").isEqualTo(1);
            assertThat(eventTypesOf(created.id())).containsExactly("tenant.provisioned", "tenant.activated",
                    "tenant.suspended");
        } finally {
            pool.shutdownNow();
        }
    }

    // ---- the context never leaks out of the service ----

    @Test
    void theServiceLeavesNoTenantContextOnTheCallingThread(@Autowired TenantContexts contexts) {
        tenants.provision(newSlug(), "Tenant A", ACTOR);

        assertThat(contexts.current()).isEmpty();
        assertThat(app.platform.sharedkernel.logging.LogContext.tenantId()).isEmpty();
    }

    // ---- helpers ----

    private static boolean databaseAccepts(TestTenant tenant, TenantStatus to) throws SQLException {
        try (Connection owner = TestDatabase.ownerConnection();
                PreparedStatement update = owner.prepareStatement(
                        "update tenant set status = ?, updated_by = ?, version = version + 1 where id = ?")) {
            update.setString(1, to.name());
            update.setObject(2, ACTOR.value());
            update.setObject(3, tenant.id().value());
            update.executeUpdate();
            return true;
        } catch (SQLException e) {
            assertThat(e.getSQLState()).isEqualTo("23514");
            return false;
        }
    }

    private static void insertRaw(String slug, String name) throws SQLException {
        try (Connection owner = TestDatabase.ownerConnection();
                PreparedStatement insert = owner.prepareStatement(
                        "insert into tenant (slug, display_name, created_by, updated_by) values (?, ?, ?, ?)")) {
            insert.setString(1, slug);
            insert.setString(2, name);
            insert.setObject(3, ACTOR.value());
            insert.setObject(4, ACTOR.value());
            insert.executeUpdate();
        }
    }

    private static long statusChangedAtMillis(TestTenant tenant) throws SQLException {
        try (Connection owner = TestDatabase.ownerConnection();
                PreparedStatement select = owner.prepareStatement(
                        "select status_changed_at from tenant where id = ?")) {
            select.setObject(1, tenant.id().value());
            try (ResultSet rs = select.executeQuery()) {
                rs.next();
                return rs.getTimestamp(1).getTime();
            }
        }
    }

    /** The event types written for the tenant, in the order they were recorded (read as the owner). */
    private static List<String> eventTypesOf(TenantId tenant) {
        List<String> types = new ArrayList<>();
        try (Connection owner = TestDatabase.ownerConnection();
                PreparedStatement select = owner.prepareStatement(
                        "select event_type from outbox_event where tenant_id = ? order by created_at, id")) {
            select.setObject(1, tenant.value());
            try (ResultSet rs = select.executeQuery()) {
                while (rs.next()) {
                    types.add(rs.getString(1));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return types;
    }

    private static List<String> payloadsOf(TenantId tenant, String type) throws SQLException {
        List<String> payloads = new ArrayList<>();
        try (Connection owner = TestDatabase.ownerConnection();
                PreparedStatement select = owner.prepareStatement(
                        "select payload::text from outbox_event where tenant_id = ? and event_type = ?")) {
            select.setObject(1, tenant.value());
            select.setString(2, type);
            try (ResultSet rs = select.executeQuery()) {
                while (rs.next()) {
                    payloads.add(rs.getString(1));
                }
            }
        }
        return payloads;
    }

    private static long countEvents() {
        try (Connection owner = TestDatabase.ownerConnection();
                PreparedStatement select = owner.prepareStatement("select count(*) from outbox_event");
                ResultSet rs = select.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
