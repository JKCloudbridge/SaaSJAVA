package app.platform.identity.internal;

import app.platform.licensing.Entitlements;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * What is judged on every request that carries the token of a connected application, beyond what is judged for any
 * token (Sprint 31, ADR-0269): the switch, the plan feature, and the registration as it is now. A token is only a
 * reference to a grant; what the application may do is read from the registration at the moment of the request, so a
 * narrowing, a switch-off or a removal is seen by the very next request on every instance, whatever the token says.
 *
 * <p>The scopes are those of the token that the registration still holds, and the object list is the registration's
 * own: both can only narrow. Nothing here grants anything; the member behind the application decides every record and
 * field.
 */
@Component
class MachineTokens {

    private static final Logger LOG = LoggerFactory.getLogger(MachineTokens.class);
    private static final String FEATURE = "integrations";

    /**
     * What an application may do right now.
     *
     * @param scopes the scopes of the token that the application still holds
     * @param objects the only objects it may use, or null for every object its member may reach
     */
    record Live(Set<String> scopes, Set<String> objects) {
    }

    private final ClientStore store;
    private final TransactionTemplate transaction;
    private final ClientProperties properties;
    private final Entitlements entitlements;
    private final Counters counters;

    MachineTokens(ClientStore store, TransactionTemplate transaction, ClientProperties properties,
            Entitlements entitlements, Counters counters) {
        this.store = store;
        this.transaction = transaction;
        this.properties = properties;
        this.entitlements = entitlements;
        this.counters = counters;
    }

    /**
     * Judges the grant of an application.
     *
     * @return what it may do, or empty when the token must not be used: the switch is off, the plan lacks the feature,
     *         or the application is off or gone
     */
    Optional<Live> check(AuthorizationStore.Introspected grant) {
        if (!properties.enabled() || !entitlements.enabled(FEATURE)) {
            return Optional.empty();
        }
        Optional<ClientStore.ClientRow> row = transaction.execute(status -> store.find(grant.clientRowId()));
        if (row == null || row.isEmpty() || !row.get().active()) {
            return Optional.empty();
        }
        Set<String> scopes = new LinkedHashSet<>(grant.scopes());
        scopes.retainAll(row.get().scopes());
        Set<String> objects = row.get().objects() == null ? null : Set.copyOf(row.get().objects());
        touch(grant);
        return Optional.of(new Live(Set.copyOf(scopes), objects));
    }

    /**
     * Says that the secret was used, at most once in the interval for one application: no row is written on every
     * request. A failure to write never fails the request.
     */
    private void touch(AuthorizationStore.Introspected grant) {
        if (grant.secretId() == null
                || counters.increment("clientuse:" + grant.clientRowId(), properties.lastUsedInterval()) != 1) {
            return;
        }
        try {
            transaction.executeWithoutResult(status -> store.touchSecret(grant.secretId(), grant.userId()));
        } catch (RuntimeException e) {
            // "Last used" is for people to read; a request that is otherwise good is never refused because of it.
            LOG.warn("The last use of a connected application could not be written ({})",
                    e.getClass().getSimpleName());
        }
    }
}
