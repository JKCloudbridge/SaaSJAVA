package app.platform.sheets.internal.formula;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The fixed words for every reason a formula can be refused (SH2). A reason is a stable key; the words never repeat the
 * formula, a text, a number or a name that was typed, and name a bound only by the number the settings give.
 */
public final class FormulaWords {

    private static final Map<String, String> WORDS = new LinkedHashMap<>();

    static {
        WORDS.put("too_long", "A formula is longer than the allowed length.");
        WORDS.put("too_deep", "A formula is nested deeper than the allowed number of levels.");
        WORDS.put("too_complex", "A formula has more parts than the allowed number.");
        WORDS.put("character_not_allowed", "A formula uses a character that is not allowed. Another sheet, another "
                + "file, a name and joining text with & are not supported.");
        WORDS.put("unclosed_text", "A formula has a text in quotes that is not closed.");
        WORDS.put("bad_reference_token", "A formula holds a reference that is not written correctly.");
        WORDS.put("unknown_column", "A formula points at a column that does not exist.");
        WORDS.put("unknown_row", "A formula points at a row that does not exist.");
        WORDS.put("column_outside_sheet", "A formula points at a column outside the sheet.");
        WORDS.put("row_outside_sheet", "A formula points at a row outside the sheet, or at a row of a repeating sheet "
                + "that cannot be named.");
        WORDS.put("name_not_allowed", "A formula uses a name. Names are not supported.");
        WORDS.put("function_not_allowed", "A formula uses a function that is not supported.");
        WORDS.put("wrong_argument_count", "A formula gives a function the wrong number of arguments.");
        WORDS.put("count_needs_cells", "COUNT takes only cells and ranges.");
        WORDS.put("text_format_not_allowed", "TEXT takes one of the listed formats as a text in quotes.");
        WORDS.put("bad_range", "A formula holds a range that is not written correctly.");
        WORDS.put("unexpected_token", "A formula is not written correctly.");
        WORDS.put("unexpected_end", "A formula ends before it is complete.");
        WORDS.put("missing_bracket", "A formula has a bracket that is not closed.");
        WORDS.put("empty_argument", "A formula leaves an argument empty.");
        WORDS.put("number_range", "A formula holds a number that is too long or too far from zero.");
        WORDS.put("unsafe_text", "A text in a formula holds markup or a control character.");
        WORDS.put("growing_not_allowed", "The whole body of a column can be named only in a sheet where the filler "
                + "adds all the rows.");
        WORDS.put("circular", "A formula depends on itself, directly or through other formulas.");
        WORDS.put("chain_too_deep", "Formulas depend on each other through more steps than the allowed number.");
        WORDS.put("not_a_fixed_cell", "A cell with a formula is a fixed cell: it holds no text of its own and is not "
                + "an input cell.");
        WORDS.put("column_not_allowed", "A column can hold a formula only in a sheet where the filler adds all the "
                + "rows.");
        WORDS.put("too_many_formulas", "A sheet has more formulas than the allowed number.");
        WORDS.put("no_body_rows", "The sheet has no rows to draw this formula at.");
        WORDS.put("empty_formula", "A formula is empty.");
    }

    private FormulaWords() {
    }

    /** The words of a reason; a reason not in the table gets the general words. */
    public static String of(String reason) {
        return WORDS.getOrDefault(reason, "A formula is not accepted.");
    }

    /** Whether the reason is one of the table. */
    public static boolean known(String reason) {
        return WORDS.containsKey(reason);
    }

    /** Every reason, in the order of the table. */
    public static Iterable<String> reasons() {
        return WORDS.keySet();
    }
}
