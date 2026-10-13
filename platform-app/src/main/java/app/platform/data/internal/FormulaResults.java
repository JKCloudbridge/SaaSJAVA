package app.platform.data.internal;

import app.platform.data.DataRecord;
import app.platform.data.StorageStrategy;
import app.platform.metadata.FieldDefinition;
import app.platform.metadata.ObjectDefinition;
import app.platform.security.ObjectAccess;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Brings the stored formula results of one record that already exists up to date (Sprint 16A, ADR-0113), for the
 * paths that do not go through a write of a person: the recalculation after a formula was edited, and a record that
 * comes back from the recycle bin (it may have been in the bin while the formulas changed, and the recalculation
 * looks only at live records).
 *
 * <p>The results are worked out by {@link Calculations#formulas}, the stage a create and an update use, from the values
 * the record holds, and rewritten without a new version and without a new time of last change
 * ({@link StorageStrategy#updateCalculated}). A result that is no longer there (an input is missing) is removed, as a
 * write removes it; a formula that cannot be calculated from the values (a division by zero) loses its stored result,
 * which no longer belongs to the formula, and the record is counted as unavailable. A record that has another version
 * by the time the results are written is left alone: whoever changed it recalculated the formulas on the way.
 */
@Component
class FormulaResults {

    /**
     * What refreshing one record did.
     *
     * @param written whether results were rewritten
     * @param unavailable whether a formula could not be calculated from the record's values
     */
    record Outcome(boolean written, boolean unavailable) {

        static final Outcome UNCHANGED = new Outcome(false, false);
    }

    private final Calculations calculations;
    private final StorageStrategy storage;

    FormulaResults(Calculations calculations, StorageStrategy storage) {
        this.calculations = calculations;
        this.storage = storage;
    }

    /** Brings the stored results of the record up to date; the object must be the one of the record. */
    Outcome refresh(ObjectDefinition object, DataRecord record) {
        if (FormulaStamp.of(object).isEmpty()) {
            return Outcome.UNCHANGED;
        }
        ObjectAccess everything = new ObjectAccess(object.apiName(), Set.of(), object.fields().stream()
                .map(FieldDefinition::apiName).collect(Collectors.toSet()), Set.of());
        Map<String, Object> values = Calculations.engineValues(object, record.fields());
        Findings findings = new Findings();
        Map<String, Object> results = calculations.formulas(object, values, everything, findings);
        Map<String, Object> set = new LinkedHashMap<>();
        List<String> clear = new ArrayList<>();
        results.forEach((field, result) -> {
            Object held = record.fields().get(field);
            if (result == null) {
                if (held != null) {
                    clear.add(field);
                }
            } else if (!WritePipeline.sameValue(held, result)) {
                set.put(field, result);
            }
        });
        for (String failed : findings.places()) {
            if (record.fields().containsKey(failed) && !clear.contains(failed)) {
                clear.add(failed);
            }
        }
        boolean unavailable = !findings.isEmpty();
        if (set.isEmpty() && clear.isEmpty()) {
            return new Outcome(false, unavailable);
        }
        return new Outcome(storage.updateCalculated(object.apiName(), record.id(), record.version(), set, clear),
                unavailable);
    }
}
