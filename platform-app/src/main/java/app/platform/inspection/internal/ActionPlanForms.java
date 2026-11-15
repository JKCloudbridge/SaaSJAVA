package app.platform.inspection.internal;

import app.platform.data.BackgroundRecords;
import app.platform.data.DataRecord;
import app.platform.data.QueriedRecord;
import app.platform.data.QueryFilter;
import app.platform.data.QuerySort;
import app.platform.data.QueryResult;
import app.platform.data.RecordQuery;
import app.platform.data.RecordTitles;
import app.platform.data.Records;
import app.platform.inspection.internal.ActionPlanViews.ActionPlanChoice;
import app.platform.inspection.internal.ActionPlanViews.ActionPlanFormView;
import app.platform.inspection.internal.PlantPublishRule.Reach;
import app.platform.sharedkernel.audit.AuditOutcome;
import app.platform.sharedkernel.audit.AuditRecord;
import app.platform.sharedkernel.audit.AuditRecorder;
import app.platform.tenant.TenantContexts;
import app.platformapi.ApiException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Which document is the Action plan form of a document that is published to plants (MC2B, ADR-0261). The person who may
 * publish the document to its plants (the one who made it, or the one who holds the ability) names it, with the same
 * rule as the plants ({@link PlantPublishRule}), so there is one answer to "who may decide how this document is
 * followed up". The form is chosen among the documents of the same application the person can read: the record store
 * says what they can read, and nothing here adds a rule.
 *
 * <p>The link is kept apart from the document's own record because that record is locked while an approval runs, and
 * it stays through every Revision. Naming a form changes no fill-in that exists: the form a low-scoring fill-in starts
 * its Action plan on is read when the plant head starts it.
 *
 * <p>The audit says the document by its identifier and whether a form was named or taken back, never which one.
 */
@Service
@Transactional
class ActionPlanForms {

    private static final int OFFERED = 50;
    private static final String SAME_DOCUMENT = "A document cannot be its own Action plan form.";
    private static final String NOT_OFFERED = "Choose a document of this application that you can read.";

    private final PlantPublishRule rule;
    private final ActionPlanLinkStore store;
    private final Records records;
    private final BackgroundRecords background;
    private final RecordTitles titles;
    private final AuditRecorder audit;
    private final TenantContexts contexts;

    ActionPlanForms(PlantPublishRule rule, ActionPlanLinkStore store, Records records, BackgroundRecords background,
            RecordTitles titles, AuditRecorder audit, TenantContexts contexts) {
        this.rule = rule;
        this.store = store;
        this.records = records;
        this.background = background;
        this.titles = titles;
        this.audit = audit;
        this.contexts = contexts;
    }

    /** The form now named, and the documents the caller may name instead. */
    @Transactional(readOnly = true)
    ActionPlanFormView view(RecordSet screen, UUID templateId) {
        Reach reach = rule.reach(screen, templateId).orElse(null);
        if (reach == null) {
            return new ActionPlanFormView(false, false, null, null, List.of());
        }
        boolean may = rule.may(reach);
        UUID named = may ? store.actionFormOf(templateId).orElse(null) : null;
        String name = named == null ? null : titles.titlesOf(reach.plantSet().template(), List.of(named)).get(named);
        return new ActionPlanFormView(true, may, named, name, may ? choices(reach) : List.of());
    }

    /**
     * Names the form, or takes it back.
     *
     * @param actionTemplateId the document, or null to take the form back
     * @throws ApiException {@code NOT_FOUND}, {@code CONFLICT} or {@code FORBIDDEN} as for the plants; a validation
     *         error for a document that is not one the caller can read in the same application
     */
    ActionPlanFormView set(RecordSet screen, UUID templateId, UUID actionTemplateId) {
        Reach reach = rule.require(screen, templateId);
        if (actionTemplateId == null) {
            store.clear(templateId, reach.caller().userId());
        } else {
            if (actionTemplateId.equals(templateId)) {
                throw ApiException.validation("actionTemplateId", SAME_DOCUMENT);
            }
            if (!readable(reach, actionTemplateId)) {
                throw ApiException.validation("actionTemplateId", NOT_OFFERED);
            }
            store.set(templateId, actionTemplateId, reach.caller().userId());
        }
        audit.record(AuditRecord.of("inspection.actionplan.form", AuditOutcome.SUCCESS)
                .forUser(reach.caller().userId()).inTenant(contexts.require().tenantId())
                .onObject(reach.plantSet().template(), templateId.toString())
                .with("named", Boolean.toString(actionTemplateId != null)));
        return view(screen, templateId);
    }

    /** The documents of the application the caller can read, other than this one; the record store judges. */
    private List<ActionPlanChoice> choices(Reach reach) {
        RecordSet set = reach.plantSet();
        QueryFilter filter = set.narrow(null);
        QueryResult result = records.query(set.template(), new RecordQuery(filter,
                List.of(new QuerySort(Templates.NAME, false)), List.of(Templates.NAME, Templates.NUMBER), List.of(),
                OFFERED, null));
        return result.records().stream().map(QueriedRecord::record)
                .filter(one -> !one.id().equals(reach.templateId()))
                .map(one -> new ActionPlanChoice(one.id(), text(one, Templates.NAME), text(one, Templates.NUMBER)))
                .toList();
    }

    /** Whether the caller can read the document and it belongs to the same application. */
    private boolean readable(Reach reach, UUID actionTemplateId) {
        RecordSet set = reach.plantSet();
        Map<UUID, String> seen = titles.titlesOf(set.template(), List.of(actionTemplateId));
        if (!seen.containsKey(actionTemplateId)) {
            return false;
        }
        return background.find(set.template(), actionTemplateId).map(DataRecord::fields).map(set::admits)
                .orElse(false);
    }

    private static String text(DataRecord record, String field) {
        return record.fields().get(field) instanceof String value && !value.isBlank() ? value : null;
    }
}
