/**
 * Observability module: request correlation, structured logging context, error tracking hook and the
 * correlation stamp that lets a database server log be tied back to a request (ADR-0012).
 *
 * <p>Other modules may use only the types in this package (its public API): the error tracking hook
 * ({@link app.platform.observability.ErrorTracker}, {@link app.platform.observability.ErrorReporter}).
 * Everything in sub-packages is internal. Allowed outgoing dependencies are declared below and verified by the
 * architecture tests.
 */
@ApplicationModule(
        displayName = "Observability",
        allowedDependencies = {
            "sharedkernel"
        })
package app.platform.observability;

import org.springframework.modulith.ApplicationModule;
