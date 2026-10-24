package app.platform.sheets.internal;

import app.platform.sheets.FillLifecycle;
import app.platform.sheets.FillSubmission;
import app.platform.sheets.internal.FillOwners.Reach;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The member's own send of a fill-in with no review behind it (FA6A, ADR-0196): who may is {@link FillOwners}, the move
 * and its checks are {@link FillLifecycle#submit} with no request, so a fill-in sent this way meets exactly the checks
 * of one sent for review (required values, at least one row where the sheet needs it, the person who started it).
 */
@Service
@Transactional
class DefaultFillSubmission implements FillSubmission {

    private final FillOwners owners;
    private final FillLifecycle lifecycle;

    DefaultFillSubmission(FillOwners owners, FillLifecycle lifecycle) {
        this.owners = owners;
        this.lifecycle = lifecycle;
    }

    @Override
    public void submitWithoutReview(String fillObject, UUID fillId) {
        Reach reach = owners.reachForChange(fillObject, fillId);
        lifecycle.submit(fillObject, fillId, null, reach.userId());
    }
}
