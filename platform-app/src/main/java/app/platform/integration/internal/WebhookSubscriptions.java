package app.platform.integration.internal;

import app.platform.integration.internal.SubscriptionMachine.Event;
import app.platform.integration.internal.WebhookSubscriptionStore.Subscription;
import app.platform.metadata.Metadata;
import app.platform.security.Ability;
import app.platform.sharedkernel.TenantId;
import app.platform.sharedkernel.audit.AuditOutcome;
import app.platform.sharedkernel.audit.AuditRecord;
import app.platform.sharedkernel.audit.AuditRecorder;
import app.platform.tenant.TenantContext;
import app.platform.tenant.TenantContexts;
import app.platformapi.ApiException;
import app.platformapi.ErrorCode;
import app.platformapi.NewWebhookSecret;
import app.platformapi.SaveWebhookRequest;
import app.platformapi.WebhookAttemptView;
import app.platformapi.WebhookCatalogue;
import app.platformapi.WebhookDeliveryPage;
import app.platformapi.WebhookDeliveryView;
import app.platformapi.WebhookReplayView;
import app.platformapi.WebhookStateView;
import app.platformapi.WebhookView;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The webhook subscriptions of an organization: make, read, change, switch on and off, replace the signing secret,
 * remove, and read and replay the delivery log (Sprint 32, ADR-0274 to ADR-0279). Sending a test is
 * {@link WebhookTesting}, because a test must not hold a transaction while it waits for another system.
 *
 * <p>Every call decides who may ask first (membership, ability, plan feature: three separate mechanisms, never merged),
 * then what is allowed in the state the subscription is in; the same table is held by the database. A subscription acts
 * as the member who makes it, for ever: there is no way to name another member and none to change it, so authority
 * never grows. Nothing here logs, and the audit and the metrics carry identifiers, keys and counts only: never an
 * address, a name chosen by the organization or a secret.
 */
@Service
@Transactional
class WebhookSubscriptions {

    static final Pattern NAME = Pattern.compile("[a-z][a-z0-9-]{1,39}");
    private static final int PAGE_MAX = 200;
    private static final int REPLAY_BATCH = 500;
    private static final int END_BATCH = 2000;
    private static final String ACTING = "A webhook acts as the member who makes it, for ever. It tells its "
            + "receiver only about records that member can read at the moment of each delivery, and it stops when "
            + "that member can no longer act. Nobody can change whom it acts as; a webhook for another member is a "
            + "new webhook.";
    private static final String STOPPED = "This webhook acts as a member who can no longer act, so nothing is sent. "
            + "Nothing was deleted. Remove it and make a new one as a member who can act.";
    private static final String SECRET_ONCE = "The signing secret is shown once, when it is made. The platform "
            + "keeps it sealed so that it can sign, and cannot show it again: if it was not copied, replace it.";
    private static final String DUPLICATES = "The same delivery can reach a receiver more than once (a retry "
            + "after a lost answer, or an event handed over twice). Every delivery has a stable identifier in the "
            + "Webhook-Id header: keep the ones you have handled and ignore a repeat.";

    private static final List<WebhookStateView> STATES = List.of(
            new WebhookStateView("PENDING", "Waiting", "Waiting for its turn or for the next attempt."),
            new WebhookStateView("SENDING", "Sending", "An instance is sending it now."),
            new WebhookStateView("DONE", "Delivered", "The address accepted it."),
            new WebhookStateView("SKIPPED", "Not sent", "It was not sent, for the reason the log states."),
            new WebhookStateView("SUPERSEDED", "Replaced", "A later change of the same record replaced it."),
            new WebhookStateView("DEAD_LETTERED", "Set aside",
                    "It will not succeed by itself; an administrator may send it again."));

    private final WebhookSubscriptionStore store;
    private final WebhookDeliveryStore deliveries;
    private final WebhookAttemptStore attempts;
    private final WebhookSigner signer;
    private final WebhookViews views;
    private final WebhookVisibility visibility;
    private final IntegrationAccess access;
    private final EndpointRules.Policy policy;
    private final WebhookProperties properties;
    private final Metadata metadata;
    private final TenantContexts contexts;
    private final AuditRecorder audit;
    private final MeterRegistry meters;
    private final Clock clock;

    WebhookSubscriptions(WebhookSubscriptionStore store, WebhookDeliveryStore deliveries, WebhookAttemptStore attempts,
            WebhookSigner signer, WebhookViews views, WebhookVisibility visibility, IntegrationAccess access,
            EndpointRules.Policy policy, WebhookProperties properties, Metadata metadata, TenantContexts contexts,
            AuditRecorder audit, MeterRegistry meters, Clock clock) {
        this.store = store;
        this.deliveries = deliveries;
        this.attempts = attempts;
        this.signer = signer;
        this.views = views;
        this.visibility = visibility;
        this.access = access;
        this.policy = policy;
        this.properties = properties;
        this.metadata = metadata;
        this.contexts = contexts;
        this.audit = audit;
        this.meters = meters;
        this.clock = clock;
    }

    // ---- reading ----

    @Transactional(readOnly = true)
    WebhookCatalogue catalogue() {
        access.require(Ability.WEBHOOKS_VIEW);
        return new WebhookCatalogue(WebhookEvents.CATALOGUE, WebhookEvents.PAYLOAD_VERSION,
                (int) Duration.ofMinutes(5).toSeconds(), (int) properties.maxOverlap().toHours(),
                properties.maxSubscriptions(), 50, ACTING, STOPPED, SECRET_ONCE, DUPLICATES, STATES);
    }

    @Transactional(readOnly = true)
    List<WebhookView> list() {
        TenantContext caller = access.require(Ability.WEBHOOKS_VIEW);
        Instant now = clock.instant();
        return store.list().stream().map(one -> views.view(one, deliveries.counts(one.id()), caller.userId(), now))
                .toList();
    }

    @Transactional(readOnly = true)
    WebhookView get(UUID id) {
        TenantContext caller = access.require(Ability.WEBHOOKS_VIEW);
        return view(found(id), caller);
    }

    @Transactional(readOnly = true)
    WebhookDeliveryPage deliveriesOf(UUID id, List<String> states, UUID before, int limit) {
        access.require(Ability.WEBHOOKS_VIEW);
        found(id);
        List<String> wanted = new ArrayList<>();
        for (String state : states) {
            if (STATES.stream().noneMatch(known -> known.key().equals(state))) {
                throw ApiException.validation("state", "Choose a state from the list.");
            }
            wanted.add(state);
        }
        int size = Math.max(1, Math.min(PAGE_MAX, limit));
        List<WebhookDeliveryStore.Delivery> rows = deliveries.page(id, wanted, before, size);
        List<WebhookDeliveryView> items = rows.stream().map(WebhookViews::delivery).toList();
        return new WebhookDeliveryPage(items, rows.size() == size ? rows.get(rows.size() - 1).id() : null);
    }

    @Transactional(readOnly = true)
    List<WebhookAttemptView> attemptsOf(UUID id, UUID delivery) {
        access.require(Ability.WEBHOOKS_VIEW);
        found(id);
        deliveries.find(id, delivery).orElseThrow(() -> ApiException.notFound("This delivery does not exist."));
        return attempts.of(delivery, 200).stream().map(WebhookViews::attempt).toList();
    }

    // ---- making and changing ----

    NewWebhookSecret create(SaveWebhookRequest request) {
        TenantContext caller = access.require(Ability.WEBHOOKS_VIEW, Ability.WEBHOOKS_MANAGE);
        if (request.version() != 0) {
            throw ApiException.validation("version", "A new webhook starts at version 0.");
        }
        Checked checked = check(request);
        if (store.count() >= properties.maxSubscriptions()) {
            throw new ApiException(ErrorCode.CONFLICT, "The organization has reached its limit of webhooks.");
        }
        if (store.nameTaken(checked.name())) {
            throw new ApiException(ErrorCode.CONFLICT, "A webhook with this name already exists.");
        }
        UUID id = store.insert(checked.name(), checked.address(), checked.events(), checked.objects(),
                caller.membershipId(), caller.userId(), checked.fingerprint(),
                RateLimiter.capacity(properties.delivery().perMinute()), caller.userId());
        String secret = signer.generate();
        signer.seal(tenant(), id, 1, secret);
        changed("created", caller, id);
        return new NewWebhookSecret(view(found(id), caller), secret);
    }

    WebhookView update(UUID id, SaveWebhookRequest request) {
        TenantContext caller = access.require(Ability.WEBHOOKS_VIEW, Ability.WEBHOOKS_MANAGE);
        Subscription current = locked(id);
        if (!SubscriptionMachine.canChange(current.state())) {
            throw new ApiException(ErrorCode.CONFLICT, "Switch the webhook off before changing it.");
        }
        if (current.version() != request.version()) {
            throw new ApiException(ErrorCode.CONCURRENT_MODIFICATION);
        }
        Checked checked = check(request);
        if (!checked.name().equals(current.name()) && store.nameTaken(checked.name())) {
            throw new ApiException(ErrorCode.CONFLICT, "A webhook with this name already exists.");
        }
        if (!store.update(id, request.version(), checked.name(), checked.address(), checked.events(),
                checked.objects(), checked.fingerprint(), caller.userId())) {
            throw new ApiException(ErrorCode.CONCURRENT_MODIFICATION);
        }
        changed("updated", caller, id);
        return view(found(id), caller);
    }

    WebhookView enable(UUID id, long version) {
        TenantContext caller = access.require(Ability.WEBHOOKS_VIEW, Ability.WEBHOOKS_MANAGE);
        Subscription current = locked(id);
        if (current.version() != version) {
            throw new ApiException(ErrorCode.CONCURRENT_MODIFICATION);
        }
        Optional<SubscriptionMachine.State> next = SubscriptionMachine.next(current.state(), Event.ENABLE,
                current.verified());
        if (next.isEmpty()) {
            throw new ApiException(ErrorCode.CONFLICT, current.state() == SubscriptionMachine.State.DISABLED
                    ? "Send a test to the address first: a webhook is switched on only after its address answered."
                    : "The webhook is switched on already.");
        }
        if (!visibility.memberActive(current.userId())) {
            throw new ApiException(ErrorCode.CONFLICT,
                    "The member this webhook acts as can no longer act. Make a new webhook.");
        }
        if (!store.setState(id, version, next.get(), null, RateLimiter.capacity(properties.delivery().perMinute()),
                caller.userId())) {
            throw new ApiException(ErrorCode.CONCURRENT_MODIFICATION);
        }
        changed("enabled", caller, id);
        return view(found(id), caller);
    }

    WebhookView disable(UUID id) {
        TenantContext caller = access.require(Ability.WEBHOOKS_VIEW, Ability.WEBHOOKS_MANAGE);
        Subscription current = locked(id);
        Optional<SubscriptionMachine.State> next = SubscriptionMachine.next(current.state(), Event.DISABLE,
                current.verified());
        if (next.isPresent()) {
            store.setState(id, null, next.get(), SubscriptionMachine.disabledReason(Event.DISABLE), 0,
                    caller.userId());
            changed("disabled", caller, id);
        }
        return view(found(id), caller);
    }

    /**
     * Replaces the signing secret. A new secret becomes the current one at once; the old one keeps signing as well for
     * {@code overlapHours} so that a receiver can change over without losing a delivery, or ends at once with 0 (the
     * answer to a leaked secret). A secret still overlapping from an earlier replacement ends now: at most two sign.
     */
    NewWebhookSecret rotate(UUID id, int overlapHours) {
        TenantContext caller = access.require(Ability.WEBHOOKS_VIEW, Ability.WEBHOOKS_MANAGE);
        if (overlapHours < 0 || overlapHours > properties.maxOverlap().toHours()) {
            throw ApiException.validation("overlapHours", "Choose a number of hours from 0 to "
                    + properties.maxOverlap().toHours() + ".");
        }
        Subscription current = locked(id);
        int number = current.secretNumber() + 1;
        String secret = signer.generate();
        signer.seal(tenant(), id, number, secret);
        if (current.previousSecretNumber() != null) {
            signer.remove(tenant(), id, current.previousSecretNumber());
        }
        Integer previous = overlapHours > 0 ? current.secretNumber() : null;
        Instant until = overlapHours > 0 ? clock.instant().plus(Duration.ofHours(overlapHours)) : null;
        if (overlapHours == 0) {
            signer.remove(tenant(), id, current.secretNumber());
        }
        store.rotate(id, number, previous, until, caller.userId());
        audit.record(AuditRecord.of("webhooks.secret_rotated", AuditOutcome.SUCCESS).forUser(caller.userId())
                .inTenant(tenant()).with("subscription", id.toString())
                .with("overlap_hours", Integer.toString(overlapHours)));
        meters.counter("platform.webhook.secrets", "result", "rotated").increment();
        return new NewWebhookSecret(view(found(id), caller), secret);
    }

    /** Ends the overlap now: the earlier secret stops signing at once. */
    WebhookView endOverlap(UUID id) {
        TenantContext caller = access.require(Ability.WEBHOOKS_VIEW, Ability.WEBHOOKS_MANAGE);
        Subscription current = locked(id);
        if (current.previousSecretNumber() != null) {
            signer.remove(tenant(), id, current.previousSecretNumber());
            store.endPrevious(id);
            audit.record(AuditRecord.of("webhooks.secret_rotated", AuditOutcome.SUCCESS).forUser(caller.userId())
                    .inTenant(tenant()).with("subscription", id.toString()).with("overlap_hours", "0"));
        }
        return view(found(id), caller);
    }

    void remove(UUID id) {
        TenantContext caller = access.require(Ability.WEBHOOKS_VIEW, Ability.WEBHOOKS_MANAGE);
        Subscription current = locked(id);
        store.remove(id, caller.userId());
        int ended;
        do {
            ended = deliveries.endOpenOf(id, WebhookReason.SUBSCRIPTION_REMOVED.key(), END_BATCH);
        } while (ended >= END_BATCH);
        signer.remove(tenant(), id, current.secretNumber());
        if (current.previousSecretNumber() != null) {
            signer.remove(tenant(), id, current.previousSecretNumber());
        }
        changed("removed", caller, id);
    }

    // ---- replay ----

    /** Sends one dead letter again as a new generation; two clicks make one. */
    WebhookReplayView replay(UUID id, UUID delivery) {
        TenantContext caller = access.require(Ability.WEBHOOKS_VIEW, Ability.WEBHOOKS_REPLAY);
        found(id);
        WebhookDeliveryStore.Delivery found = deliveries.find(id, delivery)
                .orElseThrow(() -> ApiException.notFound("This delivery does not exist."));
        if (!"DEAD_LETTERED".equals(found.state())) {
            throw new ApiException(ErrorCode.CONFLICT, "Only a delivery that was set aside can be sent again.");
        }
        int replayed = deliveries.replay(delivery) ? 1 : 0;
        replayedAudit(caller, id, replayed);
        return new WebhookReplayView(replayed, false);
    }

    /** Sends the dead letters of the subscription again, oldest first, at most one batch a call. */
    WebhookReplayView replayAll(UUID id) {
        TenantContext caller = access.require(Ability.WEBHOOKS_VIEW, Ability.WEBHOOKS_REPLAY);
        found(id);
        List<UUID> ids = deliveries.deadLetterIds(id, REPLAY_BATCH + 1);
        int replayed = 0;
        for (UUID one : ids.stream().limit(REPLAY_BATCH).toList()) {
            if (deliveries.replay(one)) {
                replayed++;
            }
        }
        replayedAudit(caller, id, replayed);
        return new WebhookReplayView(replayed, ids.size() > REPLAY_BATCH);
    }

    private void replayedAudit(TenantContext caller, UUID id, int replayed) {
        meters.counter("platform.webhook.replayed").increment(replayed);
        audit.record(AuditRecord.of("webhooks.replayed", AuditOutcome.SUCCESS).forUser(caller.userId())
                .inTenant(tenant()).with("subscription", id.toString()).with("count", Integer.toString(replayed)));
    }

    // ---- checking a definition ----

    /** A definition that passed every check, in the form that is stored. */
    private record Checked(String name, String address, List<String> events, List<String> objects,
            String fingerprint) {
    }

    private Checked check(SaveWebhookRequest request) {
        String name = request.name() == null ? "" : request.name().strip();
        if (!NAME.matcher(name).matches()) {
            throw ApiException.validation("name", "Use 2 to 40 lower case letters, digits and dashes, starting with "
                    + "a letter.");
        }
        EndpointRules.Result address = EndpointRules.parse(request.address() == null ? "" : request.address().strip(),
                policy);
        if (!address.ok()) {
            throw ApiException.validation("address", address.refusal().words());
        }
        Set<String> wanted = new LinkedHashSet<>(request.events());
        if (wanted.isEmpty() || !WebhookEvents.keys().containsAll(wanted)) {
            throw ApiException.validation("events", "Choose one or more events from the list.");
        }
        List<String> events = WebhookEvents.CATALOGUE.stream().map(event -> event.key()).filter(wanted::contains)
                .toList();
        List<String> objects = null;
        if (request.objects() != null) {
            Set<String> names = new LinkedHashSet<>(request.objects());
            if (names.isEmpty() || names.size() > 50) {
                throw ApiException.validation("objects", "Choose up to 50 objects, or leave the list out.");
            }
            for (String object : names) {
                if (metadata.object(object).isEmpty()) {
                    throw ApiException.validation("objects", "An object in the list does not exist.");
                }
            }
            objects = List.copyOf(names);
        }
        String written = request.address().strip();
        return new Checked(name, written, events, objects, fingerprintOf(written, events, objects));
    }

    /** The fingerprint of what a verification vouches for: the address, the events and the objects. */
    static String fingerprintOf(String address, List<String> events, List<String> objects) {
        StringBuilder text = new StringBuilder();
        part(text, address);
        part(text, String.valueOf(events.size()));
        events.forEach(event -> part(text, event));
        part(text, objects == null ? "-" : String.valueOf(objects.size()));
        if (objects != null) {
            objects.forEach(object -> part(text, object));
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is part of every Java runtime", impossible);
        }
    }

    private static void part(StringBuilder text, String value) {
        text.append(value.length()).append(':').append(value).append(';');
    }

    // ---- helpers ----

    private Subscription found(UUID id) {
        return store.find(id).orElseThrow(() -> ApiException.notFound("This webhook does not exist."));
    }

    private Subscription locked(UUID id) {
        return store.lock(id).orElseThrow(() -> ApiException.notFound("This webhook does not exist."));
    }

    private WebhookView view(Subscription subscription, TenantContext caller) {
        return views.view(subscription, deliveries.counts(subscription.id()), caller.userId(), clock.instant());
    }

    private TenantId tenant() {
        return contexts.require().tenantId();
    }

    private void changed(String what, TenantContext caller, UUID id) {
        audit.record(AuditRecord.of("webhooks." + what, AuditOutcome.SUCCESS).forUser(caller.userId())
                .inTenant(tenant()).with("subscription", id.toString()));
        meters.counter("platform.webhook.subscriptions", "result", what).increment();
    }
}
