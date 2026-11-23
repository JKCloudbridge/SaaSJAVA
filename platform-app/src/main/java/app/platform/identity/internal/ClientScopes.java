package app.platform.identity.internal;

import app.platform.sharedkernel.access.ApiScopes;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The scopes a connected application can hold (Sprint 31, ADR-0270): the three operation scopes of the public API and
 * nothing else. This is the one catalogue; the screens draw it from the API, and a test holds it to the scopes the
 * closed table of public operations names ({@link PublicApiAccess}), so a scope cannot exist on one side alone.
 *
 * <p><strong>Scope is necessary, never sufficient.</strong> A scope only lets a request reach an operation; what the
 * member behind the application may do with an object, a field or a record is decided by the same code as for the
 * member's own screens. {@code records.write} does not include {@code records.read}: an application that may only
 * write holds no right to read anything back beyond the answer of the write itself.
 */
final class ClientScopes {

    /** One scope: its key, a name and what it allows. */
    record Scope(String key, String title, String description) {
    }

    /** The catalogue, in the order the screens show it: the platform's one list of scopes (ApiScopes). */
    static final List<Scope> ALL = ApiScopes.ALL.stream()
            .map(scope -> new Scope(scope.key(), scope.title(), scope.description())).toList();

    private ClientScopes() {
    }

    /** The keys of the catalogue. */
    static Set<String> keys() {
        Set<String> keys = new LinkedHashSet<>();
        ALL.forEach(scope -> keys.add(scope.key()));
        return keys;
    }

    /**
     * The scopes a request names, without repeats and in the order of the catalogue.
     *
     * @return empty when the request is empty or names a scope that does not exist
     */
    static Optional<List<String>> normalize(List<String> requested) {
        if (requested == null || requested.isEmpty()) {
            return Optional.empty();
        }
        Set<String> asked = new LinkedHashSet<>();
        for (String key : requested) {
            if (key == null || !keys().contains(key.strip())) {
                return Optional.empty();
            }
            asked.add(key.strip());
        }
        List<String> ordered = new ArrayList<>();
        for (Scope scope : ALL) {
            if (asked.contains(scope.key())) {
                ordered.add(scope.key());
            }
        }
        return Optional.of(List.copyOf(ordered));
    }

    /** The scopes a token may carry: the requested ones that the application holds. */
    static Set<String> narrowed(Set<String> held, Set<String> requested) {
        Set<String> granted = new LinkedHashSet<>(requested);
        granted.retainAll(held);
        return granted;
    }
}
