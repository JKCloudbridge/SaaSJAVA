package app.platform.metadata;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * A validation rule made ready to judge a record (Sprint 13, ADR-0078, ADR-0080). It is immutable and safe to share
 * between threads; evaluating it reads only the values it is given and touches no database, no file and no clock (the
 * moment of the save is an argument).
 */
public interface CompiledRule {

    /** The API name of the rule. */
    String apiName();

    /** The object the rule belongs to. */
    String objectApiName();

    /** The message the person who is rejected reads. It is text of the organization: never log it. */
    String errorMessage();

    /** The field the message is shown on, or null for the record as a whole. */
    String errorField();

    /** The fields the condition uses, alphabetical. */
    List<String> fields();

    /**
     * Judges the values of a record. The rule rejects the save when its condition is true; a condition with no value
     * does not reject.
     *
     * @param values the values by field API name: {@code BigDecimal} (or a whole number type), {@code String},
     *        {@code Boolean}, {@code LocalDate}, {@code Instant} or {@code LocalTime}; a field with no entry, or null,
     *        has no value
     * @param now the moment {@code TODAY()} and {@code NOW()} give
     */
    RuleOutcome evaluate(Map<String, Object> values, Instant now);
}
