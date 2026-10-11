package app.platform.metadata;

import java.util.List;
import java.util.Map;

/**
 * A formula field made ready to calculate (Sprint 13, ADR-0077, ADR-0080). It is immutable and safe to share between
 * threads; calculating it reads only the values it is given and touches no database, no file and no clock.
 */
public interface CompiledFormula {

    /** The API name of the formula field. */
    String fieldApiName();

    /** The object the field belongs to. */
    String objectApiName();

    /** The type the formula gives, as the field declares it. */
    FieldType resultType();

    /** The fields the formula uses, alphabetical. */
    List<String> fields();

    /**
     * How many formulas are stacked, itself included: 1 for a formula that uses no formula. Formulas are calculated in
     * the order of the list the resolver returns, which already respects it.
     */
    int depth();

    /**
     * Calculates the value for a record.
     *
     * @param values the values by field API name, as for {@link CompiledRule#evaluate}; the values of the formula
     *        fields it uses are calculated first and passed in the same map
     */
    FormulaOutcome calculate(Map<String, Object> values);
}
