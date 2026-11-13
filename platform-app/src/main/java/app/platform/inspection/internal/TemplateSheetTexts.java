package app.platform.inspection.internal;

/**
 * The two small texts a template's sheet carries when it is shown, printed and downloaded (FA7, ADR-0201): the footer
 * row and the name of the file. Pure: no record, no table, no text of an organization except the number it is handed.
 * The footer is written the same way in the browser ({@code lib/sheets/revision.ts}) with the same cases.
 */
final class TemplateSheetTexts {

    private static final String DATE_LABEL = "Date: ";
    private static final java.time.format.DateTimeFormatter DAY = java.time.format.DateTimeFormatter
            .ofPattern("dd-MMM-yyyy", java.util.Locale.ENGLISH);

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
     * The footer of an application whose footer carries the day (MC1, ADR-0257): the first line is that of
     * {@link #footer(String, int)}, the second, below it, is {@code Date: 13-Nov-2026}. Blank until the template has
     * a number; without a day (the Revision is not the one in use, or none was ever published) it is the first line
     * alone.
     * Written the same way in the browser ({@code lib/sheets/revision.ts}) with the cases of
     * {@code footer-cases.txt}.
     */
    static String footer(String number, int revision, java.time.LocalDate day) {
        String first = footer(number, revision);
        if (first.isEmpty() || day == null) {
            return first;
        }
        return first + "\n" + DATE_LABEL + day(day);
    }

    /** The day as the footer writes it: two digits, the English month in three letters, the year. */
    static String day(java.time.LocalDate day) {
        return DAY.format(day);
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
