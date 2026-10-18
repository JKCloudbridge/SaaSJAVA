package app.platform.metadata;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * An approval process made ready to use (Sprint 24, ADR-0145): its definition and its entry criteria already parsed and
 * checked, so that judging a record costs no parsing and no database access. It is immutable and safe to share between
 * threads; evaluating it reads only the values it is given and touches no database, no file and no clock (the moment is
 * an argument).
 */
public interface CompiledApprovalProcess {

    /** The definition this was made from. */
    ApprovalProcessDefinition definition();

    /** The fields the entry criteria use, alphabetical; empty when there are none. */
    List<String> fields();

    /**
     * Judges the values of a record against the entry criteria.
     *
     * @param values the values by field API name in the types the expression engine takes; a field with no entry, or
     *        null, has no value
     * @param now the moment {@code TODAY()} and {@code NOW()} give
     * @return {@link ConditionOutcome#TRUE} when the record may be submitted (also when there are no criteria),
     *         {@link ConditionOutcome#FALSE} when it may not, {@link ConditionOutcome#NOT_EVALUABLE} when the criteria
     *         cannot be evaluated (a request is then refused, never started)
     */
    ConditionOutcome evaluate(Map<String, Object> values, Instant now);
}
