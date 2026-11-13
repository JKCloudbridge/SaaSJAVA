package app.platformapi;

import jakarta.validation.constraints.NotNull;

/**
 * One function a sheet formula may call (SH2, ADR-0252), as the server's own table lists it.
 *
 * @param name the name in capitals
 * @param least the fewest arguments
 * @param most the most arguments
 */
public record SheetFormulaFunctionView(@NotNull String name, @NotNull Integer least, @NotNull Integer most) {
}
