package app.platformapi;

import jakarta.validation.constraints.NotNull;

/**
 * How a sheet is set up for paper (SH3, ADR-0253): the one description the Excel file of the sheet is written from and
 * the print view on the screen is drawn from, so the two can never disagree. A sheet that states its paper, its
 * orientation or whether it is fitted to the width of the page is described as it states; every other sheet is A4,
 * portrait when its columns are no wider than a page holds and landscape when they are.
 *
 * @param paper the paper size: {@code A4}, {@code A3} or {@code LETTER}
 * @param orientation {@code portrait} or {@code landscape}
 * @param pageWidthMm the width of the paper as it is turned, in millimetres
 * @param pageHeightMm the height of the paper as it is turned, in millimetres
 * @param marginLeft the left margin in inches
 * @param marginRight the right margin in inches
 * @param marginTop the top margin in inches
 * @param marginBottom the bottom margin in inches
 */
public record SheetPrintView(@NotNull String paper, @NotNull String orientation, @NotNull Integer pageWidthMm,
        @NotNull Integer pageHeightMm, @NotNull Double marginLeft, @NotNull Double marginRight,
        @NotNull Double marginTop, @NotNull Double marginBottom) {
}
