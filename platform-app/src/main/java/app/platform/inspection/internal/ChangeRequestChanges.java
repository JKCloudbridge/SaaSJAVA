package app.platform.inspection.internal;

import app.platform.sharedkernel.audit.AuditOutcome;
import app.platform.sharedkernel.audit.AuditRecord;
import app.platform.sharedkernel.audit.AuditRecorder;
import app.platform.tenant.TenantContexts;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Writes the audit record and the metrics of what happens to a change request, in the transaction of the change (FA5,
 * ADR-0191).
 *
 * <p><strong>Facts only.</strong> An audit record names the template and the request by identifier, the version numbers
 * and counts. Never a title, a comment, a reason, a label of the grid or the name of a person: those are the
 * organization's text (ADR-0012). A metric tag is one of a few fixed words.
 */
@Component
class ChangeRequestChanges {

    private static final String OBJECT = Templates.OBJECT;

    private final AuditRecorder audit;
    private final TenantContexts contexts;
    private final MeterRegistry meters;

    ChangeRequestChanges(AuditRecorder audit, TenantContexts contexts, MeterRegistry meters) {
        this.audit = audit;
        this.contexts = contexts;
        this.meters = meters;
    }

    /** A request was made with {@code comments} comments about {@code places} places. */
    void requested(UUID actor, UUID template, UUID request, int versionNo, int comments, int places) {
        audit.record(base("inspection.changerequest.created", actor, template).with("request", request.toString())
                .with("version_no", Integer.toString(versionNo)).with("comments", Integer.toString(comments))
                .with("places", Integer.toString(places)));
        count("create", "ok");
    }

    /** A comment was added to a request; {@code kind} is the kind of place, a fixed word. */
    void commented(UUID actor, UUID template, UUID request, UUID comment, String kind) {
        audit.record(base("inspection.changerequest.commented", actor, template).with("request", request.toString())
                .with("comment", comment.toString()).with("place", kind));
        count("comment", "ok");
    }

    /** A request was answered: {@code act} is accepted, declined or reopened. */
    void answered(UUID actor, UUID template, UUID request, String act) {
        audit.record(base("inspection.changerequest." + act, actor, template).with("request", request.toString()));
        count(act, "ok");
    }

    /** Requests were turned into a version; {@code created} is false when they joined a draft that existed. */
    void turned(UUID actor, UUID template, int versionNo, boolean created, int requests) {
        audit.record(base("inspection.changerequest.turned", actor, template)
                .with("version_no", Integer.toString(versionNo)).with("created", Boolean.toString(created))
                .with("requests", Integer.toString(requests)));
        count("turn", "ok");
    }

    /** A request was refused; {@code reason} is one of a few fixed words. */
    void refused(String act, String reason) {
        count(act, reason);
    }

    private AuditRecord base(String type, UUID actor, UUID template) {
        return AuditRecord.of(type, AuditOutcome.SUCCESS).forUser(actor).inTenant(contexts.require().tenantId())
                .onObject(OBJECT, template.toString());
    }

    private void count(String act, String result) {
        meters.counter("platform.inspection.changerequest", "act", act, "result", result).increment();
    }
}
