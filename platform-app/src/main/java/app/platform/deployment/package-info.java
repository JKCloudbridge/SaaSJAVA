/**
 * Deployment module: how one instance of the application behaves when it is started, released and stopped by a
 * deployment (Sprint 33, ADR-0286 to ADR-0291): the check that the database schema is at least as new as the release,
 * the migration job that brings it there, the delay that lets a load balancer stop sending requests before a stopping
 * instance stops accepting them, and the build stamp that says which release is running.
 *
 * <p>It exposes nothing to other modules and nothing depends on it; everything is in sub-packages. It adds no edge:
 * it uses only the shared kernel (the worker drain helper). It never touches a table of the platform: the one question
 * it asks of the database is the schema version probe {@code platform_schema_version()}.
 */
@ApplicationModule(
        displayName = "Deployment",
        allowedDependencies = {
            "sharedkernel"
        })
package app.platform.deployment;

import org.springframework.modulith.ApplicationModule;
