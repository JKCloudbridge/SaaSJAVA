package app.platform.audit.internal;

import app.platform.sharedkernel.audit.AuditRecord;
import app.platform.sharedkernel.audit.AuditRecorder;
import app.platform.sharedkernel.logging.LogContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The recorder of audit v1 (ADR-0054): hands each record to the {@link AuditStore}, which writes it as a row of the
 * append-only table {@code audit_record}.
 *
 * <p>The write joins the caller's database transaction when there is one, so a record and the change it describes
 * stand or fall together; without a transaction it is written on its own. A failure to write is logged (the exception
 * type and the record's event type only, never its content) and does not reach the caller, as the contract says.
 */
@Component
class JdbcAuditRecorder implements AuditRecorder {

    private static final Logger LOG = LoggerFactory.getLogger(JdbcAuditRecorder.class);

    private final AuditStore store;

    JdbcAuditRecorder(AuditStore store) {
        this.store = store;
    }

    /** The attribute that says a change was made through a connected application (Sprint 31, ADR-0272). */
    static final String VIA_CLIENT = "via_client";

    @Override
    public void record(AuditRecord record) {
        try {
            store.write(withClient(record), null);
        } catch (RuntimeException e) {
            LOG.error("An audit record of type {} could not be written ({})", record.type(),
                    e.getClass().getSimpleName());
        }
    }

    /**
     * A change made through a connected application says so next to the member: every record written while the
     * request carries a client identifier gets it as an attribute, without any module having to pass it along and
     * without the tenant context (which every module shares) changing shape. A record that already names a client, or
     * that has no room left, is written as it is.
     */
    private static AuditRecord withClient(AuditRecord record) {
        return LogContext.clientId()
                .filter(client -> !record.attributes().containsKey(VIA_CLIENT)
                        && record.attributes().size() < AuditRecord.MAX_ATTRIBUTES)
                .map(client -> record.with(VIA_CLIENT, client))
                .orElse(record);
    }
}
