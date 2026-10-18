package app.platform.data.internal;

import app.platform.sharedkernel.TenantId;
import org.springframework.stereotype.Component;

/**
 * Lets a test outside this package run the index and uniqueness work of an organization at once, instead of waiting for
 * the timed sweep. Tests only: nothing in the application uses it.
 */
@Component
public class BackfillDriver {

    private final IndexBackfill backfill;

    BackfillDriver(IndexBackfill backfill) {
        this.backfill = backfill;
    }

    /** One pass of the organization's pending index and uniqueness work. */
    public int workOn(TenantId tenant) {
        return backfill.workOn(tenant);
    }
}
