/**
 * Application module: the member's side of applications and navigation (Sprint 12). The definitions are metadata; this module says which
 * applications a member may use and what their menu shows.
 *
 * <p>Other modules may use only the types in this package (its public API). Everything in sub-packages is
 * internal. Allowed outgoing dependencies are declared below and verified by the architecture tests.
 */
@ApplicationModule(
        displayName = "Application",
        allowedDependencies = {
            "sharedkernel",
            "tenant",
            "metadata",
            "security"
        })
package app.platform.application;

import org.springframework.modulith.ApplicationModule;
