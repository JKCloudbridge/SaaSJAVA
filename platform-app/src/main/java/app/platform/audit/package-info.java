/**
 * Audit module: append-only audit records of security, configuration and data changes (audit v1, Sprint 9).
 *
 * <p>Writing: the {@code AuditRecorder} contract lives in the shared kernel and this module writes each record into the
 * append-only table {@code audit_record} (ADR-0022, ADR-0054); it also turns the tenant lifecycle events of the outbox
 * into records and runs the retention of old records and of closed invitations.
 *
 * <p>Reading: {@link app.platform.audit.AuditEvents} serves the audit viewer of an organization (members who hold the
 * ability {@code audit.view}) and of the platform. The database decides which rows a reader may see (ADR-0054).
 *
 * <p>Other modules may use only the types in this package (its public API). Everything in sub-packages is
 * internal. Allowed outgoing dependencies are declared below and verified by the architecture tests.
 */
@ApplicationModule(
        displayName = "Audit",
        allowedDependencies = {
            "sharedkernel", "identity", "security", "tenant"
        })
package app.platform.audit;

import org.springframework.modulith.ApplicationModule;
