package app.platform.sheets.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.sheets.internal.RoundTripSupport.Snapshot;
import app.platform.sheets.internal.RoundTripSupport.Trip;
import app.platform.sheets.internal.SheetGrid.RowsMode;
import org.junit.jupiter.api.Test;

/**
 * A sheet in format version 2 that is downloaded and uploaded again gives the same look, the same formulas (the same
 * text), the same merged areas, widths and conditional colours (SH3, ADR-0253), with no database. The workbooks are
 * built by the tests; the two real workbooks are checked by {@code RealWorkbooksWriteReportTest}, which writes counts.
 */
class WriterRoundTripTest {

    private static void same(Trip trip) {
        Snapshot before = trip.before();
        Snapshot after = trip.after();

        assertThat(after.columns()).as("column labels").isEqualTo(before.columns());
        assertThat(after.texts()).as("fixed text by position").isEqualTo(before.texts());
        assertThat(after.looks()).as("looks by position").isEqualTo(before.looks());
        assertThat(after.merges()).as("merged areas").containsExactlyInAnyOrderElementsOf(before.merges());
        assertThat(after.widths()).as("column widths").hasSameSizeAs(before.widths());
        for (int i = 0; i < before.widths().size(); i++) {
            assertThat(Integer.parseInt(after.widths().get(i))).as("width of column " + i)
                    .isBetween(Integer.parseInt(before.widths().get(i)) - 6,
                            Integer.parseInt(before.widths().get(i)) + 6);
        }
        assertThat(after.formulas()).as("formulas as spreadsheet text").isEqualTo(before.formulas());
        assertThat(after.conditions()).as("conditional colours").isEqualTo(before.conditions());
        assertThat(after.freeze()).as("frozen pane").isEqualTo(before.freeze());
        if (before.print() != null) {
            assertThat(after.print()).as("page set-up").isEqualTo(before.print());
        }
        assertThat(after.links()).isEqualTo(before.links());
    }

    @Test
    void theSampleFormWithItsBordersComesBackWithTheSameTextLookAndMerges() {
        Trip trip = RoundTripSupport.trip(SampleForms.analysisForm(true), 2, RowsMode.FIXED, null);

        same(trip);
        assertThat(trip.before().texts()).isNotEmpty();
    }

    @Test
    void theSampleFormWithoutBordersComesBackTheSame() {
        same(RoundTripSupport.trip(SampleForms.analysisForm(false), 2, RowsMode.FIXED, null));
    }

    @Test
    void aWorkbookWithFormulasAndMergesComesBackWithTheSameFormulasAndMerges() {
        byte[] file = new XlsxFile()
                .row(1, XlsxFile.text("A1", "Item"), XlsxFile.text("B1", "Qty"), XlsxFile.text("C1", "Price"),
                        XlsxFile.text("D1", "Line"))
                .row(2, XlsxFile.text("A2", "Apple"), XlsxFile.number("B2", "2", 0), XlsxFile.number("C2", "3", 0),
                        XlsxFile.formula("D2", "B2*C2", "6"))
                .row(3, XlsxFile.text("A3", "Pear"), XlsxFile.number("B3", "4", 0), XlsxFile.number("C3", "5", 0),
                        XlsxFile.formula("D3", "B3*C3", "20"))
                .row(4, XlsxFile.text("A4", "Total"), XlsxFile.formula("B4", "SUM(B2:B3)", "6"),
                        XlsxFile.formula("C4", "SUM(C2:C3)", "8"), XlsxFile.formula("D4", "IF(B4>5,\"big\",\"small\")",
                                "big"))
                .widths(1, 1, 22).merge("A5:B5").row(5, XlsxFile.text("A5", "Merged note"))
                .bytes();

        Trip trip = RoundTripSupport.trip(file, 2, RowsMode.FIXED, null);

        same(trip);
        assertThat(trip.before().formulas()).hasSize(5);
        assertThat(trip.before().merges()).hasSize(1);
        assertThat(trip.written().formulas()).isEqualTo(5);
        assertThat(trip.written().formulasAsAnswers()).isZero();
    }
}
