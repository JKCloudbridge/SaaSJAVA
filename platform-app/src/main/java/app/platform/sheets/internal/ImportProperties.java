package app.platform.sheets.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings of the upload of a spreadsheet file into a sheet (UI3B, ADR-0195).
 *
 * <p>The size of what comes out of a file is the limit of the sheet itself ({@code platform.sheets.limits}); these are
 * the limits of what is <em>read</em> to get there, so that a hostile file is stopped while it is read and never after.
 * A limit that is reached refuses the whole file and names the limit; nothing is cut.
 *
 * @param maxFileBytes the size of the file as sent, counted on the bytes read (default 5,000,000)
 * @param maxPartBytes what one part of a workbook may inflate to (default 20,000,000)
 * @param maxInflatedBytes what the whole archive may inflate to, parts that are not read included (default 100,000,000)
 * @param maxEntries the parts of a workbook archive (default 100)
 * @param maxCells the cell and row elements read from a sheet, empty ones included (default 200,000)
 * @param maxConcurrent the files read at the same moment on one instance; one more is told to try again (default 4)
 */
@ConfigurationProperties("platform.sheets.imports")
record ImportProperties(@DefaultValue("5000000") int maxFileBytes, @DefaultValue("20000000") int maxPartBytes,
        @DefaultValue("100000000") int maxInflatedBytes, @DefaultValue("100") int maxEntries,
        @DefaultValue("200000") int maxCells, @DefaultValue("4") int maxConcurrent) {

    ImportProperties {
        range("max-file-bytes", maxFileBytes, 1_000, 50_000_000);
        range("max-part-bytes", maxPartBytes, 10_000, 200_000_000);
        range("max-inflated-bytes", maxInflatedBytes, 10_000, 1_000_000_000);
        range("max-entries", maxEntries, 5, 10_000);
        range("max-cells", maxCells, 100, 5_000_000);
        range("max-concurrent", maxConcurrent, 1, 64);
    }

    /** The settings of the product, for tests and callers that need no setting. */
    static ImportProperties standard() {
        return new ImportProperties(5_000_000, 20_000_000, 100_000_000, 100, 200_000, 4);
    }

    private static void range(String name, int value, int least, int most) {
        if (value < least || value > most) {
            throw new IllegalArgumentException("platform.sheets.imports." + name + " must be " + least + " to "
                    + most);
        }
    }
}
