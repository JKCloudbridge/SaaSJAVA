package app.platform.sheets;

import java.util.UUID;

/**
 * Sending a fill-in that no review follows (FA6A, ADR-0196), for the module that owns the header record. It is the
 * member's own act: the person behind the request, who must be the one who started the fill-in and may read and change
 * its header record, sends it; the cells are checked as they are for a review and the status becomes {@code SUBMITTED}
 * for good, with no request behind it. Who may do so is the answer of the decision API for the header, asked in the one
 * place that asks it for the cells ({@code FillOwners}); there is no second rule.
 *
 * <p>Nothing here knows whether an approval process exists: the caller asks that first and calls this only when none
 * applies, so the way without a review is never a way round a process. Must be called inside the organization's own
 * tenant context and the caller's transaction.
 */
public interface FillSubmission {

    /**
     * Sends the fill-in of the member behind the request.
     *
     * @throws app.platformapi.ApiException {@code NOT_FOUND} for a header the member cannot read, {@code FORBIDDEN} for
     *         one they may read but not change
     * @throws FillRefusal {@code NOT_FILLABLE}, {@code NOT_THE_FILLER}, {@code NO_ROWS}, {@code INCOMPLETE} (with the
     *         place), {@code INVALID}, {@code VERSION_RETIRED}, {@code NO_FILL}
     */
    void submitWithoutReview(String fillObject, UUID fillId);
}
