package app.platformapi;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/**
 * Starts a new draft version of a sheet (Functional track FA3, ADR-0186).
 *
 * <p>The first version of a sheet is blank and names its shape: {@code dimension}, and optionally {@code rowsMode}.
 * Every later version is a copy of another one and keeps that shape; naming a different shape is refused. The shape of
 * a sheet is chosen once and never changes after its first version has left draft (a first version that is still the
 * only one and still a draft may be saved with another shape).
 *
 * @param basedOn the number of the version to copy; left out, the active version is copied, or the latest when none is
 *        active; for the first version there is nothing to copy
 * @param dimension 1 (the filler adds the rows) or 2 (the sheet names the rows); needed for the first version only
 * @param rowsMode {@code FIXED}, {@code REPEATABLE} or {@code FIXED_AND_REPEATABLE}; left out, 1 gives
 *        {@code REPEATABLE} and 2 gives {@code FIXED}
 */
public record NewSheetVersionRequest(@Min(1) Integer basedOn, @Min(1) @Max(2) Integer dimension,
        @Size(max = 30) String rowsMode) {
}
