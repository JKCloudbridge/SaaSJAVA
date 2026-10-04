package app.platform.audit.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.platform.sharedkernel.ActorId;
import app.platform.sharedkernel.TenantId;
import app.platform.sharedkernel.audit.AuditOutcome;
import app.platform.sharedkernel.audit.AuditRecord;
import app.platform.sharedkernel.audit.AuditRecorder;
import app.platform.sharedkernel.audit.AuditSource;
import app.platform.testsupport.IdentityDb;
import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestDatabase;
import app.platform.testsupport.tenancy.TenantFixtures;
import app.platform.testsupport.tenancy.TenantFixtures.TestTenant;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The audit storage on a real PostgreSQL, as the roles that matter (ADR-0054, ADR-0055): records cannot be changed or
 * deleted by the application role or by anybody who logs in as the owner; the one way to delete is the purge function,
 * which refuses a young cut-off and writes a record of its own; the read policy shows an organization its own rows and
 * the platform its own; and the v1 fields are stored.
 */
@PlatformIntegrationTest
class AuditStorageIT {

    @Autowired
    private AuditRecorder recorder;

    @Autowired
    private JdbcClient jdbc;

    /** Inserts a record with a chosen time and audience, as the owner (which is how a test makes an old one). */
    private static UUID insert(String type, UUID tenant, Instant when, boolean organization, boolean platform)
            throws SQLException {
        UUID id = UUID.randomUUID();
        try (Connection owner = TestDatabase.ownerConnection();
                PreparedStatement insert = owner.prepareStatement(
                        "insert into audit_record (id, event_type, outcome, context_tenant_id, occurred_at, "
                                + "audience_organization, audience_platform, created_by, updated_by) "
                                + "values (?, ?, 'SUCCESS', ?, ?, ?, ?, ?, ?)")) {
            insert.setObject(1, id);
            insert.setString(2, type);
            insert.setObject(3, tenant);
            insert.setTimestamp(4, Timestamp.from(when));
            insert.setBoolean(5, organization);
            insert.setBoolean(6, platform);
            insert.setObject(7, ActorId.SYSTEM.value());
            insert.setObject(8, ActorId.SYSTEM.value());
            insert.executeUpdate();
        }
        return id;
    }

    private static long countByType(Connection connection, String type) throws SQLException {
        return TenantFixtures.count(connection, "select count(*) from audit_record where event_type = ?", type);
    }

    // ---- append-only ----

    @Test
    void theApplicationRoleCannotChangeOrDeleteAnAuditRecordAndNeitherCanTheOwner() throws SQLException {
        UUID id = insert("probe.append.only", null, Instant.now(), false, true);

        assertThatThrownBy(() -> TenantFixtures.asNobody(connection ->
                TenantFixtures.update(connection, "update audit_record set outcome = 'FAILURE' where id = ?", id)))
                .as("update as the application role").hasMessageContaining("append-only");
        assertThatThrownBy(() -> TenantFixtures.asNobody(connection ->
                TenantFixtures.update(connection, "delete from audit_record where id = ?", id)))
                .as("delete as the application role").hasMessageContaining("append-only");
        assertThatThrownBy(() -> IdentityDb.execute("delete from audit_record where id = ?", id))
                .as("delete as the owner").hasMessageContaining("append-only");
        assertThatThrownBy(() -> IdentityDb.execute("update audit_record set outcome = 'FAILURE' where id = ?", id))
                .as("update as the owner").hasMessageContaining("append-only");
        assertThat(IdentityDb.value(Long.class, "select count(*) from audit_record where id = ?", id)).isEqualTo(1);
    }

    // ---- the one way to delete ----

    @Test
    void thePurgeFunctionRemovesOldRecordsRecordsItselfAndRefusesAYoungCutOff() throws SQLException {
        UUID tenant = TenantFixtures.createActiveTenant().id().value();
        insert("probe.old.one", tenant, Instant.now().minus(Duration.ofDays(500)), true, true);
        insert("probe.old.two", tenant, Instant.now().minus(Duration.ofDays(450)), true, true);
        insert("probe.recent", tenant, Instant.now().minus(Duration.ofDays(5)), true, true);

        assertThatThrownBy(() -> TenantFixtures.asNobody(connection -> TenantFixtures.count(connection,
                "select platform_audit_purge(now() - interval '10 days', 100)")))
                .as("a cut-off younger than 30 days").hasMessageContaining("never purged");
        assertThatThrownBy(() -> TenantFixtures.asNobody(connection -> TenantFixtures.count(connection,
                "select platform_audit_purge(now() - interval '400 days', 0)")))
                .as("a batch of zero").hasMessageContaining("batch size");
        assertThatThrownBy(() -> IdentityDb.value(Long.class,
                "select platform_audit_purge(now() - interval '400 days', 100)"))
                .as("called by the owner itself it is still refused: only the application role's session may")
                .hasMessageContaining("append-only");

        long removed = TenantFixtures.asNobody(connection -> TenantFixtures.count(connection,
                "select platform_audit_purge(now() - interval '400 days', 100)"));

        assertThat(removed).isGreaterThanOrEqualTo(2);
        assertThat(IdentityDb.value(Long.class, "select count(*) from audit_record where event_type like "
                + "'probe.old.%'")).isZero();
        assertThat(IdentityDb.value(Long.class, "select count(*) from audit_record where event_type = "
                + "'probe.recent'")).isEqualTo(1);
        assertThat(IdentityDb.value(Long.class, "select count(*) from audit_record where event_type = "
                + "'audit.records.purged' and source = 'SCHEDULER' and audience_platform and not "
                + "audience_organization")).isGreaterThanOrEqualTo(1);
    }

    @Test
    void theRetentionJobKeepsWhatIsYoungerThanThePeriodAndRemovesTheRest() throws SQLException {
        insert("probe.job.old", null, Instant.now().minus(Duration.ofDays(60)), false, true);
        insert("probe.job.new", null, Instant.now().minus(Duration.ofDays(2)), false, true);
        AuditRetention job = new AuditRetention(jdbc, Clock.systemUTC(), new AuditProperties(
                new AuditProperties.Retention(true, Duration.ofDays(1), Duration.ofDays(31), 50)));

        long removed = job.runOnce();

        assertThat(removed).isGreaterThanOrEqualTo(1);
        assertThat(IdentityDb.value(Long.class, "select count(*) from audit_record where event_type = "
                + "'probe.job.old'")).isZero();
        assertThat(IdentityDb.value(Long.class, "select count(*) from audit_record where event_type = "
                + "'probe.job.new'")).isEqualTo(1);
        assertThatThrownBy(() -> new AuditProperties.Retention(true, Duration.ofDays(1), Duration.ofDays(10), 50))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- the read model ----

    @Test
    void anOrganizationReadsItsOwnRowsAndThePlatformItsOwnAndNobodyElsesAreVisible() throws SQLException {
        TestTenant first = TenantFixtures.createActiveTenant();
        TestTenant second = TenantFixtures.createActiveTenant();
        String type = "probe.read.x" + UUID.randomUUID().toString().substring(0, 8).replace("-", "");
        insert(type, first.id().value(), Instant.now(), true, false);
        insert(type, first.id().value(), Instant.now(), false, true);
        insert(type, second.id().value(), Instant.now(), true, false);
        insert(type, null, Instant.now(), false, true);
        insert(type, null, Instant.now(), false, false);

        long inFirst = TenantFixtures.asTenant(first.id(), connection -> countByType(connection, type));
        long inSecond = TenantFixtures.asTenant(second.id(), connection -> countByType(connection, type));
        long onPlatform = TenantFixtures.asNobody(connection -> countByType(connection, type));

        assertThat(inFirst).as("the first organization: its own row for organizations only").isEqualTo(1);
        assertThat(inSecond).as("the second organization: its own row only").isEqualTo(1);
        assertThat(onPlatform).as("no tenant: the rows for the platform").isEqualTo(2);
    }

    @Test
    void anyRecordCanBeWrittenForAnyOrganizationFromAnyContext() throws SQLException {
        TestTenant first = TenantFixtures.createActiveTenant();
        TestTenant second = TenantFixtures.createActiveTenant();
        String type = "probe.write.x" + UUID.randomUUID().toString().substring(0, 8).replace("-", "");

        TenantFixtures.asTenant(first.id(), connection -> TenantFixtures.update(connection,
                "insert into audit_record (event_type, outcome, context_tenant_id, audience_organization, "
                        + "created_by, updated_by) values (?, 'SUCCESS', ?, true, ?, ?)",
                type, second.id().value(), ActorId.SYSTEM.value(), ActorId.SYSTEM.value()));

        long seenBySecond = TenantFixtures.asTenant(second.id(), connection -> countByType(connection, type));
        long seenByFirst = TenantFixtures.asTenant(first.id(), connection -> countByType(connection, type));
        assertThat(seenBySecond).isEqualTo(1);
        assertThat(seenByFirst).isZero();
    }

    // ---- the fields of audit v1 ----

    @Test
    void theFieldsOfAuditV1AreStoredAndTheAudienceFollowsTheKind() throws SQLException {
        UUID tenant = TenantFixtures.createActiveTenant().id().value();
        String type = "access.probe.v1" + UUID.randomUUID().toString().substring(0, 6).replace("-", "");

        recorder.record(new AuditRecord(type, AuditOutcome.SUCCESS, null, new TenantId(tenant), null,
                java.util.Map.of("k", "v"), "object-a", "record-1", "before", "after", AuditSource.SCHEDULER));
        recorder.record(AuditRecord.of("platform.probe." + type.substring(13), AuditOutcome.SUCCESS)
                .inTenant(new TenantId(tenant)));

        try (Connection owner = TestDatabase.ownerConnection();
                PreparedStatement select = owner.prepareStatement(
                        "select object_key, record_id, old_value, new_value, source, audience_organization, "
                                + "audience_platform from audit_record where event_type = ?")) {
            select.setString(1, type);
            try (ResultSet rs = select.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(List.of(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5))).containsExactly("object-a", "record-1", "before", "after", "SCHEDULER");
                assertThat(rs.getBoolean(6)).as("an access record about an organization is for it").isTrue();
                assertThat(rs.getBoolean(7)).as("and not for the platform").isFalse();
            }
        }
        assertThat(IdentityDb.value(Boolean.class, "select audience_organization from audit_record "
                + "where event_type = ?", "platform.probe." + type.substring(13))).isFalse();
        assertThat(IdentityDb.value(String.class, "select source from audit_record where event_type = ?",
                "platform.probe." + type.substring(13))).as("no request here: the platform's own").isEqualTo("SYSTEM");
    }

    @Test
    void aSecondRecordForTheSameEventIsNotWritten() throws SQLException {
        AuditStore store = new AuditStore(jdbc);
        UUID event = UUID.randomUUID();
        AuditRecord record = AuditRecord.of("tenant.lifecycle.probe", AuditOutcome.SUCCESS).from(AuditSource.EVENT);

        boolean first = store.write(record, event);
        boolean second = store.write(record, event);

        assertThat(first).isTrue();
        assertThat(second).isFalse();
        assertThat(IdentityDb.value(Long.class, "select count(*) from audit_record where source_event_id = ?", event))
                .isEqualTo(1);
    }
}
