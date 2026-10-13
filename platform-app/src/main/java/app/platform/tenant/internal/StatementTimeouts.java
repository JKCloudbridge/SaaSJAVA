package app.platform.tenant.internal;

import java.time.Duration;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The time limit the database applies to each statement of an organization's transaction (Sprint 16, ADR-0108): a
 * short one when the transaction only reads, a longer one when it changes something, each with an override per
 * organization. Held apart from the request filter because it is needed when a transaction starts, which is below the
 * web layer: background work (filling an index, emptying the recycle bin) runs under the same limits.
 */
@Component
class StatementTimeouts {

    private final OrganizationLimitProperties settings;

    StatementTimeouts(OrganizationLimitProperties settings) {
        this.settings = settings;
    }

    /** Whether the time limit is applied at all. */
    boolean enabled() {
        return settings.enabled();
    }

    /**
     * The limit for the statements of a transaction.
     *
     * @param tenant the organization
     * @param readOnly whether the transaction only reads
     */
    Duration of(UUID tenant, boolean readOnly) {
        OrganizationLimitProperties.Custom custom = settings.overrides().get(tenant.toString());
        if (readOnly) {
            return custom != null && custom.queryTimeout() != null ? custom.queryTimeout() : settings.queryTimeout();
        }
        return custom != null && custom.writeTimeout() != null ? custom.writeTimeout() : settings.writeTimeout();
    }
}
