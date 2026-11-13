package app.platform.sheets.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.sheets.internal.RoundTripSupport.Snapshot;
import app.platform.sheets.internal.RoundTripSupport.Trip;
import app.platform.sheets.internal.SheetGrid.RowsMode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Uploads the two real workbooks into a sheet of format version 2, downloads each, uploads the download, and writes
 * <em>counts only</em> to {@code target/real-workbooks-write.txt} (SH3). The files are named in the system property
 * {@code realWorkbooks} (paths separated by a semicolon); they are read from bytes, never opened for writing, never
 * copied into the repository and never quoted. The downloads are written to the folder named by the property
 * {@code writeFolder} (a temporary folder outside the repository) so the owner can open them in a spreadsheet program;
 * nothing is kept in the repository. The test fails when a count that must stay equal does not.
 */
class RealWorkbooksWriteReportTest {

    private static final SheetProperties.Limits LIMITS = SheetProperties.Limits.standard();

    @Test
    @EnabledIfSystemProperty(named = "realWorkbooks", matches = ".+")
    void writesTheCountsOfTheRoundTripOfTheRealWorkbooks() throws IOException {
        StringBuilder report = new StringBuilder();
        List<String> unequal = new java.util.ArrayList<>();
        int workbook = 0;
        for (String path : System.getProperty("realWorkbooks").split(";")) {
            workbook++;
            byte[] file = Files.readAllBytes(Path.of(path));
            report.append("workbook ").append(workbook).append(": ").append(file.length).append(" bytes\n");
            int worksheets = new WorkbookReader(ImportProperties.standard(), LIMITS).read(file, null).worksheets
                    .size();
            for (int sheet = 0; sheet < worksheets; sheet++) {
                for (int dimension : new int[] {2, 1}) {
                    String label = "worksheet " + (sheet + 1) + " as " + (dimension == 2 ? "named rows"
                            : "rows the filler adds");
                    try {
                        Trip trip = RoundTripSupport.trip(file, dimension, dimension == 2 ? RowsMode.FIXED
                                : RowsMode.REPEATABLE, sheet);
                        describe(report, label, trip, workbook, sheet, dimension);
                        compare(unequal, label + " of workbook " + workbook, trip);
                    } catch (RuntimeException e) {
                        report.append("  ").append(label).append(": not uploaded (").append(e.getClass()
                                .getSimpleName()).append(")\n");
                    }
                }
            }
        }
        Files.createDirectories(Path.of("target"));
        Files.writeString(Path.of("target/real-workbooks-write.txt"), report.toString());
        assertThat(unequal).as("counts that changed on the round trip").isEmpty();
    }

    private static void describe(StringBuilder out, String label, Trip trip, int workbook, int sheet, int dimension)
            throws IOException {
        Snapshot b = trip.before();
        Snapshot a = trip.after();
        out.append("  ").append(label).append(": download ").append(trip.downloadBytes()).append(" bytes, written in ")
                .append(trip.millis()).append(" ms; ").append(b.columns().size()).append(" columns, ")
                .append(b.texts().size()).append(" cells with text, ").append(b.looks().size())
                .append(" with a look, ").append(b.merges().size()).append(" merged areas, ")
                .append(b.formulas().size()).append(" formulas (").append(trip.written().formulas())
                .append(" written as formulas, ").append(trip.written().formulasAsAnswers())
                .append(" as answers), ").append(b.conditions().size()).append(" conditional colours, freeze ")
                .append(b.freeze()).append("; after the round trip: ").append(a.texts().size()).append(" text, ")
                .append(a.looks().size()).append(" looks, ").append(a.merges().size()).append(" merges, ")
                .append(a.formulas().size()).append(" formulas, ").append(a.conditions().size())
                .append(" conditions, freeze ").append(a.freeze()).append(", ")
                .append(distinctFonts(b)).append("/").append(distinctFonts(a)).append(" font keys, ")
                .append(distinctFills(b)).append("/").append(distinctFills(a)).append(" fills\n");
        String folder = System.getProperty("writeFolder");
        if (folder != null && !folder.isBlank()) {
            Files.createDirectories(Path.of(folder));
            Files.write(Path.of(folder, "download-" + workbook + "-" + (sheet + 1) + "-" + dimension + ".xlsx"),
                    trip.file());
        }
    }

    private static long distinctFonts(Snapshot snapshot) {
        return snapshot.looks().values().stream().map(SheetLook::font).filter(java.util.Objects::nonNull).distinct()
                .count();
    }

    private static long distinctFills(Snapshot snapshot) {
        return snapshot.looks().values().stream().map(SheetLook::fill).filter(java.util.Objects::nonNull).distinct()
                .count();
    }

    private static void compare(List<String> unequal, String label, Trip trip) {
        Snapshot b = trip.before();
        Snapshot a = trip.after();
        check(unequal, label, "column labels", a.columns().equals(b.columns()));
        check(unequal, label, "cells with text", a.texts().equals(b.texts()));
        check(unequal, label, "looks", a.looks().equals(b.looks()));
        check(unequal, label, "merged areas", new java.util.HashSet<>(a.merges()).equals(
                new java.util.HashSet<>(b.merges())) && a.merges().size() == b.merges().size());
        boolean widths = a.widths().size() == b.widths().size();
        for (int i = 0; widths && i < a.widths().size(); i++) {
            widths = Math.abs(Integer.parseInt(a.widths().get(i)) - Integer.parseInt(b.widths().get(i))) <= 6;
        }
        check(unequal, label, "widths (within the 5 pixels of padding)", widths);
        check(unequal, label, "formulas", a.formulas().equals(b.formulas()));
        check(unequal, label, "conditional colours", a.conditions().equals(b.conditions()));
        check(unequal, label, "frozen pane", a.freeze().equals(b.freeze()));
    }

    private static void check(List<String> unequal, String label, String what, boolean equal) {
        if (!equal) {
            unequal.add(label + ": " + what);
        }
    }
}
