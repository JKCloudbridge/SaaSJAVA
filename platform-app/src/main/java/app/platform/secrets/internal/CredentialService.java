package app.platform.secrets.internal;

import app.platform.secrets.internal.SecretTables.Summary;
import app.platform.security.Ability;
import app.platform.sharedkernel.secrets.CredentialUsage;
import app.platform.sharedkernel.secrets.SecretRef;
import app.platform.sharedkernel.secrets.SecretStoreException;
import app.platform.sharedkernel.secrets.SecretValue;
import app.platform.tenant.TenantContext;
import app.platformapi.ApiException;
import app.platformapi.CredentialView;
import app.platformapi.ErrorCode;
import app.platformapi.WriteCredentialRequest;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What the credential endpoints do (ADR-0213): list the names, write or replace a value, go back one version, remove.
 * Every call decides who may ask first. The value goes into the store and nowhere else; this class never returns,
 * logs, audits or keeps it, and a failure of the store is answered in the platform's own words.
 */
@Service
@Transactional
class CredentialService {

    private static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9._-]{0,127}");

    private final DatabaseSecretsStore store;
    private final SecretTables tables;
    private final CredentialAccess access;
    private final ObjectProvider<CredentialUsage> usage;

    CredentialService(DatabaseSecretsStore store, SecretTables tables, CredentialAccess access,
            ObjectProvider<CredentialUsage> usage) {
        this.store = store;
        this.tables = tables;
        this.access = access;
        this.usage = usage;
    }

    @Transactional(readOnly = true)
    List<CredentialView> list() {
        access.require(Ability.CREDENTIALS_VIEW);
        return tables.list().stream().map(this::view).toList();
    }

    /** Creates the credential or replaces its value with a new version. */
    CredentialView write(String name, WriteCredentialRequest request) {
        TenantContext caller = access.require(Ability.CREDENTIALS_VIEW, Ability.CREDENTIALS_MANAGE);
        checkName(name);
        SecretValue value = SecretValue.of(request.value());
        try {
            store.put(SecretRef.forTenant(caller.tenantId(), name), value);
        } catch (SecretStoreException failure) {
            throw refusal(failure);
        } finally {
            value.destroy();
        }
        return view(tables.summary(name).orElseThrow());
    }

    /** Makes the version before the current one the current value again. */
    CredentialView rollback(String name) {
        TenantContext caller = access.require(Ability.CREDENTIALS_VIEW, Ability.CREDENTIALS_MANAGE);
        checkName(name);
        tables.summary(name).orElseThrow(() -> ApiException.notFound("This credential does not exist."));
        try {
            store.rollback(SecretRef.forTenant(caller.tenantId(), name)).orElseThrow(
                    () -> new ApiException(ErrorCode.CONFLICT, "There is no earlier version to go back to."));
        } catch (SecretStoreException failure) {
            throw refusal(failure);
        }
        return view(tables.summary(name).orElseThrow());
    }

    /** Removes the credential and every version of it. Refused while a connection that is switched on uses it. */
    void remove(String name) {
        TenantContext caller = access.require(Ability.CREDENTIALS_VIEW, Ability.CREDENTIALS_MANAGE);
        checkName(name);
        tables.summary(name).orElseThrow(() -> ApiException.notFound("This credential does not exist."));
        if (usage.orderedStream().anyMatch(source -> source.inUseWhileOn(name))) {
            throw new ApiException(ErrorCode.CONFLICT,
                    "A connection that is switched on uses this credential. Switch it off first.");
        }
        try {
            store.delete(SecretRef.forTenant(caller.tenantId(), name));
        } catch (SecretStoreException failure) {
            throw refusal(failure);
        }
    }

    private CredentialView view(Summary summary) {
        int users = usage.orderedStream().mapToInt(source -> source.usedBy(summary.name())).sum();
        return new CredentialView(summary.name(), summary.version(), summary.createdAt(), summary.updatedAt(),
                summary.lastUsedAt(), users, summary.hasPrevious());
    }

    private static void checkName(String name) {
        if (name == null || !NAME.matcher(name).matches()) {
            throw ApiException.validation("name", "Use 1 to 128 lower case letters, digits, dots, dashes or "
                    + "underscores, starting with a letter or a digit.");
        }
    }

    /** The platform's own words for a store failure; nothing the store held is repeated. */
    private static ApiException refusal(SecretStoreException failure) {
        return switch (failure.reason()) {
            case VALUE_REJECTED -> ApiException.validation("value", "A credential is 1 to 4096 characters.");
            case LIMIT_REACHED ->
                new ApiException(ErrorCode.CONFLICT, "The organization has reached its limit of credentials.");
            case KEY_UNAVAILABLE, UNREADABLE, STORAGE_FAILED, UNSUPPORTED_SCOPE, SCOPE_MISMATCH ->
                ApiException.unavailable(30);
        };
    }
}
