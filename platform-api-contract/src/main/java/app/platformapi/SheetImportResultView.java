package app.platformapi;

import jakarta.validation.constraints.NotNull;

/**
 * The answer to the upload of a spreadsheet file into a sheet (UI3B, ADR-0195).
 *
 * @param saved true when a new draft was made from the file, false for a preview that saved nothing
 * @param version the new draft, absent for a preview
 * @param report what the file gave and what was left out
 */
public record SheetImportResultView(@NotNull Boolean saved, SheetVersionSummaryView version,
        @NotNull SheetImportReportView report) {
}
