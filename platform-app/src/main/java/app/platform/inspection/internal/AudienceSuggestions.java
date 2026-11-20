package app.platform.inspection.internal;

import app.platform.inspection.internal.AudienceChoices.Place;
import app.platform.sharedkernel.approval.ApprovalHookRefusal;
import app.platform.sharedkernel.approval.ApprovalHooks.DecisionContext;
import app.platform.sharedkernel.audit.AuditOutcome;
import app.platform.sharedkernel.audit.AuditRecord;
import app.platform.sharedkernel.audit.AuditRecorder;
import app.platform.tenant.TenantContexts;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The audience a step of the template review suggests for a template (FA6G, ADR-0200). At a step that asks for a
 * choice the approver cannot approve without a location; the choice is judged here, against the lists the organization
 * offers now, and kept with the request. It is a suggestion only: <strong>no share is made and nobody gains
 * access</strong>. The person who publishes (the Publisher policy) is shown it ready-filled and chooses the real
 * audience.
 *
 * <p>It runs inside the approval's own transaction (a refusal undoes the approval) and is short. Nothing it holds is
 * written to a log, an audit record, an event or a notice: the audit says that a suggestion was made and how many
 * places it holds, never which.
 */
@Component
class AudienceSuggestions {

    private final AudienceSuggestionStore store;
    private final DepartmentAudiences audiences;
    private final AuditRecorder audit;
    private final TenantContexts contexts;
    private final MeterRegistry meters;

    AudienceSuggestions(AudienceSuggestionStore store, DepartmentAudiences audiences, AuditRecorder audit,
            TenantContexts contexts, MeterRegistry meters) {
        this.store = store;
        this.audiences = audiences;
        this.audit = audit;
        this.contexts = contexts;
        this.meters = meters;
    }

    /**
     * Judges the choice sent with an approval of a step that asks for one and keeps it. The first approval of the
     * request's step that asks writes the suggestion; a later one (a step that needs everybody) is judged the same way
     * and leaves what was written untouched, so a suggestion is never changed after it is made.
     *
     * @param templateObject the template object of the set the request belongs to (FA13B, ADR-0226)
     * @param templateId the template the request is about (for a change request the record under review is not it)
     * @throws ApprovalHookRefusal when no location was chosen or a chosen place is not offered
     */
    void accept(String templateObject, DecisionContext context, UUID templateId) {
        List<Place> places = AudienceChoices.parse(context.choices());
        Map<String, List<String>> offered = audiences.offered();
        for (Place place : places) {
            List<String> departments = offered.get(place.location());
            if (departments == null || place.department() != null && !departments.contains(place.department())) {
                throw new ApprovalHookRefusal("choice_not_offered", AudienceChoices.NOT_OFFERED);
            }
        }
        if (store.exists(context.requestId())) {
            return;
        }
        store.insert(context.requestId(), templateId, context.stepPosition(), places, context.approver());
        audit.record(AuditRecord.of("inspection.audience.suggested", AuditOutcome.SUCCESS)
                .forUser(context.approver()).inTenant(contexts.require().tenantId())
                .onObject(templateObject, templateId.toString())
                .with("places", Integer.toString(places.size())));
        meters.counter("platform.inspection.audience.suggestion", "result", "ok").increment();
    }

    /** What the request suggested, with the places that are no longer offered left out and counted. */
    Held held(UUID requestId) {
        List<Place> stored = store.ofRequest(requestId);
        if (stored.isEmpty()) {
            return new Held(List.of(), 0);
        }
        Map<String, List<String>> offered = audiences.offered();
        List<Place> kept = new ArrayList<>();
        for (Place place : stored) {
            List<String> departments = offered.get(place.location());
            if (departments != null && (place.department() == null || departments.contains(place.department()))) {
                kept.add(place);
            }
        }
        return new Held(List.copyOf(kept), stored.size() - kept.size());
    }

    /**
     * The suggestion that binds the publishing of one version (FA15, ADR-0265): the one its own review made and, when
     * that review made none (a revised version is reviewed by a change request), the template's most recent one.
     * Nothing is returned for a template no step ever made a suggestion for, and then nothing limits a publisher.
     *
     * @param reviewedRequest the approval request that reviewed the version, or null when none is known
     * @param templateId the template
     */
    Held forVersion(UUID reviewedRequest, UUID templateId) {
        Held own = reviewedRequest == null ? new Held(List.of(), 0) : held(reviewedRequest);
        if (!own.places().isEmpty() || own.dropped() > 0) {
            return own;
        }
        return store.latestRequestOfTemplate(templateId).map(this::held).orElse(own);
    }

    /**
     * A suggestion as it stands today.
     *
     * @param places the places still offered
     * @param dropped how many places are no longer offered and were left out
     */
    record Held(List<Place> places, int dropped) {
    }
}
