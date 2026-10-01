package app.platform.outbox.internal;

import app.platform.observability.ErrorReport;
import app.platform.observability.ErrorReporter;
import app.platform.sharedkernel.events.EventEnvelope;
import app.platform.sharedkernel.events.EventHandler;
import app.platform.sharedkernel.events.EventPublisher;
import app.platform.sharedkernel.events.NewEvent;
import app.platform.testsupport.TestDatabase;
import app.platform.testsupport.tenancy.TenantFixtures.TestTenant;
import app.platform.tenant.TenantContext;
import app.platform.tenant.TenantContexts;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Shared helpers of the outbox integration tests: a recording handler, publishing, and a view of the outbox table. */
final class OutboxTestSupport {

    /** The table the probe handlers write their effects to (one tenant-scoped probe table, created by the tests). */
    static final String EFFECTS_TABLE = "outbox_probe_effects";

    private OutboxTestSupport() {
    }

    /** What a handler saw when it ran. */
    record Seen(EventEnvelope event, Optional<TenantContext> context, String databaseTenant,
            List<UUID> tenantsVisibleInOutbox) {
    }

    /** A handler that records everything it sees, writes one effect row per event and can be told to fail. */
    static final class ProbeHandler implements EventHandler {

        final List<Seen> seen = new CopyOnWriteArrayList<>();
        volatile Consumer<EventEnvelope> beforeEffect = event -> { };
        volatile Consumer<EventEnvelope> afterEffect = event -> { };

        private final String name;
        private final Set<String> types;
        private final TenantContexts contexts;
        private final JdbcClient jdbc;

        ProbeHandler(String name, Set<String> types, TenantContexts contexts, JdbcClient jdbc) {
            this.name = name;
            this.types = types;
            this.contexts = contexts;
            this.jdbc = jdbc;
        }

        @Override
        public String consumerName() {
            return name;
        }

        @Override
        public Set<String> eventTypes() {
            return types;
        }

        @Override
        public void handle(EventEnvelope event) {
            beforeEffect.accept(event);
            String databaseTenant = jdbc.sql("select coalesce(current_setting('app.current_tenant', true), '')")
                    .query(String.class).single();
            List<UUID> visible = jdbc.sql("select distinct tenant_id from outbox_event")
                    .query(UUID.class).list();
            jdbc.sql("insert into " + EFFECTS_TABLE + " (note, created_by, updated_by) values (:note, :actor, :actor)")
                    .param("note", name + ":" + event.eventId())
                    .param("actor", new UUID(0L, 0L))
                    .update();
            afterEffect.accept(event);
            // Recorded only when the handler completed: a failed attempt leaves no trace (it is rolled back).
            seen.add(new Seen(event, contexts.current(), databaseTenant, visible));
        }

        long invocations() {
            return seen.size();
        }

        long invocationsFor(UUID eventId) {
            return seen.stream().filter(s -> s.event().eventId().equals(eventId)).count();
        }

        void reset() {
            seen.clear();
            beforeEffect = event -> { };
            afterEffect = event -> { };
        }
    }

    /** Collects what the error tracking hook is told. */
    static final class RecordingReporter implements ErrorReporter {

        final List<ErrorReport> reports = new CopyOnWriteArrayList<>();

        @Override
        public void report(ErrorReport report) {
            reports.add(report);
        }
    }

    /** Publishes an event in its own transaction under the tenant's context. */
    static void publish(TenantContexts contexts, PlatformTransactionManager transactions, EventPublisher publisher,
            TestTenant tenant, TenantContext context, String type, String marker) {
        contexts.run(context, () -> new TransactionTemplate(transactions).executeWithoutResult(
                status -> publisher.publish(new NewEvent(type, "{\"marker\":\"" + marker + "\"}"))));
    }

    /** The context of a tenant without a person. */
    static TenantContext of(TestTenant tenant) {
        return TenantContext.of(tenant.id());
    }

    /** One outbox row as the owner sees it (the owner here is a superuser, which reads past row level security). */
    record Row(UUID id, UUID tenantId, UUID userId, UUID membershipId, String status, int attempts, String errorType,
            boolean leased, boolean dueNow, Instant deliveredAt, long version) {
    }

    static Optional<Row> row(String marker) {
        try (Connection owner = TestDatabase.ownerConnection();
                PreparedStatement select = owner.prepareStatement("select id, tenant_id, user_id, membership_id, "
                        + "status, attempts, last_error_type, locked_until is not null and locked_until > now(), "
                        + "next_attempt_at <= now(), delivered_at, version from outbox_event "
                        + "where payload->>'marker' = ?")) {
            select.setString(1, marker);
            try (ResultSet rs = select.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new Row(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getObject(3, UUID.class), rs.getObject(4, UUID.class), rs.getString(5), rs.getInt(6),
                        rs.getString(7), rs.getBoolean(8), rs.getBoolean(9),
                        rs.getTimestamp(10) == null ? null : rs.getTimestamp(10).toInstant(), rs.getLong(11)));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Changes data the row guard protects (creation time), as the superuser owner with triggers switched off. */
    static void ownerWithoutTriggers(String sql, Object... parameters) {
        try (Connection owner = TestDatabase.ownerConnection()) {
            try (java.sql.Statement statement = owner.createStatement()) {
                statement.execute("set session_replication_role = replica");
            }
            try (PreparedStatement update = owner.prepareStatement(sql)) {
                for (int i = 0; i < parameters.length; i++) {
                    update.setObject(i + 1, parameters[i]);
                }
                update.executeUpdate();
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Marks every pending event delivered, so one test never handles another test's leftovers. */
    static void settleEverythingPending() {
        owner("update outbox_event set status = 'DELIVERED', delivered_at = now(), locked_until = null, "
                + "version = version + 1, updated_by = ? where status = 'PENDING'", new UUID(0L, 0L));
    }

    /** Makes the event due now, as if its retry delay had passed. */
    static void makeDue(UUID eventId) {
        owner("update outbox_event set next_attempt_at = now() - interval '1 second', locked_until = null, "
                + "version = version + 1, updated_by = ? where id = ?", new UUID(0L, 0L), eventId);
    }

    /** Makes the lease of a claimed event expire, as if the claiming instance had died. */
    static void expireLease(UUID eventId) {
        owner("update outbox_event set locked_until = now() - interval '1 second', version = version + 1, "
                + "updated_by = ? where id = ?", new UUID(0L, 0L), eventId);
    }

    static long effects(String consumer, UUID eventId) {
        return count("select count(*) from " + EFFECTS_TABLE + " where note = ?", consumer + ":" + eventId);
    }

    static long count(String sql, Object... parameters) {
        try (Connection owner = TestDatabase.ownerConnection();
                PreparedStatement select = owner.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                select.setObject(i + 1, parameters[i]);
            }
            try (ResultSet rs = select.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    static void owner(String sql, Object... parameters) {
        try (Connection owner = TestDatabase.ownerConnection();
                PreparedStatement statement = owner.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                statement.setObject(i + 1, parameters[i]);
            }
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
