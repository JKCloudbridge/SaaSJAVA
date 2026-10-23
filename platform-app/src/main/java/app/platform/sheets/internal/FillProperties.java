package app.platform.sheets.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings of the fill-ins of the sheets module (FF1, ADR-0192).
 *
 * <p>The characters of the text of a cell are the limit of the sheet itself ({@code platform.sheets.limits}), so a
 * value that fits a column of the sheet fits the fill-in. A limit that is reached is a validation error naming it, and
 * nothing is cut.
 *
 * @param maxBytes the size of the cells document as sent (default 1,000,000), checked before it is parsed. The typical
 *        fill-in is a few kilobytes (ADR-0173 section 6); this is only the ceiling
 * @param maxRows the rows of a fill-in, the named rows of the sheet and the rows the filler added together (1,000)
 * @param maxDepth how deep the JSON may nest (6; the document is four levels deep)
 */
@ConfigurationProperties("platform.sheets.fills")
record FillProperties(@DefaultValue("1000000") int maxBytes, @DefaultValue("1000") int maxRows,
        @DefaultValue("6") int maxDepth) {

    FillProperties {
        range("max-bytes", maxBytes, 1_000, 5_000_000);
        range("max-rows", maxRows, 1, 10_000);
        range("max-depth", maxDepth, 4, 32);
    }

    private static void range(String name, int value, int least, int most) {
        if (value < least || value > most) {
            throw new IllegalArgumentException("platform.sheets.fills." + name + " must be " + least + " to " + most);
        }
    }
}
