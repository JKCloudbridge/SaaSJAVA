package app.platformapi;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * Makes a new secret for a connected application and ends the older ones (Sprint 31, ADR-0268). By default the older
 * secrets stop working at once.
 *
 * @param overlapHours how long the older secrets keep working so that the software can be changed over, 0 to 24;
 *        absent or 0 ends them now
 * @param endTokensNow whether the tokens already issued with the older secrets stop now as well; by default they live
 *        until they end (they are short)
 */
public record RotateClientSecretRequest(@Min(0) @Max(24) Integer overlapHours, boolean endTokensNow) {
}
