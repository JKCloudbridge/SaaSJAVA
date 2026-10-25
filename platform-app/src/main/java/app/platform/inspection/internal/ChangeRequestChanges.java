package app.platform.inspection.internal;

import app.platform.sharedkernel.audit.AuditOutcome;
import app.platform.sharedkernel.audit.AuditRecord;
import app.platform.sharedkernel.audit.AuditRecorder;
import app.platform.tenant.TenantContexts;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
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

    /** The revised copy of the sheet was made for a request (FA8, ADR-0202). */
    void copied(UUID actor, UUID template, UUID request, int versionNo) {
        audit.record(base("inspection.changerequest.copied", actor, template).with("request", request.toString())
                .with("version_no", Integer.toString(versionNo)));
        count("copy", "ok");
    }

    /** The revised copy of the sheet was put in place of its grid by an uploaded file. */
    void copyReplaced(UUID actor, UUID template, UUID request) {
        audit.record(base("inspection.changerequest.copy.replaced", actor, template)
                .with("request", request.toString()));
        count("copy_replace", "ok");
    }

    /** The approvers completed the register; the names of the boxes changed, never what was written in them. */
    void registerCompleted(UUID actor, UUID template, UUID request, List<String> fields) {
        audit.record(base("inspection.changerequest.register.completed", actor, template)
                .with("request", request.toString()).with("fields", String.join(",", fields)));
        count("register", "ok");
    }

    /** The request was published: the revised sheet became the revision in use. */
    void published(UUID actor, UUID template, UUID request, int versionNo, int revision, int audiences) {
        audit.record(base("inspection.changerequest.published", actor, template).with("request", request.toString())
                .with("version_no", Integer.toString(versionNo)).with("revision", Integer.toString(revision))
                .with("audiences", Integer.toString(audiences)));
        count("publish", "ok");
    }

    /** The revised copy was thrown away because the request ended without being published. */
    void copyDiscarded(UUID actor, UUID template, UUID request, int versionNo, String how) {
        audit.record(base("inspection.changerequest.copy.discarded", actor, template)
                .with("request", request.toString()).with("version_no", Integer.toString(versionNo))
                .with("how", how));
        count("discard", "ok");
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
