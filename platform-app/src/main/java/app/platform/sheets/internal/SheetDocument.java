package app.platform.sheets.internal;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * A sheet in format version 2 as a typed model (SH1A, ADR-0251): a list of sheets, each the whole body of a version 1
 * grid ({@link SheetGrid}) plus what version 2 adds. Immutable. Everything in it is text or design of the
 * organization, so every type prints as a redacted marker.
 *
 * <p>The body of each sheet is the version 1 model, so every rule that is the same for both formats is written once
 * (see {@link SheetGridValidator}). The look and the links of the cells are kept beside it by cell key, so the body
 * stays exactly what a version 1 reader expects.
 *
 * @param sheets the sheets in the order of the tabs; the order is part of the document's meaning
 */
record SheetDocument(List<Tab> sheets) {

    /** The format number of this document. */
    static final int FORMAT_VERSION = 2;

    SheetDocument {
        sheets = List.copyOf(sheets);
    }

    /**
     * One sheet.
     *
     * @param key the permanent name inside the template
     * @param title the name of the tab, or null
     * @param fillable false for an information tab
     * @param freeze the frozen panes, or null for none
     * @param print the page set-up, or null for the default
     * @param inputs the input cells that state their own type, requirement or class, by cell key
     * @param body the version 1 body of the sheet
     * @param looks the look of the cells that have one, by cell key
     * @param links the https address of the cells that have a link, by cell key
     * @param conditions the conditional colours, in the order they are applied
     */
    record Tab(String key, String title, boolean fillable, Freeze freeze, Print print, Map<String, Input> inputs,
            SheetGrid body, Map<String, SheetLook> looks, Map<String, String> links, List<Condition> conditions) {

        Tab {
            inputs = Collections.unmodifiableMap(new LinkedHashMap<>(inputs));
            looks = Collections.unmodifiableMap(new LinkedHashMap<>(looks));
            links = Collections.unmodifiableMap(new LinkedHashMap<>(links));
            conditions = List.copyOf(conditions);
        }

        /** A sheet with no conditional colours. */
        Tab(String key, String title, boolean fillable, Freeze freeze, Print print, Map<String, Input> inputs,
                SheetGrid body, Map<String, SheetLook> looks, Map<String, String> links) {
            this(key, title, fillable, freeze, print, inputs, body, looks, links, List.of());
        }

        @Override
        public String toString() {
            return "Tab[redacted]";
        }
    }

    /**
     * The panes that stay in view.
     *
     * @param rows rows counted from the top, header rows first
     * @param columns columns counted from the left
     */
    record Freeze(int rows, int columns) {

        boolean isNone() {
            return rows == 0 && columns == 0;
        }
    }

    /**
     * The page set-up.
     *
     * @param paper A4, A3 or LETTER
     * @param orientation PORTRAIT or LANDSCAPE
     * @param fitToWidth whether the sheet is scaled to the width of the page
     * @param repeatHeaderRows whether the header rows are repeated on every page
     */
    record Print(String paper, String orientation, boolean fitToWidth, boolean repeatHeaderRows) {
    }

    /**
     * A conditional colour (SH1B, ADR-0255): the cells of a range whose value meets a comparison with a number are
     * painted. A closed rule; nothing in it is ever run.
     *
     * @param from the top left cell of the range, as a cell key
     * @param to the bottom right cell of the range, as a cell key
     * @param operator LT, LTE, EQ, NEQ, GTE, GT, BETWEEN or NOT_BETWEEN
     * @param value the number compared with, as a plain decimal text
     * @param value2 the upper number of BETWEEN and NOT_BETWEEN, otherwise null
     * @param fill the background as {@code #rrggbb}, or null
     * @param colour the text colour as {@code #rrggbb}, or null
     */
    record Condition(String from, String to, String operator, String value, String value2, String fill,
            String colour) {

        boolean isBetween() {
            return "BETWEEN".equals(operator) || "NOT_BETWEEN".equals(operator);
        }

        @Override
        public String toString() {
            return "Condition[redacted]";
        }
    }

    /** Who fills an input cell. The class is a field of the format; the server enforces it from SH5. */
    enum InputClass {
        FILLER, REVIEWER;

        String json() {
            return name().toLowerCase(Locale.ROOT);
        }

        static Optional<InputClass> of(String json) {
            for (InputClass one : values()) {
                if (one.json().equals(json)) {
                    return Optional.of(one);
                }
            }
            return Optional.empty();
        }
    }

    /**
     * What the author states about one input cell. Whatever is null or false is as its column says.
     *
     * @param type the type that overrides the column's: text, number, date, picklist, boolean or attachment
     * @param required whether the filler must fill it
     * @param cls who fills it
     * @param picklist the values of a picklist cell
     * @param maxFiles the most files of an attachment cell (5 when absent)
     */
    record Input(String type, boolean required, InputClass cls, List<String> picklist, Integer maxFiles) {

        Input {
            picklist = picklist == null ? null : List.copyOf(picklist);
        }

        @Override
        public String toString() {
            return "Input[redacted]";
        }
    }

    /** The files an attachment cell holds when it states no limit. */
    static final int DEFAULT_MAX_FILES = 5;

    /** Prints a marker, never the content. */
    @Override
    public String toString() {
        return "SheetDocument[redacted]";
    }
}
