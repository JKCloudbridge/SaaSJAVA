/**
 * Notification module: email and in-app notifications.
 *
 * <p>Other modules may use only the types in this package (its public API). Everything in sub-packages is
 * internal. Allowed outgoing dependencies are declared below and verified by the architecture tests.
 */
@ApplicationModule(
        displayName = "Notification",
        allowedDependencies = {
            "sharedkernel",
            "tenant",
            "identity"
        })
package app.platform.notification;

import org.springframework.modulith.ApplicationModule;
