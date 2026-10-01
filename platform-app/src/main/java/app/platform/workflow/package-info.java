/**
 * Workflow module: workflow definitions, triggers, actions and execution.
 *
 * <p>Other modules may use only the types in this package (its public API). Everything in sub-packages is
 * internal. Allowed outgoing dependencies are declared below and verified by the architecture tests.
 */
@ApplicationModule(
        displayName = "Workflow",
        allowedDependencies = {
            "sharedkernel",
            "tenant",
            "metadata",
            "security",
            "data",
            "approval",
            "notification",
            "integration"
        })
package app.platform.workflow;

import org.springframework.modulith.ApplicationModule;
