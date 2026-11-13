package app.platform.sheets.internal;

import java.util.List;
import java.util.Locale;
import tools.jackson.databind.JsonNode;

/**
 * How a cell of format version 2 looks, beyond its style (SH1A, ADR-0251): the font, the size in points, the text and
 * fill colours, wrapping, vertical alignment, the four borders and the number format. Every part is a key of a closed
 * list or a checked value; nothing taken from a file is ever used as it came (a colour is {@code #rrggbb} after a
 * check, a font is a key of the list, never a name). The lists are the ones the schema {@code SheetLook} states, and
 * a test compares them.
 *
 * <p>A look is held in its <em>one form</em>: a colour in lower case, and a part that says what its absence already
 * says (no wrapping, the bottom, the general number format) is not kept, so two looks that mean the same are equal.
 *
 * @param font a key of {@link #FONTS}, or null for the page's own
 * @param size whole points, or null for the page's own
 * @param colour the text colour as {@code #rrggbb}, or null
 * @param fill the background as {@code #rrggbb}, or null
 * @param wrap whether the text wraps
 * @param verticalAlignment TOP or MIDDLE; null means the bottom
 * @param borders the borders, or null for none
 * @param numberFormat a key of {@link #NUMBER_FORMATS} other than GENERAL, or null
 */
record SheetLook(String font, Integer size, String colour, String fill, boolean wrap, String verticalAlignment,
        Borders borders, String numberFormat) {

    /** The fonts, as keys: the page decides which fonts a key stands for. */
    static final List<String> FONTS = List.of("SANS_NEUTRAL", "SANS_HUMANIST", "SANS_WIDE", "SANS_FRIENDLY",
            "SANS_UNICODE", "DISPLAY_HEAVY", "DISPLAY_IMPACT", "SERIF_TEXT", "SERIF_SCREEN", "SERIF_SLAB",
            "MONO_CLASSIC", "MONO_CODE", "SANS_UI", "SANS", "SERIF", "MONO");

    /** The number formats, as keys. */
    static final List<String> NUMBER_FORMATS = List.of("GENERAL", "WHOLE", "TWO_DECIMALS", "PERCENT",
            "PERCENT_TWO_DECIMALS", "THOUSANDS", "THOUSANDS_TWO_DECIMALS", "ACCOUNTING", "MONTH_YEAR", "SHORT_DATE");

    /** The styles of one border side. */
    static final List<String> BORDER_STYLES = List.of("THIN", "MEDIUM", "THICK", "DOTTED", "DASHED", "DOUBLE");

    /** The vertical alignments. */
    static final List<String> VERTICAL = List.of("TOP", "MIDDLE", "BOTTOM");

    /** The smallest and the largest size, in points. */
    static final int LEAST_SIZE = 8;
    static final int MOST_SIZE = 28;

    /**
     * The four borders, each a key of {@link #BORDER_STYLES} or null for none.
     *
     * @param top the top side
     * @param right the right side
     * @param bottom the bottom side
     * @param left the left side
     */
    record Borders(String top, String right, String bottom, String left) {

        boolean isEmpty() {
            return top == null && right == null && bottom == null && left == null;
        }
    }

    /** Whether this look says nothing, which is the same as no look. */
    boolean isEmpty() {
        return font == null && size == null && colour == null && fill == null && !wrap && verticalAlignment == null
                && (borders == null || borders.isEmpty()) && numberFormat == null;
    }

    /** The look in its one form; null when it says nothing. */
    SheetLook normalized() {
        Borders sides = borders == null || borders.isEmpty() ? null : borders;
        SheetLook one = new SheetLook(font, size, colour == null ? null : colour.toLowerCase(Locale.ROOT),
                fill == null ? null : fill.toLowerCase(Locale.ROOT), wrap,
                "BOTTOM".equals(verticalAlignment) ? null : verticalAlignment, sides,
                "GENERAL".equals(numberFormat) ? null : numberFormat);
        return one.isEmpty() ? null : one;
    }

    /** Reads a look from a node that fits the schema {@code SheetLook}; null when the node is absent. */
    static SheetLook read(JsonNode node) {
        if (!node.isObject()) {
            return null;
        }
        JsonNode sides = node.path("borders");
        Borders borders = sides.isObject() ? new Borders(text(sides, "top"), text(sides, "right"),
                text(sides, "bottom"), text(sides, "left")) : null;
        return new SheetLook(text(node, "font"), node.has("size") ? node.path("size").asInt() : null,
                text(node, "colour"), text(node, "fill"), node.path("wrap").asBoolean(false),
                text(node, "verticalAlignment"), borders, text(node, "numberFormat"));
    }

    private static String text(JsonNode node, String name) {
        return node.has(name) ? node.path(name).asString() : null;
    }

    /** Prints a marker: the look is the organization's design, and nothing in it needs to reach a log. */
    @Override
    public String toString() {
        return "SheetLook[redacted]";
    }
}
