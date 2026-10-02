package app.platform.platformadmin.internal;

import app.platform.sharedkernel.TenantId;
import app.platform.sharedkernel.audit.AuditOutcome;
import app.platform.sharedkernel.audit.AuditRecord;
import app.platform.sharedkernel.audit.AuditRecorder;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Writes the audit records of platform actions (ADR-0030, ADR-0036): who acted (the platform person), which
 * organization it was about (the target) and why. The reason is free text typed by a platform person, so it is kept as
 * a bounded attribute (never part of the type or the internal reason code), is never echoed into a mail or shown to the
 * organization, and the form asks for it without personal data. The record is written in the transaction of the change
 * when there is one, so a change and its record stand or fall together.
 */
@Component
class PlatformAudit {

    private final AuditRecorder recorder;

    PlatformAudit(AuditRecorder recorder) {
        this.recorder = recorder;
    }

    /** A successful platform action about an organization. */
    void done(String type, UUID actor, TenantId target, String reason) {
        recorder.record(base(type, AuditOutcome.SUCCESS, actor, target, reason));
    }

    /** A successful platform action about an organization, with one more fact (identifiers and codes only). */
    void done(String type, UUID actor, TenantId target, String reason, String key, String value) {
        recorder.record(base(type, AuditOutcome.SUCCESS, actor, target, reason).with(key, value));
    }

    /** A platform action that was refused (the true reason is a code; the caller never sees it). */
    void refused(String type, UUID actor, TenantId target, String code) {
        recorder.record(AuditRecord.of(type, AuditOutcome.DENIED).forUser(actor).inTenant(target).because(code));
    }

    private static AuditRecord base(String type, AuditOutcome outcome, UUID actor, TenantId target, String reason) {
        AuditRecord record = AuditRecord.of(type, outcome).forUser(actor).inTenant(target);
        return reason == null ? record : record.with("reason_text", reason);
    }
}
