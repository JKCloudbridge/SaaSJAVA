/**
 * Audit module: append-only audit records of security, configuration and data changes.
 *
 * <p>Version 0 (Sprint 3): the {@code AuditRecorder} contract lives in the shared kernel and this module writes each
 * record into the platform-level, append-only table {@code audit_record} (ADR-0022). The full module is Sprint 9.
 *
 * <p>Other modules may use only the types in this package (its public API). Everything in sub-packages is
 * internal. Allowed outgoing dependencies are declared below and verified by the architecture tests.
 */
@ApplicationModule(
        displayName = "Audit",
        allowedDependencies = {
            "sharedkernel"
        })
package app.platform.audit;

import org.springframework.modulith.ApplicationModule;
