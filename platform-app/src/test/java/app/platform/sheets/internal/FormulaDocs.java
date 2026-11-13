package app.platform.sheets.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.sheets.internal.SheetDocument.Tab;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Documents of format version 2 with formulas for the tests of SH2, as plain maps so that a test can change one thing
 * at a time, and the validator that makes them into a checked sheet.
 */
final class FormulaDocs {

    static final SheetGridValidator VALIDATOR = new SheetGridValidator(SheetProperties.Limits.standard(),
            new SheetSchemas(), true);

    private FormulaDocs() {
    }

    /** A sheet that names its rows: a header row h1, three rows r1 to r3, and two number columns c1 and c2. */
    static Map<String, Object> named() {
        Map<String, Object> document = TwoDocs.wrap(GridDocs.map("formatVersion", 1, "dimension", 2,
                "rowsMode", "FIXED",
                "columns", new ArrayList<>(List.of(GridDocs.column("c1", "Quantity", "number"),
                        GridDocs.column("c2", "Price", "number"))),
                "headerRows", new ArrayList<>(List.of(GridDocs.map("id", "h1"))),
                "rows", new ArrayList<>(List.of(GridDocs.map("id", "r1"), GridDocs.map("id", "r2"),
                        GridDocs.map("id", "r3"))),
                "cells", GridDocs.map(), "merges", new ArrayList<>()));
        return document;
    }

    /** A sheet where the filler adds all the rows: a header row h1 and two number columns c1 and c2. */
    static Map<String, Object> repeating() {
        return TwoDocs.wrap(GridDocs.map("formatVersion", 1, "dimension", 1, "rowsMode", "REPEATABLE",
                "columns", new ArrayList<>(List.of(GridDocs.column("c1", "Quantity", "number"),
                        GridDocs.column("c2", "Price", "number"))),
                "headerRows", new ArrayList<>(List.of(GridDocs.map("id", "h1"))),
                "rows", new ArrayList<>(), "cells", GridDocs.map(), "merges", new ArrayList<>()));
    }

    /** Puts the stored form of a formula on a cell of the first sheet. */
    static Map<String, Object> withCell(Map<String, Object> document, String cell, String formula) {
        TwoDocs.cells(document).put(cell, GridDocs.map("formula", formula));
        return document;
    }

    /** Puts the stored form of a formula on a column of the first sheet. */
    static Map<String, Object> withColumn(Map<String, Object> document, String column, String formula) {
        for (Map<String, Object> one : GridDocs.list(TwoDocs.sheet(document), "columns")) {
            if (column.equals(one.get("id"))) {
                one.put("formula", formula);
            }
        }
        return document;
    }

    /** The checked sheet of a document that must be accepted. */
    static Tab accepted(Map<String, Object> document) {
        SheetGridValidator.Result result = VALIDATOR.check(GridDocs.bytes(document));
        assertThat(result.problems()).isEmpty();
        return result.checked().document().sheets().get(0);
    }

    /** The code of the first problem of a document that must be refused. */
    static String refusedWith(Map<String, Object> document) {
        SheetGridValidator.Result result = VALIDATOR.check(GridDocs.bytes(document));
        assertThat(result.accepted()).as("accepted").isFalse();
        return result.problems().get(0).code();
    }
}
