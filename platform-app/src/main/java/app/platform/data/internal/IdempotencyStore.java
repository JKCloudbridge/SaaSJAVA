package app.platform.data.internal;

import app.platform.data.IdempotentWrites;
import app.platform.tenant.TenantContexts;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The memory of keyed writes (Sprint 30, ADR-0246): the table {@code idempotency_record}. Every statement is a constant
 * with parameters; the organization is a parameter taken from the tenant context in addition to the forced row level
 * security of the table. What the rows mean is decided by {@link DefaultIdempotentWrites} and {@link BulkRecords}, not
 * here. Nothing in a row is a value of a record or a word a person typed; the key is only ever a hash.
 */
@Repository
class IdempotencyStore {

    /** An earlier outcome as stored; the outcome is the JSON text the database holds. */
    record Row(String operation, String requestHash, String outcome) {

        /** Prints nothing of the outcome. */
        @Override
        public String toString() {
            return "IdempotencyStore.Row[" + operation + "]";
        }
    }

    /**
     * Makes the callers that use one key of one member wait for each other until the transaction ends, so that the
     * second one finds what the first one wrote. A lock on the key itself, not on a row: the row does not exist yet.
     */
    private static final String LOCK =
            "select 1 from (select pg_advisory_xact_lock(hashtextextended(:scope, 0))) as held";

    private static final String DELETE_EXPIRED = "delete from idempotency_record where tenant_id = :tenant "
            + "and caller_id = :caller and key_hash = :key and expires_at <= now()";

    private static final String FIND = "select operation, request_hash, outcome::text as outcome "
            + "from idempotency_record where tenant_id = :tenant and caller_id = :caller and key_hash = :key "
            + "and deleted_at is null";

    private static final String INSERT = "insert into idempotency_record (tenant_id, caller_id, key_hash, "
            + "request_hash, operation, object_api_name, outcome, expires_at, created_by, updated_by) values "
            + "(:tenant, :caller, :key, :request, :operation, :object, cast(:outcome as jsonb), "
            + "now() + make_interval(secs => :seconds), :caller, :caller)";

    private static final String PURGE = "delete from idempotency_record where id in (select id from "
            + "idempotency_record where tenant_id = :tenant and expires_at <= now() order by expires_at limit :limit)";

    private final JdbcClient jdbc;
    private final TenantContexts contexts;

    IdempotencyStore(JdbcClient jdbc, TenantContexts contexts) {
        this.jdbc = jdbc;
        this.contexts = contexts;
    }

    /** Waits for any other transaction that holds the key; must run inside the transaction of the write. */
    void lock(UUID caller, String keyHash) {
        jdbc.sql(LOCK).param("scope", tenant() + ":" + caller + ":" + keyHash).query(Integer.class).single();
    }

    /** The earlier outcome of the key, if it is still good (an expired one is removed first and so is no outcome). */
    Optional<Row> find(UUID caller, String keyHash) {
        jdbc.sql(DELETE_EXPIRED).param("tenant", tenant()).param("caller", caller).param("key", keyHash).update();
        return jdbc.sql(FIND).param("tenant", tenant()).param("caller", caller).param("key", keyHash)
                .query((rs, row) -> new Row(rs.getString("operation"), rs.getString("request_hash"),
                        rs.getString("outcome")))
                .optional();
    }

    void insert(IdempotentWrites.Claim claim, String operation, String objectApiName, String outcomeJson) {
        jdbc.sql(INSERT).param("tenant", tenant()).param("caller", claim.caller()).param("key", claim.keyHash())
                .param("request", claim.requestHash()).param("operation", operation).param("object", objectApiName)
                .param("outcome", outcomeJson).param("seconds", (double) claim.retention().toSeconds()).update();
    }

    /** Removes expired rows of the organization the thread works for; the number removed. */
    int purgeExpired(int limit) {
        return jdbc.sql(PURGE).param("tenant", tenant()).param("limit", limit).update();
    }

    private UUID tenant() {
        return contexts.require().tenantId().value();
    }
}
