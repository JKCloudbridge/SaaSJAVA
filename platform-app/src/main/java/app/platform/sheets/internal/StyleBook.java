package app.platform.sheets.internal;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.BuiltinFormats;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.xssf.model.StylesTable;
import org.apache.poi.xssf.model.ThemesTable;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFFont;
import org.apache.poi.xssf.usermodel.extensions.XSSFCellBorder;
import org.openxmlformats.schemas.spreadsheetml.x2006.main.CTCellAlignment;
import org.openxmlformats.schemas.spreadsheetml.x2006.main.CTColor;
import org.openxmlformats.schemas.spreadsheetml.x2006.main.CTDxf;
import org.openxmlformats.schemas.spreadsheetml.x2006.main.CTFill;
import org.openxmlformats.schemas.spreadsheetml.x2006.main.CTPatternFill;

/**
 * What a workbook's styles say about how a cell looks, as the closed look of the format (SH1B, ADR-0251, ADR-0253).
 * The styles part and the theme are read by the library (it resolves theme colours, tints and the table of indexed
 * colours), <em>after</em> our own counts and only from bytes; what comes out is mapped here and nothing of the
 * library's types leaves this class: a font family becomes a key of the closed list, a colour becomes {@code #rrggbb},
 * a border style and a number format become keys.
 *
 * <p>A text colour of plain black and a fill that is not a solid colour are not kept: black is what the page already
 * draws, and a screen chooses a readable text colour for a fill. A format is worked out once, the first time a cell
 * asks for it.
 */
final class StyleBook {

    /** The most of each kind of style element a workbook can have, which is what the file format itself allows. */
    private static final int MOST = 64_000;

    /**
     * How a cell with some style looks.
     *
     * @param bold whether the text is bold
     * @param italic whether the text is italic
     * @param underline whether the text is underlined
     * @param horizontal LEFT, CENTER or RIGHT, or null for the general alignment
     * @param points the font size in whole points, or 0 when the file does not say
     * @param look the closed look (font, size, colours, wrap, vertical alignment, borders, number format), or null
     * @param date whether the number format shows a date or a time
     * @param unknownNumberFormat whether the number format is one the closed list cannot hold
     * @param bordered whether the cell has a border on any side
     * @param fontId the number of the font in the file, to count how many unknown families there are (never a name)
     * @param fontFallback whether the font family was not in the table and took a generic key
     */
    record Format(boolean bold, boolean italic, boolean underline, String horizontal, int points, SheetLook look,
            boolean date, boolean unknownNumberFormat, boolean bordered, int fontId, boolean fontFallback) {

        static final Format PLAIN = new Format(false, false, false, null, 0, null, false, false, false, -1, false);

        /** Whether the cell shows something of itself with no text: a fill or a border. */
        boolean visible() {
            return bordered || look != null && look.fill() != null;
        }
    }

    /**
     * What a conditional format paints.
     *
     * @param fill the background as {@code #rrggbb}, or null
     * @param colour the text colour as {@code #rrggbb}, or null
     */
    record Paint(String fill, String colour) {
    }

    private final StylesTable styles;
    private final Map<Integer, Format> formats = new HashMap<>();

    private StyleBook(StylesTable styles) {
        this.styles = styles;
    }

    /** No styles: every cell is plain. */
    static StyleBook none() {
        return new StyleBook(null);
    }

    /**
     * Reads the styles part, and the theme when there is one.
     *
     * @throws ImportRefusal when the part is not what it says or holds more of a kind than a workbook can
     */
    static StyleBook read(byte[] stylesPart, byte[] themePart, ImportProperties settings) {
        PoiLimits.ensure(settings);
        count(stylesPart);
        Xml.refuseDoctype(stylesPart);
        try {
            StylesTable table = new StylesTable(new ByteArrayInputStream(stylesPart));
            if (themePart != null) {
                Xml.refuseDoctype(themePart);
                table.setTheme(new ThemesTable(new ByteArrayInputStream(themePart)));
            }
            return new StyleBook(table);
        } catch (IOException | RuntimeException e) {
            // The library's message can quote the file: say nothing of it.
            throw ImportRefusal.notXlsx();
        }
    }

    /** Our own count of each kind of element, before the library parses the part. */
    private static void count(byte[] part) {
        XMLStreamReader r = Xml.reader(part);
        int[] seen = new int[5];
        boolean inBorders = false;
        boolean inFonts = false;
        boolean inFills = false;
        boolean inXfs = false;
        boolean inDxfs = false;
        try {
            while (r.hasNext()) {
                int event = r.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    switch (r.getLocalName()) {
                        case "borders" -> inBorders = true;
                        case "fonts" -> inFonts = true;
                        case "fills" -> inFills = true;
                        case "cellXfs" -> inXfs = true;
                        case "dxfs" -> inDxfs = true;
                        case "border" -> over(inBorders, seen, 0);
                        case "font" -> over(inFonts, seen, 1);
                        case "fill" -> over(inFills, seen, 2);
                        case "xf" -> over(inXfs, seen, 3);
                        case "dxf" -> over(inDxfs, seen, 4);
                        default -> {
                        }
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    switch (r.getLocalName()) {
                        case "borders" -> inBorders = false;
                        case "fonts" -> inFonts = false;
                        case "fills" -> inFills = false;
                        case "cellXfs" -> inXfs = false;
                        case "dxfs" -> inDxfs = false;
                        default -> {
                        }
                    }
                }
            }
        } catch (XMLStreamException e) {
            throw ImportRefusal.notXlsx();
        } finally {
            Xml.close(r);
        }
    }

    private static void over(boolean inside, int[] seen, int which) {
        if (inside && ++seen[which] > MOST) {
            throw ImportRefusal.tooLarge("styles", "The file has more cell styles than a workbook is allowed to have ("
                    + MOST + ").");
        }
    }

    /** The format of the cell style with this number (the {@code s} of a cell); plain when there is none. */
    Format at(int xf) {
        if (styles == null || xf < 0) {
            return Format.PLAIN;
        }
        return formats.computeIfAbsent(xf, this::compute);
    }

    private Format compute(int xf) {
        XSSFCellStyle style = safe(() -> styles.getStyleAt(xf), null);
        return style == null ? Format.PLAIN : map(style);
    }

    /**
     * What the library says, a part at a time: a workbook written by hand or by a tool that leaves out a list (no
     * fills, a border number that points nowhere) still gives the parts it does have. The library's message can quote
     * the file, so it is dropped.
     */
    private static <T> T safe(Supplier<T> part, T fallback) {
        try {
            T value = part.get();
            return value == null ? fallback : value;
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private Format map(XSSFCellStyle style) {
        XSSFFont font = safe(style::getFont, null);
        boolean bold = font != null && safe(font::getBold, false);
        boolean italic = font != null && safe(font::getItalic, false);
        boolean underline = font != null && safe(font::getUnderline, (byte) 0) != 0;
        String family = font == null ? null : safe(font::getFontName, null);
        FontKeys.Mapped key = family == null || family.isBlank() ? null : FontKeys.of(family);
        int points = font == null ? 0 : safe(() -> points(font), 0);
        Integer size = points <= 0 ? null : Math.max(SheetLook.LEAST_SIZE, Math.min(SheetLook.MOST_SIZE, points));
        String colour = font == null ? null : safe(() -> hex(font.getXSSFColor()), null);
        if ("#000000".equals(colour)) {
            colour = null;
        }
        String fill = safe(() -> style.getFillPattern() == FillPatternType.SOLID_FOREGROUND
                ? hex(style.getFillForegroundXSSFColor()) : null, null);
        // Read from the style record itself: the library ignores a border or an alignment unless the record also
        // carries the "apply" flag, which hand-made files and some tools leave out.
        XSSFCellBorder cellBorder = safe(() -> styles.getBorderAt((int) style.getCoreXf().getBorderId()), null);
        SheetLook.Borders borders = cellBorder == null ? new SheetLook.Borders(null, null, null, null)
                : new SheetLook.Borders(border(cellBorder, XSSFCellBorder.BorderSide.TOP),
                        border(cellBorder, XSSFCellBorder.BorderSide.RIGHT),
                        border(cellBorder, XSSFCellBorder.BorderSide.BOTTOM),
                        border(cellBorder, XSSFCellBorder.BorderSide.LEFT));
        CTCellAlignment alignment = safe(() -> style.getCoreXf().isSetAlignment()
                ? style.getCoreXf().getAlignment() : null, null);
        boolean bordered = !borders.isEmpty();
        NumberFormats.Meaning number = safe(() -> number(style), NumberFormats.Meaning.GENERAL);
        SheetLook look = new SheetLook(key == null ? null : key.key(), size, colour, fill,
                alignment != null && alignment.getWrapText(), vertical(alignment),
                bordered ? borders : null, number.key()).normalized();
        return new Format(bold, italic, underline, horizontal(alignment),
                Math.max(points, 0), look, number.date(), number.unknown(), bordered,
                safe(() -> style.getFontIndex(), -1), key != null && key.how() == FontKeys.How.FALLBACK);
    }

    private static int points(XSSFFont font) {
        var sizes = font.getCTFont().getSzArray();
        if (sizes.length == 0) {
            return 0;
        }
        double value = sizes[0].getVal();
        return Double.isFinite(value) && value > 0 && value < 1_000 ? (int) Math.round(value) : 0;
    }

    private NumberFormats.Meaning number(XSSFCellStyle style) {
        int id = style.getDataFormat() & 0xffff;
        String code = styles.getNumberFormats().get((short) id);
        if (code == null) {
            code = BuiltinFormats.getBuiltinFormat(id);
        }
        return NumberFormats.of(id, code);
    }

    private static String border(XSSFCellBorder border, XSSFCellBorder.BorderSide side) {
        BorderStyle style = safe(() -> border.getBorderStyle(side), BorderStyle.NONE);
        return switch (style) {
            case NONE -> null;
            case THIN, HAIR -> "THIN";
            case MEDIUM, MEDIUM_DASHED, MEDIUM_DASH_DOT, MEDIUM_DASH_DOT_DOT, SLANTED_DASH_DOT -> "MEDIUM";
            case THICK -> "THICK";
            case DOTTED -> "DOTTED";
            case DASHED, DASH_DOT, DASH_DOT_DOT -> "DASHED";
            case DOUBLE -> "DOUBLE";
        };
    }

    private static String horizontal(CTCellAlignment alignment) {
        if (alignment == null || alignment.getHorizontal() == null) {
            return null;
        }
        return switch (alignment.getHorizontal().toString()) {
            case "left" -> "LEFT";
            case "center", "centerContinuous" -> "CENTER";
            case "right" -> "RIGHT";
            default -> null;
        };
    }

    private static String vertical(CTCellAlignment alignment) {
        if (alignment == null || alignment.getVertical() == null) {
            return null;
        }
        return switch (alignment.getVertical().toString()) {
            case "top" -> "TOP";
            case "center", "justify", "distributed" -> "MIDDLE";
            default -> null;
        };
    }

    /** A colour as {@code #rrggbb} in lower case, with the theme and the tint applied, or null when it has none. */
    private static String hex(XSSFColor colour) {
        if (colour == null || colour.isAuto()) {
            return null;
        }
        byte[] rgb;
        try {
            rgb = colour.getRGBWithTint();
        } catch (RuntimeException e) {
            return null;
        }
        if (rgb == null || rgb.length < 3) {
            return null;
        }
        int at = rgb.length - 3;
        return String.format("#%02x%02x%02x", rgb[at] & 0xff, rgb[at + 1] & 0xff, rgb[at + 2] & 0xff);
    }

    /** What the differential style with this number paints (a conditional format), or null when it paints nothing. */
    Paint paint(int dxfId) {
        if (styles == null || dxfId < 0) {
            return null;
        }
        try {
            CTDxf dxf = styles.getDxfAt(dxfId);
            if (dxf == null) {
                return null;
            }
            String fill = null;
            CTFill ctFill = dxf.getFill();
            CTPatternFill pattern = ctFill == null ? null : ctFill.getPatternFill();
            if (pattern != null) {
                // In a differential style the colour of a solid fill is the background colour.
                CTColor ct = pattern.getBgColor() != null ? pattern.getBgColor() : pattern.getFgColor();
                fill = ct == null ? null : hex(resolved(ct));
            }
            String colour = null;
            if (dxf.getFont() != null && dxf.getFont().sizeOfColorArray() > 0) {
                colour = hex(resolved(dxf.getFont().getColorArray(0)));
            }
            return fill == null && colour == null ? null : new Paint(fill, colour);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private XSSFColor resolved(CTColor ct) {
        XSSFColor colour = XSSFColor.from(ct, styles.getIndexedColors());
        if (styles.getTheme() != null) {
            styles.getTheme().inheritFromThemeAsRequired(colour);
        }
        return colour;
    }
}
