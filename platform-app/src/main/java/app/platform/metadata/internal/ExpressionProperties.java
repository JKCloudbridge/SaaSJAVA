package app.platform.metadata.internal;

import app.platform.expression.Limits;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings of the expression engine (ADR-0076, ADR-0081): the bounds that keep one expression from costing more than a
 * calculation should. The length of a formula is fixed at the 4000 characters of the field and of the database
 * check, so it is not a setting. Lowering a bound never makes a stored expression wrong; it makes the engine refuse a
 * bigger one, with the name of the bound.
 *
 * @param maxNodes the most parts (values, fields, operators, calls) of one expression (default 500)
 * @param maxDepth the deepest nesting of brackets, calls and prefix operators (default 32)
 * @param maxSteps the most steps of one evaluation (default 10000)
 * @param maxTextLength the most characters of a text value or result (default 100000, the longest long text)
 * @param maxMillis the most milliseconds of one evaluation, the last line of defence behind the steps (default 50)
 */
@ConfigurationProperties("platform.expression.limits")
record ExpressionProperties(@DefaultValue("500") int maxNodes, @DefaultValue("32") int maxDepth,
        @DefaultValue("10000") int maxSteps, @DefaultValue("100000") int maxTextLength,
        @DefaultValue("50") long maxMillis) {

    /** The bounds as the engine takes them. */
    Limits limits() {
        return new Limits(Limits.FORMULA_LENGTH, maxNodes, maxDepth, maxSteps, maxTextLength, maxMillis);
    }
}
