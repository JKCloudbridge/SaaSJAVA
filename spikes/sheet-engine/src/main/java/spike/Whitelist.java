package spike;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.apache.poi.ss.formula.FormulaParseException;
import org.apache.poi.ss.formula.FormulaParser;
import org.apache.poi.ss.formula.FormulaType;
import org.apache.poi.ss.formula.ptg.AbstractFunctionPtg;
import org.apache.poi.ss.formula.ptg.Ptg;
import org.apache.poi.xssf.usermodel.XSSFEvaluationWorkbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/**
 * The server's gate for a formula. Order: (1) bounds on the TEXT before any parser sees it (length, nesting), (2) POI's own
 * parser, (3) an ALLOWLIST of token kinds (an unknown kind is refused, not tolerated) and of function names. Formulas are never
 * run as code: they are interpreted over parsed tokens. Messages hold kinds and function names only, never cell text.
 */
public final class Whitelist {

    /** The functions the first release would allow. SUM is added to the expression engine's list (IF, ISBLANK, ROUND, MIN, MAX). */
    public static final Set<String> FUNCTIONS = Set.of("IF", "MIN", "MAX", "SUM", "ROUND", "AND", "OR", "ISBLANK", "NOT");

    /** The token kinds that may appear: constants, plain same-sheet references and ranges, arithmetic, comparison. */
    private static final Set<String> TOKENS = Set.of("IntPtg", "NumberPtg", "StringPtg", "BoolPtg", "MissingArgPtg",
            "ErrPtg", "RefPtg", "AreaPtg", "ParenthesisPtg", "AddPtg", "SubtractPtg", "MultiplyPtg", "DividePtg",
            "PowerPtg", "EqualPtg", "NotEqualPtg", "LessThanPtg", "LessEqualPtg", "GreaterThanPtg", "GreaterEqualPtg",
            "UnaryMinusPtg", "UnaryPlusPtg", "PercentPtg", "AttrPtg", "FuncPtg", "FuncVarPtg");

    /** The bounds of the expression engine (ADR-0076): 4000 characters, 32 levels. */
    public static final int MAX_CHARS = 4000;
    public static final int MAX_DEPTH = 32;

    private Whitelist() {
    }

    /** Problems found; empty means the formula is inside the whitelist. */
    public static List<String> check(XSSFWorkbook wb, int sheetIndex, String formula) {
        List<String> problems = new ArrayList<>();
        if (formula.length() > MAX_CHARS) {
            problems.add("too_long");
            return problems;
        }
        if (depth(formula) > MAX_DEPTH) {
            problems.add("too_deep");
            return problems;
        }
        Ptg[] tokens;
        try {
            tokens = FormulaParser.parse(formula, XSSFEvaluationWorkbook.create(wb), FormulaType.CELL, sheetIndex);
        } catch (FormulaParseException | IllegalArgumentException e) {
            problems.add("parse:" + e.getClass().getSimpleName());
            return problems;
        }
        for (Ptg t : tokens) {
            String kind = t.getClass().getSimpleName();
            if (t instanceof AbstractFunctionPtg f) {
                if (!FUNCTIONS.contains(f.getName().toUpperCase(Locale.ROOT))) {
                    problems.add("function:" + f.getName());
                }
            } else if (!TOKENS.contains(kind)) {
                problems.add("token:" + kind);
            }
        }
        return problems;
    }

    /** Nesting of brackets outside text constants; counted on the text so the parser is never asked to recurse deeply. */
    public static int depth(String formula) {
        int depth = 0;
        int max = 0;
        boolean inText = false;
        for (int i = 0; i < formula.length(); i++) {
            char c = formula.charAt(i);
            if (c == '"') {
                inText = !inText;
            } else if (!inText) {
                if (c == '(') {
                    max = Math.max(max, ++depth);
                } else if (c == ')') {
                    depth--;
                }
            }
        }
        return max;
    }
}
