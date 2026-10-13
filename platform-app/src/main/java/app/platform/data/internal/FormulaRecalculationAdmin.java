package app.platform.data.internal;

import app.platform.metadata.FieldType;
import app.platform.metadata.Metadata;
import app.platform.metadata.ObjectDefinition;
import app.platform.security.Ability;
import app.platform.security.Permissions;
import app.platform.sharedkernel.audit.AuditOutcome;
import app.platform.sharedkernel.audit.AuditRecord;
import app.platform.sharedkernel.audit.AuditRecorder;
import app.platform.tenant.TenantContext;
import app.platform.tenant.TenantContexts;
import app.platformapi.ApiException;
import app.platformapi.ErrorCode;
import app.platformapi.FormulaRecalculationView;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What the administrators of metadata see of the recalculation of an object's stored formula results, and the one thing
 * they can do about it: start it (Sprint 16A, ADR-0113). Reading needs the ability to view metadata, starting it the
 * ability to publish metadata (it applies what was published). Answers on an organization host only; the object is the
 * one of the path in the organization of the host. Nothing here reads a record.
 */
@Service
@Transactional
class FormulaRecalculationAdmin {

    private static final String NO_OBJECT = "This object does not exist.";

    private final Metadata metadata;
    private final FormulaRecalculationStore store;
    private final FormulaRecalculation recalculation;
    private final FormulaCurrency currency;
    private final Permissions permissions;
    private final AuditRecorder audit;
    private final TenantContexts contexts;

    FormulaRecalculationAdmin(Metadata metadata, FormulaRecalculationStore store, FormulaRecalculation recalculation,
            FormulaCurrency currency, Permissions permissions, AuditRecorder audit, TenantContexts contexts) {
        this.metadata = metadata;
        this.store = store;
        this.recalculation = recalculation;
        this.currency = currency;
        this.permissions = permissions;
        this.audit = audit;
        this.contexts = contexts;
    }

    /**
     * The state of an object's stored formula results.
     *
     * @throws ApiException {@code NOT_FOUND} on the platform host or for an object that is not held here,
     *         {@code FORBIDDEN} without the ability to view metadata
     */
    @Transactional(readOnly = true)
    FormulaRecalculationView statusOf(String objectApiName) {
        TenantContext context = member();
        require(context, Ability.METADATA_VIEW);
        return view(object(objectApiName));
    }

    /**
     * Starts a pass over the object's records, whether or not the results are known to be current. A pass that is
     * already waiting or running does the work: asking again changes nothing.
     *
     * @throws ApiException as {@link #statusOf}, with the ability to publish metadata, and {@code CONFLICT} for an
     *         object without formulas
     */
    FormulaRecalculationView start(String objectApiName) {
        TenantContext context = member();
        require(context, Ability.METADATA_PUBLISH);
        ObjectDefinition object = object(objectApiName);
        if (formulas(object) == 0) {
            throw new ApiException(ErrorCode.CONFLICT, "This object has no formulas, so there is nothing to "
                    + "recalculate.");
        }
        boolean asked = recalculation.startNow(object);
        if (asked) {
            audit.record(AuditRecord.of("data.formulas.recalculation.requested", AuditOutcome.SUCCESS)
                    .forUser(context.userId()).inTenant(context.tenantId()).onObject(object.apiName(), null));
        }
        return view(object);
    }

    private TenantContext member() {
        return contexts.current().filter(current -> current.membershipId() != null)
                .orElseThrow(() -> ApiException.notFound(NO_OBJECT));
    }

    private void require(TenantContext context, Ability ability) {
        if (!permissions.has(context.membershipId(), ability)) {
            throw new ApiException(ErrorCode.FORBIDDEN);
        }
    }

    private ObjectDefinition object(String objectApiName) {
        return metadata.object(objectApiName).filter(found -> found.managedBy() == null)
                .orElseThrow(() -> ApiException.notFound(NO_OBJECT));
    }

    private static int formulas(ObjectDefinition object) {
        return (int) object.fields().stream().filter(field -> field.type() == FieldType.FORMULA && !field.retired())
                .count();
    }

    private FormulaRecalculationView view(ObjectDefinition object) {
        FormulaRecalculationStore.Row row = store.find(object.apiName()).orElse(null);
        int formulas = formulas(object);
        String state;
        if (FormulaStamp.of(object).isEmpty()) {
            state = "NONE";
        } else if (row != null && row.state() != FormulaRecalculationStore.State.IDLE) {
            state = row.state().name();
        } else {
            state = currency.isCurrent(object) ? "CURRENT" : "NEEDED";
        }
        if (row == null) {
            return new FormulaRecalculationView(object.apiName(), state, formulas, null, null, 0L, 0L, 0L, null, 0L,
                    0L, 0L);
        }
        boolean running = row.state() != FormulaRecalculationStore.State.IDLE;
        return new FormulaRecalculationView(object.apiName(), state, formulas,
                running && row.startedBy() != null ? row.startedBy().name() : null,
                running ? row.startedAt() : null, running ? row.examined() : 0L, running ? row.changed() : 0L,
                running ? row.unavailable() : 0L, row.lastFinishedAt(), row.lastExamined(), row.lastChanged(),
                row.lastUnavailable());
    }
}
