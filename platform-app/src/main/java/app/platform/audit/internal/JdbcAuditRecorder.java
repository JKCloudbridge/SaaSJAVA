package app.platform.audit.internal;

import app.platform.sharedkernel.ActorId;
import app.platform.sharedkernel.audit.AuditRecord;
import app.platform.sharedkernel.audit.AuditRecorder;
import app.platform.sharedkernel.logging.LogContext;
import java.sql.Types;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Audit v0 (ADR-0022): writes each record as a row of the append-only table {@code audit_record}.
 *
 * <p>The write joins the caller's database transaction when there is one, so a record and the change it describes
 * stand or fall together; without a transaction it is written on its own. The table is platform-level, so a record can
 * be written with or without a tenant context. A failure to write is logged (the exception type and the record's event
 * type only, never its content) and does not reach the caller, as the contract says.
 *
 * <p>Sprint 9 replaces this with the full audit module (who, what, when, tenant, object, old and new value, read
 * model).
 */
@Component
class JdbcAuditRecorder implements AuditRecorder {

    private static final Logger LOG = LoggerFactory.getLogger(JdbcAuditRecorder.class);

    private final JdbcClient jdbc;
    private final JsonMapper json = JsonMapper.builder().build();

    JdbcAuditRecorder(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void record(AuditRecord record) {
        try {
            UUID actor = record.actorUserId() == null ? ActorId.SYSTEM.value() : record.actorUserId();
            jdbc.sql("insert into audit_record (event_type, outcome, actor_user_id, context_tenant_id, reason, "
                            + "request_id, trace_id, attributes, created_by, updated_by) "
                            + "values (:type, :outcome, :user, :tenant, :reason, :request, :trace, "
                            + "cast(:attributes as jsonb), :actor, :actor)")
                    .param("type", record.type())
                    .param("outcome", record.outcome().name())
                    .param("user", record.actorUserId(), Types.OTHER)
                    .param("tenant", record.tenantId() == null ? null : record.tenantId().value(), Types.OTHER)
                    .param("reason", record.reason(), Types.VARCHAR)
                    .param("request", LogContext.requestId().orElse(null), Types.VARCHAR)
                    .param("trace", LogContext.traceId().orElse(null), Types.VARCHAR)
                    .param("attributes", json.writeValueAsString(record.attributes()))
                    .param("actor", actor)
                    .update();
        } catch (RuntimeException e) {
            LOG.error("An audit record of type {} could not be written ({})", record.type(),
                    e.getClass().getSimpleName());
        }
    }
}
