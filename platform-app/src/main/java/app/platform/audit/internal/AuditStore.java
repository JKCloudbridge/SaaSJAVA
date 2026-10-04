package app.platform.audit.internal;

import app.platform.sharedkernel.ActorId;
import app.platform.sharedkernel.audit.AuditRecord;
import app.platform.sharedkernel.audit.AuditSource;
import app.platform.sharedkernel.logging.LogContext;
import java.sql.Types;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes one record into the append-only table {@code audit_record} (audit v1, ADR-0054). Joins the caller's
 * transaction when there is one. Used by the recorder (which never fails its caller) and by the event handler (which
 * must fail, so that the event is retried).
 */
@Component
class AuditStore {

    private final JdbcClient jdbc;
    private final JsonMapper json = JsonMapper.builder().build();

    AuditStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Writes the record.
     *
     * @param sourceEventId the outbox event the record is made from, or null; a second record for the same event is
     *        not written
     * @return whether a row was written (false only for an event that was recorded before)
     */
    boolean write(AuditRecord record, UUID sourceEventId) {
        UUID actor = record.actorUserId() == null ? ActorId.SYSTEM.value() : record.actorUserId();
        boolean aboutOrganization = record.tenantId() != null;
        AuditAudience audience = AuditAudience.of(record.type(), aboutOrganization);
        AuditSource source = record.source() != null ? record.source()
                : LogContext.requestId().isPresent() ? AuditSource.API : AuditSource.SYSTEM;
        String conflict = sourceEventId == null ? ""
                : " on conflict (source_event_id) where source_event_id is not null and deleted_at is null "
                        + "do nothing";
        return jdbc.sql("insert into audit_record (event_type, outcome, actor_user_id, context_tenant_id, reason, "
                        + "request_id, trace_id, attributes, object_key, record_id, old_value, new_value, source, "
                        + "source_event_id, audience_organization, audience_platform, created_by, updated_by) "
                        + "values (:type, :outcome, :user, :tenant, :reason, :request, :trace, "
                        + "cast(:attributes as jsonb), :object, :record, :old, :new, :source, :event, :org, "
                        + ":platform, :actor, :actor)" + conflict)
                .param("type", record.type())
                .param("outcome", record.outcome().name())
                .param("user", record.actorUserId(), Types.OTHER)
                .param("tenant", record.tenantId() == null ? null : record.tenantId().value(), Types.OTHER)
                .param("reason", record.reason(), Types.VARCHAR)
                .param("request", LogContext.requestId().orElse(null), Types.VARCHAR)
                .param("trace", LogContext.traceId().orElse(null), Types.VARCHAR)
                .param("attributes", json.writeValueAsString(record.attributes()))
                .param("object", record.objectKey(), Types.VARCHAR)
                .param("record", record.recordId(), Types.VARCHAR)
                .param("old", record.oldValue(), Types.VARCHAR)
                .param("new", record.newValue(), Types.VARCHAR)
                .param("source", source.name())
                .param("event", sourceEventId, Types.OTHER)
                .param("org", audience.organization())
                .param("platform", audience.platform())
                .param("actor", actor)
                .update() > 0;
    }
}
