/**
 * Platform Admin module: platform-level administration, separate from tenant administration (Sprint 6, ADR-0030 to
 * ADR-0038). The platform console (organizations, plans, platform people, sessions), provisioning an organization for a
 * client, tenant lifecycle by platform administrators, and controlled support access with its single enforcement point
 * ({@code app.platform.sharedkernel.support.SupportAccess}, implemented here).
 *
 * <p>The module has no public API of its own: its endpoints are internal, and what other modules need from it is the
 * contract in the shared kernel. It reaches an organization one at a time through that organization's tenant context
 * (never a privileged connection or a broad system scope), and lists across organizations only from platform-level
 * tables.
 *
 * <p>Other modules may use only the types in this package (its public API). Everything in sub-packages is
 * internal. Allowed outgoing dependencies are declared below and verified by the architecture tests.
 */
@ApplicationModule(
        displayName = "Platform Admin",
        allowedDependencies = {
            "sharedkernel",
            "tenant",
            "identity",
            "licensing",
            "security",
            "audit"
        })
package app.platform.platformadmin;

import org.springframework.modulith.ApplicationModule;
