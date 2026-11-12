package app.platform.sheets.internal;

/**
 * Sample workbooks that have the structure of a real paper-style form (FA14, ADR-0250), made in memory so that
 * nothing is stored. The form: a title merged across the whole width, a header row, a numbered column, a label
 * column and a wide answer column, tall rows, five bordered blank rows under "Possible cause", a merged answer area
 * of three columns and five rows, and blank cells <em>outside</em> the table that carry no border. The texts are
 * made up.
 */
final class SampleForms {

    static final String TITLE = "ANALYSIS AND ACTION PLAN FORM";

    /** The table: columns A to C, rows 1 to 15. */
    static final int COLUMNS = 3;
    static final int ROWS = 15;
    /** The cells that hold text. */
    static final int TEXT_CELLS = 10;
    /** The bordered cells that hold no text and are not under a merged area (the anchor of a merge counts as one). */
    static final int BORDERED_BLANKS = 19;

    private SampleForms() {
    }

    /** The form with the borders it was drawn with. */
    static byte[] analysisForm() {
        return analysisForm(true);
    }

    /**
     * The form, with or without its borders. Without them the file has the same texts, merges, widths and heights and
     * its styles part has no list of borders, so only the text decides how far the table reaches.
     */
    static byte[] analysisForm(boolean borders) {
        XlsxFile file = new XlsxFile();
        if (!borders) {
            file.styles(XlsxFile.STYLES_WITHOUT_BORDERS);
        }
        file.widths(1, 1, 6).widths(2, 2, 22).widths(3, 3, 60);
        // The title is one merged area; the cells under it are written with the border of the box.
        file.row(1, 45, XlsxFile.text("A1", TITLE, XlsxFile.TITLE_BOX), XlsxFile.boxed("B1"), XlsxFile.boxed("C1"));
        file.row(2, XlsxFile.boxedText("A2", "No."), XlsxFile.boxedText("B2", "Item"),
                XlsxFile.boxedText("C2", "Answer"));
        file.row(3, 40, XlsxFile.boxedText("A3", "1"), XlsxFile.boxedText("B3", "Problem statement"),
                XlsxFile.boxed("C3"), XlsxFile.blank("E3"));
        file.row(4, 40, XlsxFile.boxedText("A4", "2"), XlsxFile.boxedText("B4", "Possible cause"),
                XlsxFile.boxed("C4"));
        for (int row = 5; row <= 9; row++) {
            file.row(row, 30, XlsxFile.boxed("A" + row), XlsxFile.boxed("B" + row), XlsxFile.boxed("C" + row));
        }
        file.row(10, 40, XlsxFile.boxedText("A10", "3"), XlsxFile.boxedText("B10", "Action"),
                XlsxFile.boxed("C10"), XlsxFile.blank("E10"));
        for (int row = 11; row <= 15; row++) {
            file.row(row, XlsxFile.boxed("A" + row), XlsxFile.boxed("B" + row), XlsxFile.boxed("C" + row));
        }
        // Outside the table: cells with a style and no border, and one whose border names the style "none".
        file.row(18, XlsxFile.noBorder("C18"), XlsxFile.strayBorder("D18"));
        file.row(20, "<c r=\"A20\" s=\"0\"/>", "<c r=\"B20\" s=\"0\"/>", "<c r=\"F20\" s=\"0\"/>");
        return file.merge("A1:C1").merge("A11:C15").bytes();
    }
}
