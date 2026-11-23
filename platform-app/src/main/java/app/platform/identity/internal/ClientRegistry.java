package app.platform.identity.internal;

import app.platform.licensing.Entitlements;
import app.platform.security.Ability;
import app.platform.security.Permissions;
import app.platformapi.ApiException;
import app.platformapi.ApiPaths;
import app.platformapi.ClientSecretView;
import app.platformapi.ConnectedAppCatalogue;
import app.platformapi.ConnectedAppDetail;
import app.platformapi.ConnectedAppView;
import app.platformapi.CreateConnectedAppRequest;
import app.platformapi.ErrorCode;
import app.platformapi.NewClientSecret;
import app.platformapi.RotateClientSecretRequest;
import app.platformapi.UpdateConnectedAppRequest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.stereotype.Service;

/**
 * The connected applications of an organization: registering, changing, switching off and on, making and ending
 * secrets, removing (Sprint 31, ADR-0267 to ADR-0270). Every call is one transaction, decided by the caller's
 * abilities and the plan feature (three separate mechanisms), and audited by identifiers and counts, never by a secret
 * or a typed word.
 *
 * <p><strong>An application acts as the member who registers it, for ever.</strong> There is no way to name another
 * member and none to change it: authority never grows, and a person who should be acted as is a new registration.
 * Whatever scopes and objects an application is given only ever narrow what that member may do; the member's own
 * permissions decide every record and field.
 *
 * <p>Every change that can take something away (narrowing the scopes or the objects, switching off, ending a secret,
 * removing) ends the matching tokens in the same transaction, so a token never outlives the decision that took its
 * rights away. A secret is shown once, in the answer of the call that made it, and kept as a hash.
 */
@Service
class ClientRegistry {

    private static final String FEATURE = "integrations";
    private static final String PLAN_REFUSAL = "Connected applications are not part of this organization's plan.";

    private final Administration administration;
    private final Permissions permissions;
    private final Entitlements entitlements;
    private final ClientStore store;
    private final AuthorizationStore grants;
    private final AuthAudit audit;
    private final ClientProperties properties;
    private final Clock clock;

    ClientRegistry(Administration administration, Permissions permissions, Entitlements entitlements,
            ClientStore store, AuthorizationStore grants, AuthAudit audit, ClientProperties properties, Clock clock) {
        this.administration = administration;
        this.permissions = permissions;
        this.entitlements = entitlements;
        this.store = store;
        this.grants = grants;
        this.audit = audit;
        this.properties = properties;
        this.clock = clock;
    }

    // ---- reading ----

    ConnectedAppCatalogue catalogue() {
        return view("clients.catalogue", own -> new ConnectedAppCatalogue(
                ClientScopes.ALL.stream().map(scope -> new ConnectedAppCatalogue.ClientScopeView(scope.key(),
                        scope.title(), scope.description())).toList(),
                ApiPaths.OAUTH2_TOKEN,
                (int) properties.tokens().defaultLife().toMinutes(),
                (int) properties.tokens().min().toMinutes(),
                (int) properties.tokens().max().toMinutes(),
                (int) properties.maxOverlap().toHours(),
                50,
                Words.ACTING,
                Words.STOPPED,
                Words.TOKEN_REQUEST));
    }

    List<ConnectedAppView> list() {
        return view("clients.list", own -> store.list().stream().map(row -> toView(row, own.userId())).toList());
    }

    ConnectedAppDetail read(UUID id) {
        return view("clients.read", own -> detail(found(id), own.userId()));
    }

    // ---- registering and changing ----

    NewClientSecret register(CreateConnectedAppRequest request) {
        return manage("clients.register", own -> {
            List<String> scopes = scopesOf(request.scopes());
            List<String> objects = objectsOf(request.objects());
            int seconds = lifeOf(request.tokenMinutes());
            if (store.count() >= properties.maxPerOrganization()) {
                throw new ApiException(ErrorCode.CONFLICT,
                        "This organization has the most connected applications it may have.");
            }
            String clientId = ClientSecrets.newClientId();
            String description = request.description() == null ? "" : request.description().strip();
            UUID row = store.insert(clientId, request.name().strip(), description, own.id(), own.userId(), scopes,
                    objects, seconds, own.userId());
            String secret = ClientSecrets.generate();
            store.insertSecret(row, ClientSecrets.hash(secret), own.userId());
            audit.clientRegistered(own.userId(), clientId, scopes.size(), objects != null);
            audit.clientSecretMade(own.userId(), clientId, false, 0, 0);
            return new NewClientSecret(detail(found(row), own.userId()), secret);
        });
    }

    ConnectedAppDetail update(UUID id, UpdateConnectedAppRequest request) {
        return manage("clients.update", own -> {
            ClientStore.ClientRow before = locked(id);
            if (before.version() != request.version()) {
                throw new ApiException(ErrorCode.CONCURRENT_MODIFICATION);
            }
            List<String> scopes = scopesOf(request.scopes());
            List<String> objects = objectsOf(request.objects());
            int seconds = lifeOf(request.tokenMinutes());
            String description = request.description() == null ? "" : request.description().strip();
            if (!store.update(id, request.version(), request.name().strip(), description, scopes, objects, seconds,
                    own.userId())) {
                throw new ApiException(ErrorCode.CONCURRENT_MODIFICATION);
            }
            // Narrowing takes effect at once: tokens already issued carry the old scopes (the registration is read at
            // every request as well, but the end of a token is the plain answer).
            boolean narrowed = narrows(before, scopes, objects) || seconds < before.tokenSeconds();
            if (narrowed) {
                grants.revokeByClient(id, "client_narrowed", own.userId());
            }
            audit.clientChanged(own.userId(), before.clientId(), narrowed);
            return detail(found(id), own.userId());
        });
    }

    ConnectedAppDetail disable(UUID id) {
        return manage("clients.disable", own -> switchTo(own, id, false));
    }

    ConnectedAppDetail enable(UUID id) {
        return manage("clients.enable", own -> switchTo(own, id, true));
    }

    private ConnectedAppDetail switchTo(MembershipRepository.Own own, UUID id, boolean on) {
        ClientStore.ClientRow row = locked(id);
        store.setState(id, on ? "ACTIVE" : "DISABLED", own.userId());
        int ended = on ? 0 : grants.revokeByClient(id, "client_disabled", own.userId());
        audit.clientSwitched(own.userId(), row.clientId(), on, ended);
        return detail(found(id), own.userId());
    }

    void remove(UUID id) {
        manage("clients.remove", own -> {
            ClientStore.ClientRow row = locked(id);
            store.endSecretsOf(id, "removed", own.userId());
            int ended = grants.revokeByClient(id, "client_removed", own.userId());
            store.markRemoved(id, own.userId());
            audit.clientRemoved(own.userId(), row.clientId(), ended);
            return Boolean.TRUE;
        });
    }

    // ---- secrets ----

    /**
     * Makes a new secret and ends the older ones: now by default, or after an overlap chosen at this moment. With
     * {@code endTokensNow} the tokens the older secrets already made stop too; otherwise they live until they end.
     */
    NewClientSecret rotate(UUID id, RotateClientSecretRequest request) {
        return manage("clients.rotate", own -> {
            ClientStore.ClientRow row = locked(id);
            int overlapHours = request == null || request.overlapHours() == null ? 0 : request.overlapHours();
            Duration overlap = Duration.ofHours(overlapHours);
            if (overlap.compareTo(properties.maxOverlap()) > 0) {
                throw ApiException.validation("overlapHours", "Is more than the longest overlap allowed.");
            }
            List<ClientStore.SecretRow> older = store.workingSecrets(id);
            boolean endTokens = request != null && request.endTokensNow();
            int ended = 0;
            int stillWorking = 0;
            Instant endsAt = clock.instant().plus(overlap);
            for (ClientStore.SecretRow secret : older) {
                if (overlapHours == 0) {
                    store.endSecret(secret.id(), "rotated", own.userId());
                    ended++;
                } else {
                    // Only ever shorter: a secret already ending sooner keeps its sooner end.
                    if (secret.expiresAt() == null || secret.expiresAt().isAfter(endsAt)) {
                        store.setSecretEnd(secret.id(), endsAt, own.userId());
                    }
                    stillWorking++;
                }
                if (endTokens) {
                    grants.revokeBySecret(secret.id(), "secret_rotated", own.userId());
                }
            }
            if (stillWorking + 1 > properties.maxActiveSecrets()) {
                throw new ApiException(ErrorCode.CONFLICT,
                        "This application already has the most secrets it may have at once. End one first.");
            }
            String secret = ClientSecrets.generate();
            store.insertSecret(id, ClientSecrets.hash(secret), own.userId());
            audit.clientSecretMade(own.userId(), row.clientId(), true, overlapHours, ended);
            return new NewClientSecret(detail(found(id), own.userId()), secret);
        });
    }

    /** Ends one secret now and ends the tokens it made. Ending one that has ended already changes nothing. */
    ConnectedAppDetail revokeSecret(UUID id, UUID secretId) {
        return manage("clients.revoke_secret", own -> {
            ClientStore.ClientRow row = locked(id);
            ClientStore.SecretRow secret = store.findSecret(id, secretId)
                    .orElseThrow(() -> ApiException.notFound("This secret does not exist."));
            int tokens = 0;
            if (secret.endedAt() == null) {
                store.endSecret(secretId, "revoked", own.userId());
                tokens = grants.revokeBySecret(secretId, "secret_revoked", own.userId());
                audit.clientSecretRevoked(own.userId(), row.clientId(), tokens);
            }
            return detail(found(id), own.userId());
        });
    }

    // ---- the rules ----

    /** Whether a change takes something away: a scope, the objects, or the shorter token life. */
    static boolean narrows(ClientStore.ClientRow before, List<String> scopes, List<String> objects) {
        if (!scopes.containsAll(before.scopes())) {
            return true;
        }
        if (objects == null) {
            return false;
        }
        return before.objects() == null || !objects.containsAll(before.objects());
    }

    private static List<String> scopesOf(List<String> requested) {
        return ClientScopes.normalize(requested)
                .orElseThrow(() -> ApiException.validation("scopes", "Choose at least one scope from the list."));
    }

    private static List<String> objectsOf(List<String> requested) {
        if (requested == null) {
            return null;
        }
        Set<String> names = new LinkedHashSet<>(requested);
        if (names.isEmpty()) {
            return null;
        }
        return List.copyOf(names);
    }

    private int lifeOf(Integer minutes) {
        if (minutes == null) {
            return (int) properties.tokens().defaultLife().toSeconds();
        }
        Duration life = Duration.ofMinutes(minutes);
        if (life.compareTo(properties.tokens().min()) < 0 || life.compareTo(properties.tokens().max()) > 0) {
            throw ApiException.validation("tokenMinutes", "Is outside the lifetimes this platform allows.");
        }
        return (int) life.toSeconds();
    }

    // ---- gates ----

    /** Reading: the caller needs {@code clients.view}. */
    private <T> T view(String action, Function<MembershipRepository.Own, T> work) {
        return gate(action, Ability.CLIENTS_VIEW, false, work);
    }

    /** Changing: the caller needs {@code clients.manage} and {@code clients.view}. */
    private <T> T manage(String action, Function<MembershipRepository.Own, T> work) {
        return gate(action, Ability.CLIENTS_MANAGE, true, work);
    }

    private <T> T gate(String action, Ability ability, boolean alsoView, Function<MembershipRepository.Own, T> work) {
        if (!properties.enabled()) {
            throw ApiException.notFound("This is not available at this address.");
        }
        return administration.run(action, ability, own -> {
            if (alsoView && !permissions.has(own.id(), Ability.CLIENTS_VIEW)) {
                throw new ApiException(ErrorCode.FORBIDDEN);
            }
            if (!entitlements.enabled(FEATURE)) {
                throw new ApiException(ErrorCode.CONFLICT, PLAN_REFUSAL);
            }
            return work.apply(own);
        });
    }

    private ClientStore.ClientRow found(UUID id) {
        return store.find(id).orElseThrow(() -> ApiException.notFound("This connected application does not exist."));
    }

    /** The application, held for the rest of the transaction so that changes to it are made one at a time. */
    private ClientStore.ClientRow locked(UUID id) {
        if (!store.lock(id)) {
            throw ApiException.notFound("This connected application does not exist.");
        }
        return found(id);
    }

    // ---- views ----

    private ConnectedAppDetail detail(ClientStore.ClientRow row, UUID caller) {
        Instant now = clock.instant();
        List<ClientSecretView> secrets = new ArrayList<>();
        int keptEnded = 0;
        for (ClientStore.SecretRow secret : store.secretsOf(row.id())) {
            boolean working = secret.works(now);
            if (!working && ++keptEnded > properties.keptEndedSecrets()) {
                continue;
            }
            secrets.add(new ClientSecretView(secret.id(), secret.number(), stateOf(secret, now), secret.createdAt(),
                    secret.lastUsedAt(), endOf(secret)));
        }
        return new ConnectedAppDetail(toView(row, caller), List.copyOf(secrets));
    }

    private static String stateOf(ClientStore.SecretRow secret, Instant now) {
        if (secret.endedAt() != null) {
            return "ENDED";
        }
        if (secret.expiresAt() == null) {
            return "ACTIVE";
        }
        return secret.expiresAt().isAfter(now) ? "ENDING" : "ENDED";
    }

    private static Instant endOf(ClientStore.SecretRow secret) {
        return secret.endedAt() != null ? secret.endedAt() : secret.expiresAt();
    }

    private static ConnectedAppView toView(ClientStore.ClientRow row, UUID caller) {
        String stopped = !row.active() ? "disabled" : !row.memberCanAct() ? "member_not_active" : null;
        return new ConnectedAppView(row.id(), row.clientId(), row.name(), row.description(), row.state(),
                stopped == null, stopped, blankToNull(row.actsAsName()), row.userId().equals(caller),
                row.scopes(), row.objects(), row.tokenSeconds() / 60, row.activeSecrets(), row.createdAt(),
                row.lastUsedAt(), row.version());
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text;
    }

    /** The words the screens show, from the server so that the browser holds none of its own. */
    private static final class Words {

        static final String ACTING = "A connected application acts as the member who registers it, with exactly "
                + "that member's permissions and nothing more; the scopes and the list of objects you choose can only "
                + "narrow them. For an integration that must outlive staff changes, register it as a dedicated "
                + "integration member that an administrator creates for the purpose (an ordinary, licensed member "
                + "whose access policy gives only what the integration needs), not as a named employee. Whom an "
                + "application acts as can never be changed.";

        static final String STOPPED = "This application stopped because the member it acts as is no longer active. "
                + "Nothing is transferred automatically. Register a new application as another member (a dedicated "
                + "integration member is best), give the software its new client identifier and secret, then remove "
                + "this one.";

        static final String TOKEN_REQUEST = "POST " + ApiPaths.OAUTH2_TOKEN + "\n"
                + "Host: <the address of your organization>\n"
                + "Authorization: Basic <client identifier and client secret, joined by a colon, in Base64>\n"
                + "Content-Type: application/x-www-form-urlencoded\n\n"
                + "grant_type=client_credentials&scope=records.read";

        private Words() {
        }
    }
}
