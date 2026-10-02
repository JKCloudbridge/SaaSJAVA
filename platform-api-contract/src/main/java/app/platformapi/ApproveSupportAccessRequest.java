package app.platformapi;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * An administrator of the organization approves a request.
 *
 * @param minutes how long, 15 to 240 and not more than was asked; absent means as asked
 */
public record ApproveSupportAccessRequest(@Min(15) @Max(240) Integer minutes) {
}
