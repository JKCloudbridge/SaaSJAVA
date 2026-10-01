package app.platform.sharedkernel.secrets;

import app.platform.sharedkernel.TenantId;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Logical address of a secret, independent of any concrete secrets store.
 *
 * <p>A secret lives in exactly one scope: the platform itself, or one tenant. Stores must never
 * allow a lookup to cross scopes.
 *
 * @param scope {@code platform} or {@code tenant:<tenant-id>}
 * @param name lower-case identifier unique within the scope, for example {@code erp-connection-1}
 */
public record SecretRef(String scope, String name) {

    private static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9._-]{0,127}");
    private static final String PLATFORM_SCOPE = "platform";
    private static final String TENANT_SCOPE_PREFIX = "tenant:";

    public SecretRef {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(name, "name");
        if (!NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Invalid secret name");
        }
        if (!PLATFORM_SCOPE.equals(scope) && !scope.startsWith(TENANT_SCOPE_PREFIX)) {
            throw new IllegalArgumentException("Invalid secret scope");
        }
    }

    public static SecretRef forPlatform(String name) {
        return new SecretRef(PLATFORM_SCOPE, name);
    }

    public static SecretRef forTenant(TenantId tenantId, String name) {
        Objects.requireNonNull(tenantId, "tenantId");
        return new SecretRef(TENANT_SCOPE_PREFIX + tenantId, name);
    }
}
