package app.platform.integration;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.sharedkernel.ActorId;
import app.platform.testsupport.TestApplication;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestDatabase;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.tenancy.TenantFixtures;
import com.jayway.jsonpath.JsonPath;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Two real instances and a worker that stops (Sprint 29, ADR-0231): two application instances against one
 * database claim the same queue without ever sending a change twice, and a delivery that an instance was sending
 * when it stopped is taken over by whichever instance sees its lease end, written down as interrupted and sent
 * with the same key.
 */
@ReliabilityTest
class MultiInstanceIT extends ReliabilitySupport {

    private static final String[] PROPERTIES = {
        "platform.outbox.enabled=true",
        "platform.integration.reliability.poll-interval=100ms",
        "platform.integration.reliability.retry.initial-delay=300ms",
        "platform.integration.reliability.retry.max-delay=2s",
        "platform.integration.reliability.circuit.failure-threshold=3",
        "platform.integration.reliability.circuit.open-for=3s",
        "platform.integration.flow.send-timeout=2s"};

    private FakeService service;
    private TestApplication second;

    @BeforeEach
    void start() throws Exception {
        service = new FakeService();
        service.creates("/api/item", "ext");
        second = TestApplication.start(PROPERTIES);
    }

    @AfterEach
    void stop() {
        second.close();
        service.close();
    }

    @Test
    void twoInstancesClaimTheSameQueueWithoutSendingAnythingTwice() throws Exception {
        Linked linked = linked(service, "EXTERNAL_WINS");
        TestBrowser admin = linked.admin();
        String connection = linked.connection();
        int records = 14;

        for (int i = 0; i < records; i++) {
            created(admin, newTitle());
        }
        await(linked, "every change to be sent", () -> count(admin, connection, "DONE") == records);

        assertThat(service.effectsOf("POST", "/api/item")).as("each record was made once").isEqualTo(records);
        assertThat(service.asked("/api/item")).as("and asked once: nothing was sent twice").isEqualTo(records);
        List<Integer> attempts = JsonPath.read(deliveries(admin, connection).body(), "$.data[*].attempts");
        assertThat(attempts).hasSize(records).allMatch(one -> one == 1);
    }

    @Test
    void aDeliveryHeldByAnInstanceThatStoppedIsTakenOverWrittenDownAndSentWithTheSameKey() throws Exception {
        Linked linked = linked(service, "EXTERNAL_WINS");
        TestBrowser admin = linked.admin();
        String connection = linked.connection();

        // The record is made while the connection is off, so no delivery is made for it by the handler ...
        Response off = disable(admin, connection);
        assertThat(off.status()).as(off.body()).isEqualTo(200);
        String record = created(admin, newTitle());
        // The event of that record must be handled while the connection is off, or it would make a delivery of its own.
        await(linked, "the event of the record to be handled", () -> pendingEvents(linked) == 0);
        UUID version = liveVersionOf(linked);
        // ... and the row an instance would have left behind when it stopped mid-send is written by hand.
        UUID delivery = UUID.randomUUID();
        try (Connection owner = TestDatabase.ownerConnection()) {
            owner.setAutoCommit(false);
            TenantFixtures.setTenant(owner, linked.organization().id());
            try (PreparedStatement insert = owner.prepareStatement("insert into integration_delivery (id, tenant_id, "
                    + "connection_id, mapping_version_id, direction, kind, event_id, object_api_name, record_id, "
                    + "state, attempts, run_at, locked_until, last_attempt_at, idempotency_key, created_by, "
                    + "updated_by) select ?, c.tenant_id, c.id, ?, 'OUT', 'CREATE', ?, 'ObjectA__c', ?, 'PENDING', "
                    + "0, now() - interval '5 minutes', null, null, null, ?, ? from integration_connection c "
                    + "where c.name = ? and c.tenant_id = ?")) {
                insert.setObject(1, delivery);
                insert.setObject(2, version);
                insert.setObject(3, UUID.randomUUID());
                insert.setObject(4, UUID.fromString(record));
                insert.setObject(5, ActorId.SYSTEM.value());
                insert.setObject(6, ActorId.SYSTEM.value());
                insert.setString(7, connection);
                insert.setObject(8, linked.organization().id().value());
                assertThat(insert.executeUpdate()).isEqualTo(1);
            }
            try (PreparedStatement held = owner.prepareStatement("update integration_delivery set state = 'SENDING', "
                    + "attempts = 1, locked_until = now() - interval '1 minute', "
                    + "last_attempt_at = now() - interval '2 minutes', idempotency_key = id::text || ':1', "
                    + "version = version + 1 where id = ?")) {
                held.setObject(1, delivery);
                assertThat(held.executeUpdate()).isEqualTo(1);
            }
            owner.commit();
        }
        assertThat(service.effectsOf("POST", "/api/item")).as("nothing has been sent yet").isZero();

        // The connection is switched on again; the lease of the row has ended, so an instance takes it over.
        // The set-up tested the connection a moment ago; a second test inside the minimum interval answers 429.
        await(linked, "the connection to be testable again", () -> test(admin, connection).status() == 200);
        assertThat(enable(admin, connection, versionOf(admin, connection)).status()).isEqualTo(200);
        await(linked, "the held delivery to be taken over", () -> count(admin, connection, "DONE") == 1);

        var story = attemptsOf(admin, connection, delivery.toString());
        assertThat(story.getFirst()).containsEntry("outcome", "INTERRUPTED").containsEntry("reason", "interrupted");
        assertThat(story.getLast()).containsEntry("outcome", "SUCCESS");
        assertThat(service.effectsOf("POST", "/api/item")).isEqualTo(1);
        assertThat(service.seen().stream().filter(one -> one.method().equals("POST")).findFirst().orElseThrow()
                .header("idempotency-key")).as("the repeat carries the key of the send that was lost")
                .isEqualTo(delivery + ":1");
    }

    private static long pendingEvents(Linked linked) {
        try (Connection owner = TestDatabase.ownerConnection()) {
            owner.setAutoCommit(false);
            TenantFixtures.setTenant(owner, linked.organization().id());
            try (PreparedStatement statement = owner.prepareStatement("select count(*) from outbox_event "
                    + "where tenant_id = ? and status = 'PENDING'")) {
                statement.setObject(1, linked.organization().id().value());
                try (ResultSet found = statement.executeQuery()) {
                    found.next();
                    return found.getLong(1);
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("The outbox could not be read: " + e.getSQLState(), e);
        }
    }

    private static UUID liveVersionOf(Linked linked) throws SQLException {
        try (Connection owner = TestDatabase.ownerConnection()) {
            owner.setAutoCommit(false);
            TenantFixtures.setTenant(owner, linked.organization().id());
            try (PreparedStatement statement = owner.prepareStatement("select v.id from integration_mapping_version v "
                    + "where v.status = 'LIVE' and v.tenant_id = ?")) {
                statement.setObject(1, linked.organization().id().value());
                try (ResultSet found = statement.executeQuery()) {
                    assertThat(found.next()).isTrue();
                    return found.getObject(1, UUID.class);
                }
            }
        }
    }
}
