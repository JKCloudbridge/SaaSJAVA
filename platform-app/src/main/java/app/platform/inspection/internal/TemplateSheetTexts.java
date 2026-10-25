package app.platform.inspection.internal;

import app.platform.sheets.Revisions;

/**
 * The two small texts a template's sheet carries when it is shown, printed and downloaded (FA7, ADR-0201): the footer
 * row and the name of the file. Pure: no record, no table, no text of an organization except the number it is handed.
 * The footer is written the same way in the browser ({@code lib/sheets/revision.ts}) with the same cases.
 */
final class TemplateSheetTexts {

    private TemplateSheetTexts() {
    }

    /**
     * The text at the right of the footer row of a sheet: the template number, two spaces, {@code Rev No.} and the
     * revision in single quotes, for example {@code F/MR/16  Rev No.'01'}. Blank until the template has a number.
     */
    static String footer(String number, int stored) {
        if (number == null || number.isBlank()) {
            return "";
        }
        return number.strip() + "  Rev No.'" + Revisions.show(stored) + "'";
    }

    /**
     * The name of the file a sheet is downloaded as: the number and the revision, a slash of the number becoming a
     * hyphen
     * (for example {@code F-MR-16_Rev00.xlsx}). Anything else a file name should not hold becomes a hyphen too, so the
     * name can never carry a path or a header break. A template with no number is called {@code Template}.
     */
    static String fileName(String number, int stored) {
        String base = number == null || number.isBlank() ? "Template"
                : number.strip().replaceAll("[^A-Za-z0-9._-]", "-");
        return base + "_Rev" + Revisions.show(stored) + ".xlsx";
    }
}
