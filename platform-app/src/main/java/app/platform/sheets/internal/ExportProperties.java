package app.platform.sheets.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings of the file a sheet is written as (SH3, ADR-0253 decision 7).
 *
 * <p>The library builds the whole workbook in memory before it writes it (the measurement at the largest sheet the
 * limits allow, 500 rows by 100 columns, took about a second and about 100 MB while the workbook was held), so the
 * number of files written at the same moment on one instance is bounded, and a sheet over the cell bound is refused
 * before anything is built. A bound that is reached refuses the whole file and is answered like a file that is too
 * large; nothing is cut.
 *
 * @param maxBytes the size of the finished file (default 5,000,000)
 * @param maxCells the cells of all worksheets of one file, the label band and the footer row included (default 250,000)
 * @param maxMillis the time one file may take to build (default 20,000)
 * @param maxConcurrent the files written at the same moment on one instance; one more waits up to two seconds and is
 *        then told to try again (default 2)
 */
@ConfigurationProperties("platform.sheets.exports")
record ExportProperties(@DefaultValue("5000000") int maxBytes, @DefaultValue("250000") int maxCells,
        @DefaultValue("20000") int maxMillis, @DefaultValue("2") int maxConcurrent) {

    ExportProperties {
        range("max-bytes", maxBytes, 1_000, 50_000_000);
        range("max-cells", maxCells, 100, 5_000_000);
        range("max-millis", maxMillis, 100, 600_000);
        range("max-concurrent", maxConcurrent, 1, 64);
    }

    /** The settings of the product, for tests and callers that need no setting. */
    static ExportProperties standard() {
        return new ExportProperties(5_000_000, 250_000, 20_000, 2);
    }

    private static void range(String name, int value, int least, int most) {
        if (value < least || value > most) {
            throw new IllegalArgumentException("platform.sheets.exports." + name + " must be " + least + " to "
                    + most);
        }
    }
}
