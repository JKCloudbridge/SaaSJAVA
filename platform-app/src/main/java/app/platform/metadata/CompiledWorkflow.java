package app.platform.metadata;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * A workflow made ready to run (Sprint 22, ADR-0136, ADR-0137): its definition and its condition already parsed and
 * checked, so that judging a record costs no parsing and no database access. It is immutable and safe to share between
 * threads; evaluating it reads only the values it is given and touches no database, no file and no clock (the moment
 * is an argument).
 */
public interface CompiledWorkflow {

    /** The definition this was made from. */
    WorkflowDefinition definition();

    /** The fields the condition uses, alphabetical; empty when there is no condition. */
    List<String> fields();

    /**
     * Judges the values of a record.
     *
     * @param values the values by field API name in the types the expression engine takes ({@code BigDecimal},
     *        {@code String}, {@code Boolean}, {@code LocalDate}, {@code Instant} or {@code LocalTime}); a field with no
     *        entry, or null, has no value
     * @param now the moment {@code TODAY()} and {@code NOW()} give
     */
    ConditionOutcome evaluate(Map<String, Object> values, Instant now);
}
