/**
 * Web module: the HTTP conventions of the API (ADR-0011): global error handling into the single error model,
 * paging parameter binding, OpenAPI generation and the platform status endpoint.
 *
 * <p>Controllers of other modules need nothing from here: they use the envelope and error types of the API
 * contract and throw {@code ApiException}; this module turns those into responses. Everything in sub-packages is
 * internal. Allowed outgoing dependencies are declared below and verified by the architecture tests.
 */
@ApplicationModule(
        displayName = "Web",
        allowedDependencies = {
            "sharedkernel",
            "observability"
        })
package app.platform.web;

import org.springframework.modulith.ApplicationModule;
