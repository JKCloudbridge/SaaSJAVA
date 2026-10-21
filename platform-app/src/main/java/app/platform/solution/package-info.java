/**
 * Solution module: the platform-shipped solutions (Functional track FA1, ADR-0175, ADR-0179, ADR-0181). A solution is a
 * cluster of business function that ships inside the platform: an application, the objects it needs and its access
 * policies, declared in read-only files ({@code solutions/<key>/solution.yml} and {@code solutions/<key>/objects}),
 * appearing for an organization only while its plan has the solution's feature, and changeable or removable by nobody
 * who works for an organization.
 *
 * <p>This module is generic: no word of any one solution is in its code, only in the files it reads. It loads and
 * checks the bundles at start, answers the shared-kernel contract {@code ShippedApplications} that the member's menu
 * merges (the application is computed for each request and written to no table of the organization), keeps the shipped
 * access policies of an organization in step (rows marked as shipped, made in the organization's own context the first
 * time they are needed), and supplies the words the console shows before a feature is taken away. The objects of a
 * bundle are platform definitions read by the metadata module; this module checks that every one is named by a bundle.
 *
 * <p>Other modules may use only the types in this package (its public API). Everything in sub-packages is
 * internal. Allowed outgoing dependencies are declared below and verified by the architecture tests.
 */
@ApplicationModule(
        displayName = "Solution",
        allowedDependencies = {
            "sharedkernel",
            "tenant",
            "licensing",
            "metadata",
            "security"
        })
package app.platform.solution;

import org.springframework.modulith.ApplicationModule;
