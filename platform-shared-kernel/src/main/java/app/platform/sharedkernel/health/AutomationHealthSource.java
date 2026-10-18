package app.platform.sharedkernel.health;

import java.util.List;

/**
 * A module's part of the health of automation (Sprint 25, ADR-0163): the counts of what is waiting or has failed. The
 * pattern of {@code ParkedEvents}: the module that owns the tables implements the contract, and whoever draws the
 * failure view collects every implementation without depending on any of the modules.
 *
 * <p>Both methods are counts only. {@link #organization()} answers for the organization of the thread's tenant
 * context, in the caller's transaction, under its row level security. {@link #platform()} answers across organizations
 * without a tenant context and without opening a system scope: it reports what the instance last measured for its own
 * gauges (the dispatcher, the timer), or reads a platform-level table, so asking for it costs nothing the instance did
 * not already pay.
 */
public interface AutomationHealthSource {

    /** The sections about the current organization; empty when this module has none. */
    default List<HealthSection> organization() {
        return List.of();
    }

    /** The sections across organizations, without any name or value; empty when this module has none. */
    default List<HealthSection> platform() {
        return List.of();
    }
}
