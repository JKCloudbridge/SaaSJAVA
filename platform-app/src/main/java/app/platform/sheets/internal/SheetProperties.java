package app.platform.sheets.internal;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings of the sheets module (FA3, ADR-0173 section 3, ADR-0186; SH1A, ADR-0251).
 *
 * @param limits what a grid may hold; a limit that is reached is a validation error naming it, and nothing is cut
 * @param smallBodyBytes the largest body of the endpoints that carry no grid (default 4,096)
 * @param formatTwo the switch of format version 2
 */
@ConfigurationProperties("platform.sheets")
record SheetProperties(@DefaultValue Limits limits, @DefaultValue("4096") int smallBodyBytes,
        @DefaultValue FormatTwo formatTwo) {

    SheetProperties {
        if (smallBodyBytes < 64 || smallBodyBytes > 65_536) {
            throw new IllegalArgumentException("platform.sheets.small-body-bytes must be 64 to 65536");
        }
    }

    /**
     * The switch of format version 2 (SH1A, ADR-0251). Nothing stores a version 2 document unless it is on. It is off
     * by default, so that a deployment never holds a template that cannot yet be filled in; the {@code local} and
     * {@code test} profiles switch it on.
     *
     * @param enabled whether a document of format version 2 may be stored
     * @param owners the API names of the objects whose uploads make a document of format version 2 while the switch is
     *        on (MC1, ADR-0257); empty means every owner. It lets one application try the new format while the others
     *        keep making sheets that can be filled in
     */
    record FormatTwo(@DefaultValue("false") boolean enabled, @DefaultValue List<String> owners) {

        /** The canonical constructor, named for the binder because the record has a second one. */
        @ConstructorBinding
        FormatTwo(@DefaultValue("false") boolean enabled, @DefaultValue List<String> owners) {
            this.enabled = enabled;
            this.owners = List.copyOf(owners);
        }

        /** The switch alone: every owner follows it. */
        FormatTwo(boolean enabled) {
            this(enabled, List.of());
        }

        /** Whether an upload for a record of this owner makes a document of format version 2. */
        boolean makesFor(String ownerObject) {
            return enabled && (owners.isEmpty() || owners.contains(ownerObject));
        }
    }

    /**
     * What a grid may hold. The defaults are the product caps the product owner decided on 2026-10-21 (1 MB, 500 rows
     * by 100 columns) and, for format version 2, on 2026-11-12 (ADR-0251). The database has its own, higher ceiling for
     * the size (5,000,000 bytes), whatever these say.
     *
     * @param maxBytes the size of the grid as sent (default 1,000,000), checked before it is parsed
     * @param maxRows the named body rows (500)
     * @param maxColumns the columns (100)
     * @param maxHeaderRows the header rows (10)
     * @param maxCellChars the characters of a cell's text in format version 1 and of the text typed into an input cell
     *        in format version 2 (1,000)
     * @param maxLabelChars the characters of a label (200)
     * @param maxPicklistValues the values of a picklist column (100)
     * @param maxMerges the merged areas (2,000)
     * @param maxDepth how deep the JSON may nest (12)
     * @param maxVersionsPerOwner the versions one sheet may have; none is ever deleted, so this is what keeps a sheet
     *        from growing for ever (1,000)
     * @param maxFixedTextChars the characters of a fixed text in format version 2 (5,000)
     * @param minColumnWidth the narrowest column of format version 2, in pixels (8)
     * @param maxColumnWidth the widest column of format version 2, in pixels (1,000)
     * @param minRowHeight the shortest row of format version 2, in pixels (4)
     * @param maxRowHeight the tallest row of format version 2, in pixels (600)
     * @param maxSheets the sheets of one template (1; a later release raises it)
     * @param maxFilesPerCell the files of one attachment cell (5)
     * @param maxLinkChars the characters of the address of a link (2,000)
     */
    record Limits(@DefaultValue("1000000") int maxBytes, @DefaultValue("500") int maxRows,
            @DefaultValue("100") int maxColumns, @DefaultValue("10") int maxHeaderRows,
            @DefaultValue("1000") int maxCellChars, @DefaultValue("200") int maxLabelChars,
            @DefaultValue("100") int maxPicklistValues, @DefaultValue("2000") int maxMerges,
            @DefaultValue("12") int maxDepth, @DefaultValue("1000") int maxVersionsPerOwner,
            @DefaultValue("5000") int maxFixedTextChars, @DefaultValue("8") int minColumnWidth,
            @DefaultValue("1000") int maxColumnWidth, @DefaultValue("4") int minRowHeight,
            @DefaultValue("600") int maxRowHeight, @DefaultValue("1") int maxSheets,
            @DefaultValue("5") int maxFilesPerCell, @DefaultValue("2000") int maxLinkChars) {

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
            // The schema of format version 2 is the widest a setting may reach: a setting only narrows it.
            range("max-fixed-text-chars", maxFixedTextChars, 1, 10_000);
            range("min-column-width", minColumnWidth, 8, 1_000);
            range("max-column-width", maxColumnWidth, 8, 1_000);
            range("min-row-height", minRowHeight, 4, 600);
            range("max-row-height", maxRowHeight, 4, 600);
            range("max-sheets", maxSheets, 1, 8);
            range("max-files-per-cell", maxFilesPerCell, 1, 5);
            range("max-link-chars", maxLinkChars, 10, 2_000);
            if (minColumnWidth > maxColumnWidth) {
                throw new IllegalArgumentException("platform.sheets.limits.min-column-width is above the maximum");
            }
            if (minRowHeight > maxRowHeight) {
                throw new IllegalArgumentException("platform.sheets.limits.min-row-height is above the maximum");
            }
        }

        /** The caps of the product, for tests and callers that need no setting. */
        static Limits standard() {
            return new Limits(1_000_000, 500, 100, 10, 1_000, 200, 100, 2_000, 12, 1_000, 5_000, 8, 1_000, 4, 600, 1,
                    5, 2_000);
        }

        /**
         * The same limits with the ten of format version 1 changed, the rest as they are: for the tests that squeeze
         * one limit.
         */
        static Limits ofOne(int maxBytes, int maxRows, int maxColumns, int maxHeaderRows, int maxCellChars,
                int maxLabelChars, int maxPicklistValues, int maxMerges, int maxDepth, int maxVersionsPerOwner) {
            return new Limits(maxBytes, maxRows, maxColumns, maxHeaderRows, maxCellChars, maxLabelChars,
                    maxPicklistValues, maxMerges, maxDepth, maxVersionsPerOwner, 5_000, 8, 1_000, 4, 600, 1, 5, 2_000);
        }

        private static void range(String name, int value, int least, int most) {
            if (value < least || value > most) {
                throw new IllegalArgumentException("platform.sheets.limits." + name + " must be " + least + " to "
                        + most);
            }
        }
    }
}
