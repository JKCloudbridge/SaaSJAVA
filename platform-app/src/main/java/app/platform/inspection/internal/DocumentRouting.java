package app.platform.inspection.internal;

import app.platform.inspection.internal.DocumentRoutingViews.RoutingApplicationView;
import app.platform.inspection.internal.DocumentRoutingViews.RoutingIdentificationView;
import app.platform.inspection.internal.DocumentRoutingViews.RoutingRuleView;
import app.platform.inspection.internal.DocumentRoutingViews.RoutingView;
import app.platform.inspection.internal.DocumentRoutingViews.SaveRoutingRuleRequest;
import app.platform.inspection.internal.TemplateAccess.Caller;
import app.platform.metadata.FieldConfiguration.PicklistConfiguration;
import app.platform.metadata.FieldDefinition;
import app.platform.metadata.Metadata;
import app.platform.metadata.PicklistValue;
import app.platform.security.Ability;
import app.platform.sharedkernel.audit.AuditOutcome;
import app.platform.sharedkernel.audit.AuditRecord;
import app.platform.sharedkernel.audit.AuditRecorder;
import app.platform.sharedkernel.solution.ShippedApplicationView;
import app.platform.sharedkernel.solution.ShippedApplications;
import app.platform.tenant.TenantContexts;
import app.platformapi.ApiException;
import app.platformapi.ErrorCode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Which application a document also belongs to, decided by its number (MC1.1, ADR-0259). An organization sets rules in
 * Setup, Numbering, Application routing: Identification, and optionally Part A, to an application whose solution says
 * its documents come from Document Management. The platform asks {@link #applicationFor} at the moment a document's
 * number is made and writes the answer on the document once; a rule changed or removed later changes no document that
 * was numbered before.
 *
 * <p>Managing the rules needs the ability {@code numbering.manage}; asking is the platform's own work and needs none.
 * What a rule can name is data of the solution files, never a list in this code, and an application the organization
 * does not have is never chosen (a rule that names one is shown as unavailable and does nothing). The Identification
 * and Part A are the organization's text: not logged, not audited; the audit says the application's key.
 */
@Service
@Transactional
class DocumentRouting {

    private static final String TOO_MANY = "This organization has the most rules it can have.";
    private static final int MOST_RULES = 500;

    private final DocumentRoutingStore store;
    private final RecordSets sets;
    private final TemplateAccess access;
    private final Metadata metadata;
    private final ObjectProvider<ShippedApplications> shipped;
    private final AuditRecorder audit;
    private final TenantContexts contexts;

    DocumentRouting(DocumentRoutingStore store, RecordSets sets, TemplateAccess access, Metadata metadata,
            ObjectProvider<ShippedApplications> shipped, AuditRecorder audit, TenantContexts contexts) {
        this.store = store;
        this.sets = sets;
        this.access = access;
        this.metadata = metadata;
        this.shipped = shipped;
        this.audit = audit;
        this.contexts = contexts;
    }

    // ---- what the numbering asks (the platform's own work) ----

    /**
     * The application a document with this number's identity belongs to: the rule of its exact Identification and
     * Part A, else the rule of its Identification alone. Null when no rule fits, when the rule names an application
     * the organization does not have, or one whose documents are another object's than {@code templateObject}.
     *
     * @param templateObject the object the document is a record of
     * @param fields the document's fields (the Identification and Part A are read from them)
     */
    @Transactional(readOnly = true)
    String applicationFor(String templateObject, Map<String, Object> fields) {
        Object identification = fields.get(Templates.CATEGORY);
        if (Templates.isBlank(identification)) {
            return null;
        }
        Object partA = fields.get(Templates.PART_A);
        Optional<String> found = store.applicationFor(identification.toString().strip(),
                Templates.isBlank(partA) ? "" : partA.toString().strip());
        return found.filter(sets::routable)
                .filter(application -> sets.routedSet(application)
                        .map(set -> set.template().equals(templateObject)).orElse(false))
                .orElse(null);
    }

    // ---- what the screen asks (Setup, Numbering, Application routing) ----

    @Transactional(readOnly = true)
    RoutingView view() {
        access.require(Ability.NUMBERING_MANAGE);
        return currentView();
    }

    RoutingView save(SaveRoutingRuleRequest request) {
        Caller caller = access.require(Ability.NUMBERING_MANAGE);
        String identification = request.identification().strip();
        String partA = request.partA() == null ? "" : request.partA().strip();
        String application = request.application().strip();
        if (!knownIdentification(identification)) {
            throw ApiException.validation("identification", "Choose an identification from the list.");
        }
        if (!partA.isEmpty() && !Templates.isPart(partA)) {
            throw ApiException.validation("partA", "Part A uses capital letters, digits or a hyphen, up to 10.");
        }
        if (!sets.routable(application)) {
            throw ApiException.validation("application", "Choose an application from the list.");
        }
        boolean wasChanged;
        String kind;
        if (request.version() == null) {
            if (store.rules().size() >= MOST_RULES) {
                throw ApiException.validation("identification", TOO_MANY);
            }
            wasChanged = store.insert(identification, partA, application, caller.userId());
            if (!wasChanged) {
                throw new ApiException(ErrorCode.CONFLICT, "A rule for this combination already exists. Change "
                        + "that one instead.");
            }
            kind = "made";
        } else {
            UUID id = store.rules().stream()
                    .filter(rule -> rule.identification().equals(identification) && rule.partA().equals(partA))
                    .map(DocumentRoutingStore.Rule::id).findFirst()
                    .orElseThrow(() -> new ApiException(ErrorCode.CONFLICT, "This rule was changed or removed by "
                            + "someone else. Reload and try again."));
            wasChanged = store.update(id, application, request.version(), caller.userId());
            if (!wasChanged) {
                throw new ApiException(ErrorCode.CONFLICT, "This rule was changed or removed by someone else. "
                        + "Reload and try again.");
            }
            kind = "changed";
        }
        audit.record(AuditRecord.of("inspection.routing." + kind, AuditOutcome.SUCCESS).forUser(caller.userId())
                .inTenant(contexts.require().tenantId()).with("application", application));
        return currentView();
    }

    RoutingView remove(UUID id, long version) {
        Caller caller = access.require(Ability.NUMBERING_MANAGE);
        Optional<DocumentRoutingStore.Rule> rule = store.rules().stream()
                .filter(found -> found.id().equals(id)).findFirst();
        if (rule.isEmpty() || !store.remove(id, version, caller.userId())) {
            throw new ApiException(ErrorCode.CONFLICT, "This rule was changed or removed by someone else. Reload and "
                    + "try again.");
        }
        audit.record(AuditRecord.of("inspection.routing.removed", AuditOutcome.SUCCESS).forUser(caller.userId())
                .inTenant(contexts.require().tenantId()).with("application", rule.get().application()));
        return currentView();
    }

    // ---- the pieces ----

    private RoutingView currentView() {
        Map<String, String> labels = offered();
        List<RoutingRuleView> rules = new ArrayList<>();
        for (DocumentRoutingStore.Rule rule : store.rules()) {
            boolean available = labels.containsKey(rule.application());
            rules.add(new RoutingRuleView(rule.id(), rule.identification(), rule.partA(), rule.application(),
                    labels.get(rule.application()), available, rule.version()));
        }
        List<RoutingApplicationView> applications = labels.entrySet().stream()
                .map(entry -> new RoutingApplicationView(entry.getKey(), entry.getValue())).toList();
        return new RoutingView(rules, applications, identifications());
    }

    /** The applications a rule can name and the organization has, with their names, in the order of the grid. */
    private Map<String, String> offered() {
        Map<String, String> labels = new LinkedHashMap<>();
        ShippedApplications source = shipped.getIfAvailable();
        if (source == null) {
            return labels;
        }
        UUID membership = contexts.current().map(context -> context.membershipId()).orElse(null);
        Map<String, String> byKey = source.forMember(membership).stream()
                .collect(Collectors.toMap(ShippedApplicationView::apiName, ShippedApplicationView::label,
                        (first, second) -> first));
        for (String application : sets.routedApplications()) {
            if (sets.routable(application) && byKey.containsKey(application)) {
                labels.put(application, byKey.get(application));
            }
        }
        return labels;
    }

    /** The values of the Identification list of the documents the offered applications are made of. */
    private List<RoutingIdentificationView> identifications() {
        return pick().map(values -> values.stream().map(value -> new RoutingIdentificationView(value.value(),
                value.label(), value.active())).toList()).orElse(List.of());
    }

    private boolean knownIdentification(String code) {
        return pick().map(values -> values.stream().anyMatch(value -> value.value().equals(code))).orElse(false);
    }

    private Optional<List<PicklistValue>> pick() {
        return sets.routedApplications().stream().filter(sets::routable).findFirst().flatMap(sets::routedSet)
                .flatMap(set -> metadata.object(set.template()))
                .flatMap(object -> object.field(Templates.CATEGORY))
                .map(FieldDefinition::configuration)
                .filter(PicklistConfiguration.class::isInstance).map(PicklistConfiguration.class::cast)
                .map(PicklistConfiguration::values);
    }
}
