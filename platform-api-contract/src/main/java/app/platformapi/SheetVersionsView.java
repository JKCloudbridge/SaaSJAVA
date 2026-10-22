package app.platformapi;

import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * The versions of one sheet the caller may see, newest first, without their grids (Functional track FA3, ADR-0186).
 *
 * <p>A caller who may only read the record the sheet belongs to sees the versions that are in use or were (active,
 * replaced, retired); a caller who may change the record sees every version. The server decides this again on every
 * request.
 *
 * @param versions the versions, highest number first
 * @param canChange whether the caller may start and change drafts: a hint for the screen, which offers the buttons
 *        only when it is true; every request that follows is decided again
 * @param limits what a grid may hold, so that the screen can say so before the person tries
 */
public record SheetVersionsView(@NotNull List<SheetVersionSummaryView> versions, @NotNull Boolean canChange,
        @NotNull SheetLimitsView limits) {

    /** Copies the list, so that it cannot be changed from outside. */
    public SheetVersionsView {
        versions = versions == null ? null : List.copyOf(versions);
    }
}
