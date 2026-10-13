package app.platform.data.internal;

import app.platform.metadata.ObjectDefinition;
import org.springframework.stereotype.Component;

/**
 * Whether the stored formula results of an object can be trusted (Sprint 16A, ADR-0113): they can when they are known
 * to be current for the formulas the object has <em>now</em>. The answer compares the fingerprint of the published
 * formulas with the one the last finished pass recorded, so a formula edit makes the results untrusted at the moment
 * it is published, with no event to wait for and no event that can be lost; and a pass in flight keeps them untrusted
 * until it has finished. An object without formulas has nothing to distrust.
 *
 * <p>Must run inside the organization's tenant context (it reads the recalculation state of that organization).
 */
@Component
class FormulaCurrency {

    private final FormulaRecalculationStore store;

    FormulaCurrency(FormulaRecalculationStore store) {
        this.store = store;
    }

    /** Whether the stored results of the object's formulas are current for its formulas now. */
    boolean isCurrent(ObjectDefinition object) {
        String stamp = FormulaStamp.of(object);
        if (stamp.isEmpty()) {
            return true;
        }
        return store.find(object.apiName()).filter(row -> row.state() == FormulaRecalculationStore.State.IDLE
                && stamp.equals(row.currentStamp())).isPresent();
    }
}
