package app.platform.sheets.internal.formula;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The functions a sheet formula may call, with the number of arguments each takes (SH2, ADR-0252). This table is the
 * allowlist of the gate and is told to the screen from here, so the browser holds no list of its own. Adding a
 * function is one row, a case in the shared case file, both evaluators and a refusal line.
 *
 * <p>Left out of the set on purpose: {@code STDEV}, {@code VAR}, {@code RANK} and {@code N} (the second opinion
 * disagrees with the documentation on an edge input), and for ever everything that reads the clock, the network,
 * another file or evaluates text.
 */
public final class FormulaFunctions {

    /** The nine formats {@code TEXT} accepts, as a text constant. */
    public static final Set<String> TEXT_FORMATS = Set.of("0", "0.0", "0.00", "0.000", "0%", "0.0%", "0.00%", "#,##0",
            "#,##0.00");

    /** The nine formats in the order the screen lists them. */
    public static final List<String> TEXT_FORMAT_LIST = List.of("0", "0.0", "0.00", "0.000", "0%", "0.0%", "0.00%",
            "#,##0", "#,##0.00");

    /**
     * One function.
     *
     * @param name the name in capitals
     * @param least the fewest arguments
     * @param most the most arguments
     */
    public record Arity(String name, int least, int most) {
    }

    private static final Map<String, Arity> TABLE = new LinkedHashMap<>();

    static {
        add("ABS", 1, 1);
        add("MOD", 2, 2);
        add("INT", 1, 1);
        add("ROUND", 2, 2);
        add("ROUNDUP", 2, 2);
        add("ROUNDDOWN", 2, 2);
        add("SQRT", 1, 1);
        add("POWER", 2, 2);
        add("PRODUCT", 1, 255);
        add("IF", 2, 3);
        add("IFERROR", 2, 2);
        add("AND", 1, 255);
        add("OR", 1, 255);
        add("NOT", 1, 1);
        add("ISNUMBER", 1, 1);
        add("ISTEXT", 1, 1);
        add("ISBLANK", 1, 1);
        add("SUMIF", 2, 3);
        add("COUNTIF", 2, 2);
        add("AVERAGEIF", 2, 3);
        add("IFS", 2, 254);
        add("SWITCH", 3, 253);
        add("SUM", 1, 255);
        add("AVERAGE", 1, 255);
        add("MEDIAN", 1, 255);
        add("MODE", 1, 255);
        add("COUNT", 1, 255);
        add("COUNTA", 1, 255);
        add("MIN", 1, 255);
        add("MAX", 1, 255);
        add("LARGE", 2, 2);
        add("SMALL", 2, 2);
        add("VALUE", 1, 1);
        add("TEXT", 2, 2);
        add("CONCATENATE", 1, 255);
        add("LEFT", 1, 2);
        add("RIGHT", 1, 2);
        add("LEN", 1, 1);
        add("UPPER", 1, 1);
        add("TRIM", 1, 1);
    }

    private FormulaFunctions() {
    }

    private static void add(String name, int least, int most) {
        TABLE.put(name, new Arity(name, least, most));
    }

    /** The arity of a function, or null when the name is not in the set. */
    public static Arity of(String name) {
        return TABLE.get(name);
    }

    /** Every function, in the order of the table. */
    public static List<Arity> all() {
        return new ArrayList<>(TABLE.values());
    }
}
