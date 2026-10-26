package app.platform.inspection.internal;

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
     * revision in single quotes, for example {@code F/MR/16  Rev No.'01'}. Blank until the template has a number. The
     * revision is the number people read (0 for the first), which the database gave the version when it was first in
     * use (FA8).
     */
    static String footer(String number, int revision) {
        if (number == null || number.isBlank()) {
            return "";
        }
        return number.strip() + "  Rev No.'" + TemplateRevisions.show(revision) + "'";
    }

    /**
     * The name of the file a sheet is downloaded as: the number and the revision, a slash of the number becoming a
     * hyphen
     * (for example {@code F-MR-16_Rev00.xlsx}). Anything else a file name should not hold becomes a hyphen too, so the
     * name can never carry a path or a header break. A template with no number is called {@code Template}.
     */
    static String fileName(String number, int revision) {
        String base = number == null || number.isBlank() ? "Template"
                : number.strip().replaceAll("[^A-Za-z0-9._-]", "-");
        return base + "_Rev" + TemplateRevisions.show(revision) + ".xlsx";
    }
}
