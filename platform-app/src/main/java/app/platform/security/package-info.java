/**
 * Security module: profiles, roles, access policies, individual grants, the effective-permission calculator and the
 * read contract that every other module uses to ask what a member may do (Sprint 7, ADR-0039 to ADR-0045). Public
 * groups, object and field permissions and the decision API arrived in Sprint 8 (ADR-0046 to ADR-0050); the cache and
 * the security audit come in Sprint 9.
 *
 * <p>Other modules may use only the types in this package (its public API): {@link app.platform.security.Permissions}
 * (what may this member do), {@link app.platform.security.MemberAccess} (what the identity module changes when members
 * join, return, leave or are given something), the {@link app.platform.security.Ability} catalogue and the pure
 * {@link app.platform.security.EffectivePermissions} calculator. Everything in sub-packages is internal. Allowed
 * outgoing
 * dependencies are declared below and verified by the architecture tests.
 *
 * <p>The edge to {@code identity} of the first draft was reversed in Sprint 7: identity asks security, in the
 * transaction
 * that creates, deactivates or changes a member, so security knows members only by their identifier.
 */
@ApplicationModule(
        displayName = "Security",
        allowedDependencies = {
            "sharedkernel",
            "tenant",
            "licensing"
        })
package app.platform.security;

import org.springframework.modulith.ApplicationModule;
