package app.platform.sheets.internal;

import app.platform.sheets.SheetPages;
import app.platform.sheets.SheetPages.PrintSettings;

/**
 * How a sheet is set up for paper (FA10, ADR-0204; SH3, ADR-0253): the one description the Excel file is written from
 * and every print view on the screen is drawn from, so the two can never disagree.
 *
 * <p>A sheet in format version 2 may state its paper (A4, A3 or LETTER), its orientation and whether it is fitted to
 * the width of the page; what it states wins. Everything else, and every sheet in format version 1, is A4 fitted to the
 * width of the page, portrait when the columns together are no wider than {@link #PORTRAIT_WIDTH} pixels and landscape
 * when they are. The margins are the same for all. Pure: nothing here reads a record.
 */
final class PrintRules {

    /** Wider than this (pixels, all columns together) the page is landscape: A4 portrait holds about this much. */
    static final int PORTRAIT_WIDTH = 720;
    private static final int DEFAULT_COLUMN_WIDTH = 120;
    /** The page margins in inches: left and right, then top and bottom. */
    static final double MARGIN_SIDE = 0.5;
    static final double MARGIN_TOP = 0.6;

    private PrintRules() {
    }

    /** The page of a sheet as it is written and as it is shown. */
    record Page(String paper, boolean landscape, boolean fitToWidth) {
    }

    /** The orientation a sheet of this width is printed in when it states none. */
    static boolean wide(SheetGrid grid) {
        int width = 0;
        for (SheetGrid.Column column : grid.columns()) {
            width += column.width() == null ? DEFAULT_COLUMN_WIDTH : column.width();
        }
        return width > PORTRAIT_WIDTH;
    }

    /**
     * The page of a sheet.
     *
     * @param grid the body of the sheet
     * @param stated what a sheet of format version 2 states, or null
     */
    static Page of(SheetGrid grid, SheetDocument.Print stated) {
        if (stated == null) {
            return new Page("A4", wide(grid), true);
        }
        return new Page(stated.paper(), "LANDSCAPE".equals(stated.orientation()), stated.fitToWidth());
    }

    /** The description of the page in the words and units a print view draws. */
    static PrintSettings settings(SheetGrid grid, SheetDocument.Print stated) {
        Page page = of(grid, stated);
        int[] size = millimetres(page.paper());
        int width = page.landscape() ? size[1] : size[0];
        int height = page.landscape() ? size[0] : size[1];
        return new SheetPages.PrintSettings(page.paper(), page.landscape() ? "landscape" : "portrait", width, height,
                MARGIN_SIDE, MARGIN_SIDE, MARGIN_TOP, MARGIN_TOP);
    }

    /** The description of the page of a sheet in format version 1. */
    static PrintSettings settings(SheetGrid grid) {
        return settings(grid, null);
    }

    /**
     * The description of the page of a stored document of either format: the first sheet of a document of format
     * version 2 (what it states wins), or a grid of format version 1.
     */
    static PrintSettings settings(tools.jackson.databind.JsonNode stored) {
        if (!SheetGridValidator.isFormatTwo(stored)) {
            return settings(SheetGridReader.read(stored));
        }
        tools.jackson.databind.JsonNode sheet = stored.path("sheets").get(0);
        tools.jackson.databind.JsonNode print = sheet.path("print");
        SheetDocument.Print stated = print.isObject() ? new SheetDocument.Print(print.path("paper").asString(),
                print.path("orientation").asString(), print.path("fitToWidth").asBoolean(),
                print.path("repeatHeaderRows").asBoolean()) : null;
        return settings(SheetGridReader.readBody(sheet), stated);
    }

    /** The short and the long side of a paper size, in millimetres. */
    private static int[] millimetres(String paper) {
        return switch (paper) {
            case "A3" -> new int[] {297, 420};
            case "LETTER" -> new int[] {216, 279};
            default -> new int[] {210, 297};
        };
    }
}
