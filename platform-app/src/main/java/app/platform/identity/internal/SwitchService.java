package app.platform.identity.internal;

import app.platform.identity.UserStatus;
import app.platform.tenant.TenantContext;
import app.platform.tenant.TenantContexts;
import app.platformapi.ApiException;
import app.platformapi.OrganizationSummary;
import app.platformapi.SwitchTarget;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Organization switching (ADR-0029). Sessions and cookies belong to a host, so moving to another organization means
 * arriving on its host signed in. Two steps:
 *
 * <ol>
 *   <li>On the host the person is signed in on: they name a destination from their own list. The server checks it
 *       against their memberships (an unknown organization and one they do not belong to give the same answer) and
 *       hands out a one-time proof, valid for a minute, stored only as a hash.</li>
 *   <li>On the destination host: the proof is presented (it travels after the {@code #}, so no server logs it). The
 * host       decides the organization, the proof must have been made for exactly that organization, the person must be
 * an       active member of it, and a sign-in session bound to that host is started. The usual authorization-code step
 *       then gives the browser its cookies.</li>
 * </ol>
 * The destination is a navigation target, never a tenant for the request: the tenant of every call is still derived
 * from the host.
 */
@Service
class SwitchService {

    static final String NOT_AVAILABLE = "That organization is not available.";
    static final String INVALID_LINK = "This link is not valid or has expired.";

    private final OrganizationDirectory directory;
    private final OrganizationHosts hosts;
    private final HandoffRepository handoffs;
    private final MembershipGate gate;
    private final LoginSessions loginSessions;
    private final UserRepository users;
    private final AccountLimiter limiter;
    private final TransactionTemplate transaction;
    private final TenantContexts contexts;
    private final AuthAudit audit;
    private final IdentityProperties properties;
    private final Clock clock;

    SwitchService(OrganizationDirectory directory, OrganizationHosts hosts, HandoffRepository handoffs,
            MembershipGate gate, LoginSessions loginSessions, UserRepository users, AccountLimiter limiter,
            TransactionTemplate transaction, TenantContexts contexts, AuthAudit audit, IdentityProperties properties,
            Clock clock) {
        this.directory = directory;
        this.hosts = hosts;
        this.handoffs = handoffs;
        this.gate = gate;
        this.loginSessions = loginSessions;
        this.users = users;
        this.limiter = limiter;
        this.transaction = transaction;
        this.contexts = contexts;
        this.audit = audit;
        this.properties = properties;
        this.clock = clock;
    }

    /** Whether the current request is on an organization host. */
    boolean onOrganizationHost() {
        return contexts.current().isPresent();
    }

    /** The organizations the person is an active member of. */
    List<OrganizationSummary> mine(UUID userId, String authority) {
        return directory.of(userId).stream()
                .map(org -> new OrganizationSummary(org.slug(), org.displayName(), hosts.of(org.slug(), authority)))
                .toList();
    }

    /**
     * Step one. @return where to go and the proof to take there
     * @throws ApiException {@code NOT_FOUND} with one message for an unknown organization and one the person does not
     *         belong to, {@code RATE_LIMITED}
     */
    SwitchTarget request(UUID userId, String slug, String authority, String source) {
        limiter.admitSwitch(userId);
        OrganizationDirectory.Organization target = directory.find(userId, slug)
                .orElseThrow(() -> {
                    audit.switchRefused(userId, "not_available", source);
                    return ApiException.notFound(NOT_AVAILABLE);
                });
        String proof = Hashes.randomSecret();
        Duration life = properties.account().handoffLife();
        transaction.executeWithoutResult(status ->
                handoffs.insert(userId, target.tenantId(), Hashes.hashed(proof), clock.instant().plus(life)));
        audit.switchRequested(userId, target.tenantId());
        return new SwitchTarget(hosts.of(target.slug(), authority), proof);
    }

    /**
     * Step two, on the destination host. @return the secret of a new sign-in session bound to this host, to be set as
     *         the login cookie
     * @throws ApiException {@code VALIDATION_ERROR} on the field {@code token} with one message for every proof that
     *         cannot be used (unknown, used, expired, made for another organization, the person no longer a member),
     *         {@code RATE_LIMITED}
     */
    String complete(String token, String source) {
        limiter.admitTokenAttempt(source);
        Optional<TenantContext> here = contexts.current();
        HandoffRepository.Live live = handoffs.findLive(Hashes.hashed(token), clock.instant()).orElse(null);
        if (live == null || here.isEmpty() || !live.boundTenantId().equals(here.get().tenantId().value())) {
            throw refused(live == null ? "not_live" : "wrong_host", source, live == null ? null : live.userId());
        }
        if (!handoffs.consume(live.id(), clock.instant())) {
            throw refused("already_used", source, live.userId());
        }
        if (gate.activeMembership(live.userId()).isEmpty()) {
            throw refused("not_a_member", source, live.userId());
        }
        long version = users.findById(live.userId()).filter(user -> user.status() == UserStatus.ACTIVE)
                .map(user -> user.securityVersion())
                .orElseThrow(() -> refused("account_not_active", source, live.userId()));
        String secret = loginSessions.create(live.userId(), live.boundTenantId(), version,
                properties.tokens().loginSession());
        audit.switched(live.userId());
        return secret;
    }

    private ApiException refused(String reason, String source, UUID userId) {
        audit.switchRefused(userId, reason, source);
        return ApiException.validation("token", INVALID_LINK);
    }
}
