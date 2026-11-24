package app.platform.identity.internal;

import app.platform.identity.internal.RateLimitMath.Item;
import app.platform.licensing.Entitlements;
import app.platform.security.Ability;
import app.platform.sharedkernel.limits.RateLimitSettings.Limits;
import app.platform.tenant.TenantContexts;
import app.platformapi.ApiException;
import app.platformapi.ApplicationUsageView;
import app.platformapi.ErrorCode;
import app.platformapi.LimitUsageView;
import app.platformapi.UsageView;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * What the organization may ask of the public API and what it has used (Sprint 32, ADR-0280): the organization's own
 * limits, and one limit for each connected application. Numbers only, read from the counters without adding anything,
 * so looking at the page never costs a request against the limits and never reveals another organization. The caller
 * needs {@code usage.view} and the organization needs the plan feature of the integrations (three separate mechanisms).
 */
@Service
class UsageService {

    private static final String FEATURE = "integrations";
    private static final String WORDS = "Every request to the public API counts as one; a bulk call counts as many as "
            + "it has records. Each connected application and each person's own token has a limit a minute, and the "
            + "whole organization has a limit a minute and a limit a day that ends at midnight UTC. Every answer says "
            + "what is left in the headers RateLimit-Policy and RateLimit. When a limit is reached the answer is 429 "
            + "with Retry-After: wait that many seconds, and spread bulk calls out.";

    private final Administration administration;
    private final Entitlements entitlements;
    private final RateLimits limits;
    private final LimitOverrides overrides;
    private final RateLimitProperties properties;
    private final ClientStore clients;
    private final TenantContexts contexts;
    private final Clock clock;

    UsageService(Administration administration, Entitlements entitlements, RateLimits limits,
            LimitOverrides overrides, RateLimitProperties properties, ClientStore clients, TenantContexts contexts,
            Clock clock) {
        this.administration = administration;
        this.entitlements = entitlements;
        this.limits = limits;
        this.overrides = overrides;
        this.properties = properties;
        this.clients = clients;
        this.contexts = contexts;
        this.clock = clock;
    }

    UsageView usage() {
        return administration.run("usage.view", Ability.USAGE_VIEW, own -> {
            if (!entitlements.enabled(FEATURE)) {
                throw new ApiException(ErrorCode.CONFLICT, "API usage is not part of this organization's plan.");
            }
            UUID tenant = contexts.require().tenantId().value();
            Limits effective = overrides.effective();
            List<LimitUsageView> organization = new ArrayList<>();
            organization.add(view(limits.organizationMinute(tenant, effective),
                    "Requests a minute, whole organization", effective.overridden()));
            organization.add(view(limits.organizationDay(tenant, effective),
                    "Requests a day, whole organization", effective.overridden()));
            List<ApplicationUsageView> applications = new ArrayList<>();
            for (ClientStore.ClientRow client : clients.list()) {
                Item minute = limits.callerMinute(new RateLimits.Caller(tenant, true, client.id()), effective);
                applications.add(new ApplicationUsageView(client.name(),
                        List.of(view(minute, "Requests a minute", effective.overridden()))));
            }
            return new UsageView(properties.enabled(), organization, applications, WORDS);
        });
    }

    private LimitUsageView view(Item item, String title, boolean overridden) {
        long used = Math.max(0, item.quota() - item.remaining());
        Instant resetsAt = used == 0 ? null : clock.instant().plusSeconds(item.resetSeconds());
        return new LimitUsageView(item.name(), title, item.quota(), used, item.remaining(), resetsAt, overridden);
    }
}
