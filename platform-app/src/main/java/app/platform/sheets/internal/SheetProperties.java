package app.platform.sheets.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings of the sheets module (FA3, ADR-0173 section 3, ADR-0186).
 *
 * @param limits what a grid may hold; a limit that is reached is a validation error naming it, and nothing is cut
 * @param smallBodyBytes the largest body of the endpoints that carry no grid (default 4,096)
 */
@ConfigurationProperties("platform.sheets")
record SheetProperties(@DefaultValue Limits limits, @DefaultValue("4096") int smallBodyBytes) {

    SheetProperties {
        if (smallBodyBytes < 64 || smallBodyBytes > 65_536) {
            throw new IllegalArgumentException("platform.sheets.small-body-bytes must be 64 to 65536");
        }
    }

    /**
     * What a grid may hold. The defaults are the product caps the product owner decided on 2026-10-21 (1 MB, 500 rows
     * by 100 columns). The database has its own, higher ceiling for the size (5,000,000 bytes), whatever these say.
     *
     * @param maxBytes the size of the grid as sent (default 1,000,000), checked before it is parsed
     * @param maxRows the named body rows (500)
     * @param maxColumns the columns (100)
     * @param maxHeaderRows the header rows (10)
     * @param maxCellChars the characters of a cell's text (1,000)
     * @param maxLabelChars the characters of a label (200)
     * @param maxPicklistValues the values of a picklist column (100)
     * @param maxMerges the merged areas (2,000)
     * @param maxDepth how deep the JSON may nest (12)
     * @param maxVersionsPerOwner the versions one sheet may have; none is ever deleted, so this is what keeps a sheet
     *        from growing for ever (1,000)
     */
    record Limits(@DefaultValue("1000000") int maxBytes, @DefaultValue("500") int maxRows,
            @DefaultValue("100") int maxColumns, @DefaultValue("10") int maxHeaderRows,
            @DefaultValue("1000") int maxCellChars, @DefaultValue("200") int maxLabelChars,
            @DefaultValue("100") int maxPicklistValues, @DefaultValue("2000") int maxMerges,
            @DefaultValue("12") int maxDepth, @DefaultValue("1000") int maxVersionsPerOwner) {

        Limits {
            range("max-bytes", maxBytes, 1_000, 5_000_000);
            range("max-rows", maxRows, 1, 5_000);
            range("max-columns", maxColumns, 1, 1_000);
            range("max-header-rows", maxHeaderRows, 0, 100);
            range("max-cell-chars", maxCellChars, 1, 10_000);
            range("max-label-chars", maxLabelChars, 1, 1_000);
            range("max-picklist-values", maxPicklistValues, 1, 1_000);
            range("max-merges", maxMerges, 0, 100_000);
            range("max-depth", maxDepth, 4, 64);
            range("max-versions-per-owner", maxVersionsPerOwner, 1, 100_000);
        }

        /** The caps of the product, for tests and callers that need no setting. */
        static Limits standard() {
            return new Limits(1_000_000, 500, 100, 10, 1_000, 200, 100, 2_000, 12, 1_000);
        }

        private static void range(String name, int value, int least, int most) {
            if (value < least || value > most) {
                throw new IllegalArgumentException("platform.sheets.limits." + name + " must be " + least + " to "
                        + most);
            }
        }
    }
}
