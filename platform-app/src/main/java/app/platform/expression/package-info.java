/**
 * Expression module: the one small language in which an organization writes a formula or a validation rule
 * (Sprint 13, ADR-0076 to ADR-0081).
 *
 * <p>An expression is text that this module interprets itself. It is parsed into a tree of a few fixed kinds of node,
 * checked against the types of the fields the caller names, and evaluated over a map of values; it never becomes Java,
 * never reaches a class, a method or a file, and never touches the database, so it costs no I/O to evaluate one. Every
 * run is bounded (length, parts, nesting, steps, size of a text, time) and every refusal names the place and the kind
 * of problem in plain words.
 *
 * <p>Other modules use only the types in this package (its public API): {@code ExpressionEngine},
 * {@code Expression}, {@code Compiled}, {@code ExpressionType}, {@code Purpose}, {@code Limits},
 * {@code ExpressionException} and {@code FunctionInfo}. Everything in sub-packages is internal. The module depends on
 * nothing of the platform; metadata uses it now, the data engine, workflow and integration mapping later.
 */
@ApplicationModule(
        displayName = "Expression",
        allowedDependencies = {
            "sharedkernel"
        })
package app.platform.expression;

import org.springframework.modulith.ApplicationModule;
