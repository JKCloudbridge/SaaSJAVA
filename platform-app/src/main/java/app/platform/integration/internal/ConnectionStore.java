package app.platform.integration.internal;

import app.platform.integration.internal.ConnectionMachine.State;
import app.platform.tenant.TenantContexts;
import app.platformapi.IntegrationView;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * The only class that names {@code integration_connection} (ADR-0208). Every statement is a constant with parameters
 * and names the tenant as well as relying on row level security. Nothing here logs; a row prints as redacted.
 */
@Repository
class ConnectionStore {

    /** The parts of a definition that are written together. */
    record Definition(String label, int connectorVersion, String baseUrl, String authKind, String authHeaderName,
            String credentialName, Map<String, String> settings, List<IntegrationView.Subscription> subscriptions,
            List<IntegrationView.ScopeEntry> scope, String fingerprint) {

        Definition {
            settings = Map.copyOf(settings);
            subscriptions = List.copyOf(subscriptions);
            scope = List.copyOf(scope);
        }

        @Override
        public String toString() {
            return "Definition[redacted]";
        }
    }

    /** A stored connection. */
    record Connection(UUID id, String name, String connectorType, Definition definition, State state,
            String testedFingerprint, Instant lastTestAt, String lastTestResult, String lastTestReason,
            long version, String inboundId) {

        @Override
        public String toString() {
            return "Connection[redacted]";
        }
    }

    private static final String COLUMNS = "id, name, label, connector_type, connector_version, base_url, auth_kind, "
            + "auth_header_name, credential_name, cast(settings as text) as settings, "
            + "cast(subscriptions as text) as subscriptions, cast(scope as text) as scope, state, "
            + "definition_fingerprint, tested_fingerprint, last_test_at, last_test_result, last_test_reason, version, "
            + "inbound_id";
    private static final TypeReference<Map<String, String>> SETTINGS = new TypeReference<>() {
    };
    private static final TypeReference<List<IntegrationView.Subscription>> SUBSCRIPTIONS = new TypeReference<>() {
    };
    private static final TypeReference<List<IntegrationView.ScopeEntry>> SCOPE = new TypeReference<>() {
    };

    private final JdbcClient jdbc;
    private final TenantContexts contexts;
    private final JsonMapper json = JsonMapper.builder().build();

    ConnectionStore(JdbcClient jdbc, TenantContexts contexts) {
        this.jdbc = jdbc;
        this.contexts = contexts;
    }

    List<Connection> list() {
        return jdbc.sql("select " + COLUMNS + " from integration_connection where tenant_id = :tenant "
                        + "and deleted_at is null order by name")
                .param("tenant", tenant()).query((rs, row) -> map(rs)).list();
    }

    long count() {
        return jdbc.sql("select count(*) from integration_connection where tenant_id = :tenant "
                        + "and deleted_at is null").param("tenant", tenant()).query(Long.class).single();
    }

    Optional<Connection> find(String name) {
        return jdbc.sql("select " + COLUMNS + " from integration_connection where tenant_id = :tenant "
                        + "and name = :name and deleted_at is null")
                .param("tenant", tenant()).param("name", name).query((rs, row) -> map(rs)).optional();
    }

    Optional<Connection> findById(UUID id) {
        return jdbc.sql("select " + COLUMNS + " from integration_connection where tenant_id = :tenant "
                        + "and id = :id and deleted_at is null")
                .param("tenant", tenant()).param("id", id).query((rs, row) -> map(rs)).optional();
    }

    /** The connections that are switched on and usable. Small by the limit per organization; read afresh each time. */
    List<Connection> enabled() {
        return jdbc.sql("select " + COLUMNS + " from integration_connection where tenant_id = :tenant "
                        + "and state = 'ENABLED' and deleted_at is null")
                .param("tenant", tenant()).query((rs, row) -> map(rs)).list();
    }

    void insert(String name, String connectorType, Definition definition, UUID actor) {
        jdbc.sql("insert into integration_connection (name, label, connector_type, connector_version, base_url, "
                        + "auth_kind, auth_header_name, credential_name, settings, subscriptions, scope, "
                        + "definition_fingerprint, created_by, updated_by) values (:name, :label, :type, :cv, :url, "
                        + ":auth, :header, :credential, cast(:settings as jsonb), cast(:subscriptions as jsonb), "
                        + "cast(:scope as jsonb), :fingerprint, :actor, :actor)")
                .param("name", name).param("label", definition.label()).param("type", connectorType)
                .param("cv", definition.connectorVersion()).param("url", definition.baseUrl())
                .param("auth", definition.authKind()).param("header", definition.authHeaderName())
                .param("credential", definition.credentialName())
                .param("settings", json.writeValueAsString(definition.settings()))
                .param("subscriptions", json.writeValueAsString(definition.subscriptions()))
                .param("scope", json.writeValueAsString(definition.scope()))
                .param("fingerprint", definition.fingerprint()).param("actor", actor).update();
    }

    /** Changes the definition and the state together; false when the version read is stale. */
    boolean update(UUID id, long version, Definition definition, State state, String testedFingerprint, UUID actor) {
        return jdbc.sql("update integration_connection set label = :label, connector_version = :cv, "
                        + "base_url = :url, auth_kind = :auth, auth_header_name = :header, "
                        + "credential_name = :credential, settings = cast(:settings as jsonb), "
                        + "subscriptions = cast(:subscriptions as jsonb), scope = cast(:scope as jsonb), "
                        + "definition_fingerprint = :fingerprint, state = :state, "
                        + "tested_fingerprint = :tested, version = version + 1, updated_by = :actor "
                        + "where tenant_id = :tenant and id = :id and version = :version and deleted_at is null")
                .param("label", definition.label()).param("cv", definition.connectorVersion())
                .param("url", definition.baseUrl()).param("auth", definition.authKind())
                .param("header", definition.authHeaderName()).param("credential", definition.credentialName())
                .param("settings", json.writeValueAsString(definition.settings()))
                .param("subscriptions", json.writeValueAsString(definition.subscriptions()))
                .param("scope", json.writeValueAsString(definition.scope()))
                .param("fingerprint", definition.fingerprint()).param("state", state.name())
                .param("tested", testedFingerprint).param("actor", actor).param("tenant", tenant())
                .param("id", id).param("version", version).update() == 1;
    }

    /** Moves the state only (switch on or off); false when the version read is stale. */
    boolean setState(UUID id, long version, State state, UUID actor) {
        return jdbc.sql("update integration_connection set state = :state, version = version + 1, "
                        + "updated_by = :actor where tenant_id = :tenant and id = :id and version = :version "
                        + "and deleted_at is null")
                .param("state", state.name()).param("actor", actor).param("tenant", tenant()).param("id", id)
                .param("version", version).update() == 1;
    }

    /**
     * Takes the right to run a test: one claim per connection per interval, decided by the database clock, so two
     * instances cannot both test it. A claim is not a change of the definition.
     */
    boolean claimTest(UUID id, Duration minInterval, UUID actor) {
        return jdbc.sql("update integration_connection set test_claimed_at = now(), version = version + 1, "
                        + "updated_by = :actor where tenant_id = :tenant and id = :id and deleted_at is null "
                        + "and (test_claimed_at is null or test_claimed_at < now() - make_interval(secs => :secs))")
                .param("actor", actor).param("tenant", tenant()).param("id", id)
                .param("secs", (double) minInterval.toMillis() / 1000.0).update() == 1;
    }

    /**
     * Stores the result of a test of the definition with this fingerprint and the state it leads to. A result for a
     * definition that has been changed since is dropped (false).
     */
    boolean recordTest(UUID id, String fingerprint, State state, String testedFingerprint, boolean passed,
            String reason, UUID actor) {
        return jdbc.sql("update integration_connection set state = :state, tested_fingerprint = :tested, "
                        + "last_test_at = now(), last_test_result = :result, last_test_reason = :reason, "
                        + "version = version + 1, updated_by = :actor where tenant_id = :tenant and id = :id "
                        + "and definition_fingerprint = :fingerprint and deleted_at is null")
                .param("state", state.name()).param("tested", testedFingerprint)
                .param("result", passed ? "PASSED" : "FAILED").param("reason", reason).param("actor", actor)
                .param("tenant", tenant()).param("id", id).param("fingerprint", fingerprint).update() == 1;
    }

    /** How many live connections name the credential. */
    int countUsing(String credentialName) {
        return jdbc.sql("select count(*) from integration_connection where tenant_id = :tenant "
                        + "and (credential_name = :name or settings ->> 'inboundCredential' = :name) "
                        + "and deleted_at is null")
                .param("tenant", tenant()).param("name", credentialName).query(Integer.class).single();
    }

    /** Whether a connection that is switched on (working or failing) names the credential. */
    boolean anyOnUsing(String credentialName) {
        return jdbc.sql("select exists (select 1 from integration_connection where tenant_id = :tenant "
                        + "and (credential_name = :name or settings ->> 'inboundCredential' = :name) "
                        + "and state in ('ENABLED', 'FAILING') and deleted_at is null)")
                .param("tenant", tenant()).param("name", credentialName).query(Boolean.class).single();
    }

    /**
     * Voids what a test vouched for after the credential changed (ADR-0213): a tested connection goes back to
     * untested, a connection that is switched off must be tested before it is switched on again, a connection that is
     * switched on keeps running, and every one shows that its last test no longer counts. The fingerprint of the
     * definition is not touched: the credential's value was never part of it.
     */
    int voidTests(String credentialName, UUID actor) {
        return jdbc.sql("update integration_connection set "
                        + "state = case when state = 'TESTED' then 'UNTESTED' else state end, "
                        + "tested_fingerprint = case when state in ('TESTED', 'DISABLED') then null "
                        + "else tested_fingerprint end, "
                        + "last_test_result = case when last_test_at is null then last_test_result else 'VOIDED' end, "
                        + "last_test_reason = case when last_test_at is null then last_test_reason "
                        + "else 'credential_changed' end, "
                        + "version = version + 1, updated_by = :actor where tenant_id = :tenant "
                        + "and (credential_name = :name or settings ->> 'inboundCredential' = :name) "
                        + "and deleted_at is null")
                .param("actor", actor).param("tenant", tenant()).param("name", credentialName).update();
    }

    boolean remove(UUID id, long version, UUID actor) {
        return jdbc.sql("update integration_connection set deleted_at = now(), deleted_by = :actor, "
                        + "version = version + 1, updated_by = :actor where tenant_id = :tenant and id = :id "
                        + "and version = :version and deleted_at is null")
                .param("actor", actor).param("tenant", tenant()).param("id", id).param("version", version)
                .update() == 1;
    }

    private Connection map(java.sql.ResultSet rs) throws java.sql.SQLException {
        Definition definition = new Definition(rs.getString("label"), rs.getInt("connector_version"),
                rs.getString("base_url"), rs.getString("auth_kind"), rs.getString("auth_header_name"),
                rs.getString("credential_name"), json.readValue(rs.getString("settings"), SETTINGS),
                json.readValue(rs.getString("subscriptions"), SUBSCRIPTIONS),
                json.readValue(rs.getString("scope"), SCOPE), rs.getString("definition_fingerprint"));
        java.sql.Timestamp at = rs.getTimestamp("last_test_at");
        return new Connection(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("connector_type"),
                definition, State.valueOf(rs.getString("state")), rs.getString("tested_fingerprint"),
                at == null ? null : at.toInstant(), rs.getString("last_test_result"),
                rs.getString("last_test_reason"), rs.getLong("version"), rs.getString("inbound_id"));
    }

    private UUID tenant() {
        return contexts.require().tenantId().value();
    }
}
