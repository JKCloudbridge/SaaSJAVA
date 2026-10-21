package app.platform.security.internal;

import app.platform.security.ShippedAccessPolicies;
import app.platform.sharedkernel.ActorId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Makes the shipped access policies of a solution in the organization of the current tenant context (ADR-0181), after
 * the pattern of {@link SystemProfiles}: lock the organization's access changes first (so two requests are decided one
 * after the other), look, make what is missing, and write one audit record that counts. The database lets this
 * transaction write rows marked as shipped because it says so ({@link AccessStore#allowShippedPolicyWrites}); nobody
 * else can.
 */
@Service
class DefaultShippedAccessPolicies implements ShippedAccessPolicies {

    private static final String SUFFIX = " (platform)";

    private final AccessStore store;
    private final DataAccessStore dataStore;
    private final AccessAudit audit;

    DefaultShippedAccessPolicies(AccessStore store, DataAccessStore dataStore, AccessAudit audit) {
        this.store = store;
        this.dataStore = dataStore;
        this.audit = audit;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean upToDate(String owner, List<Declaration> declared) {
        Map<String, String> held = store.shippedPolicies(owner).stream()
                .collect(Collectors.toMap(AccessStore.PolicyRow::managedKey, AccessStore.PolicyRow::managedHash));
        return declared.stream().allMatch(declaration -> declaration.fingerprint().equals(held.get(declaration.key())));
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Result ensure(String owner, List<Declaration> declared) {
        store.lockAccessChanges();
        store.allowShippedPolicyWrites();
        Map<String, AccessStore.PolicyRow> held = new HashMap<>();
        store.shippedPolicies(owner).forEach(row -> held.put(row.managedKey(), row));
        int made = 0;
        int updated = 0;
        for (Declaration declaration : declared) {
            AccessStore.PolicyRow existing = held.get(declaration.key());
            String fingerprint = declaration.fingerprint();
            if (existing == null) {
                UUID id = store.insertShippedPolicy(freeName(declaration.name()), declaration.description(), owner,
                        declaration.key(), fingerprint, ActorId.SYSTEM);
                dataStore.replace(DataAccessStore.Holder.POLICY, id, declaration.objects(), Map.of(), ActorId.SYSTEM);
                made++;
            } else if (!fingerprint.equals(existing.managedHash())) {
                // A new name is taken only when it is free; otherwise the policy keeps the name it has.
                String name = declaration.name().equalsIgnoreCase(existing.name())
                        || !store.policyNameTaken(declaration.name()) ? declaration.name() : existing.name();
                store.updateShippedPolicy(existing.id(), name, declaration.description(), fingerprint, ActorId.SYSTEM);
                dataStore.replace(DataAccessStore.Holder.POLICY, existing.id(), declaration.objects(), Map.of(),
                        ActorId.SYSTEM);
                updated++;
            }
        }
        if (made + updated > 0) {
            audit.shippedPoliciesEnsured(made, updated);
        }
        return new Result(made, updated);
    }

    /** The name, or the name with a suffix when an administrator's policy already has it (names are unique). */
    private String freeName(String name) {
        if (!store.policyNameTaken(name)) {
            return name;
        }
        String candidate = name + SUFFIX;
        for (int attempt = 2; store.policyNameTaken(candidate); attempt++) {
            candidate = name + " (platform " + attempt + ")";
        }
        return candidate;
    }
}
