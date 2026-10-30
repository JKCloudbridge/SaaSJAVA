/**
 * Secrets module: the implementation of the secrets store behind the D4 contract ({@code SecretsStore} in the shared
 * kernel) and the endpoints that put a credential in without ever giving it back (Sprint 27, ADR-0212 to ADR-0215).
 * <p>The module has no public API of its own: other modules ask for a secret only through the shared-kernel contract
 * and are told who uses a credential through {@code CredentialUsage}. Everything here is internal. Only the store
 * implementation names the secret tables, only the cipher turns a value into bytes and back, and no class prints a
 * {@code SecretValue} (architecture tests).
 * <p>Allowed outgoing dependencies are declared below and verified by the architecture tests. {@code integration} does
 * not depend on this module and this module does not depend on {@code integration}.
 */
@ApplicationModule(
        displayName = "Secrets",
        allowedDependencies = {"sharedkernel", "tenant", "security", "licensing", "observability"})
package app.platform.secrets;

import org.springframework.modulith.ApplicationModule;
