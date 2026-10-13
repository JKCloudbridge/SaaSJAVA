package app.platform.tenant;

import jakarta.servlet.Filter;

/**
 * The limits one organization is held to so that it cannot take a shared database away from the others (Sprint 16,
 * ADR-0108). Two limits, both per organization:
 *
 * <ul>
 *   <li><strong>Requests in progress.</strong> At most a configured number of API requests of one organization run at
 *       the same time on an instance. A request over the number waits a moment (and only a few may wait), then is
 *       refused with 429 and a {@code Retry-After} header; nothing queues without bound and no request thread is held
 *       for long. Platform addresses and the probes are not limited.</li>
 *   <li><strong>Statement time.</strong> Every transaction of an organization carries a time limit for the statements
 *       it runs, set in the database for the transaction (so it holds on every instance, whoever sends the
 *       statement): a short one for reading, a longer one for changing.</li>
 * </ul>
 *
 * <p>The numbers are settings with an override per organization (a plan decides them later). Metrics count refusals
 * and waits without naming an organization; the log says that an organization was limited, never what it asked.
 */
public interface OrganizationLimits {

    /**
     * The servlet filter that enforces the first limit. It belongs in the security chain after the caller is known (a
     * stranger cannot use up an organization's turns). It is not a bean of its own: the caller adds it to the chain
     * once.
     */
    Filter requestFilter();
}
