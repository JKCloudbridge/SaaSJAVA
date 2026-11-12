package spike;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.apache.poi.ss.formula.FormulaParseException;
import org.apache.poi.ss.formula.FormulaParser;
import org.apache.poi.ss.formula.FormulaType;
import org.apache.poi.ss.formula.ptg.AbstractFunctionPtg;
import org.apache.poi.ss.formula.ptg.FuncVarPtg;
import org.apache.poi.ss.formula.ptg.NameXPxg;
import org.apache.poi.ss.formula.ptg.Ptg;
import org.apache.poi.ss.formula.ptg.StringPtg;
import org.apache.poi.xssf.usermodel.XSSFEvaluationWorkbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/**
 * POC 2, WP5.4. The gate of {@link Whitelist} (POC 1) with the larger function set. Same order: (1) bounds on the TEXT before any
 * parser sees it, (2) POI's own parser, (3) an ALLOWLIST of token kinds and of function names. Two narrowed forms decided on the
 * evidence of the function cases: TEXT takes only a listed format as a text constant, COUNT takes only cell references and
 * ranges. Nothing here runs a formula; messages hold kinds and function names only, never cell text.
 */
public final class Whitelist2 {

    /** The first release's functions (POC 2): the nine of POC 1 and the new ones; N is out (POI's evaluator cannot be its oracle). */
    public static final Set<String> FUNCTIONS = Set.of("ABS", "MOD", "INT", "ROUND", "ROUNDUP", "ROUNDDOWN", "SQRT", "POWER", "PRODUCT",
            "IF", "IFERROR", "AND", "OR", "NOT", "ISNUMBER", "ISTEXT", "ISBLANK", "SUMIF", "COUNTIF", "AVERAGEIF", "IFS", "SWITCH",
            "SUM", "AVERAGE", "MEDIAN", "MODE", "STDEV", "VAR", "COUNT", "COUNTA", "MIN", "MAX", "LARGE", "SMALL", "RANK",
            "VALUE", "TEXT", "CONCATENATE", "LEFT", "RIGHT", "LEN", "UPPER", "TRIM");

    public static final Set<String> TEXT_FORMATS = Set.of("0", "0.0", "0.00", "0.000", "0%", "0.0%", "0.00%", "#,##0", "#,##0.00");

    private static final Set<String> TOKENS = Set.of("IntPtg", "NumberPtg", "StringPtg", "BoolPtg", "MissingArgPtg",
            "ErrPtg", "RefPtg", "AreaPtg", "ParenthesisPtg", "AddPtg", "SubtractPtg", "MultiplyPtg", "DividePtg",
            "PowerPtg", "EqualPtg", "NotEqualPtg", "LessThanPtg", "LessEqualPtg", "GreaterThanPtg", "GreaterEqualPtg",
            "UnaryMinusPtg", "UnaryPlusPtg", "PercentPtg", "AttrPtg", "FuncPtg", "FuncVarPtg");

    public static final int MAX_CHARS = 4000;
    public static final int MAX_DEPTH = 32;

    private Whitelist2() {
    }

    public static List<String> check(XSSFWorkbook wb, int sheetIndex, String formula) {
        List<String> problems = new ArrayList<>();
        if (formula.length() > MAX_CHARS) {
            problems.add("too_long");
            return problems;
        }
        if (Whitelist.depth(formula) > MAX_DEPTH) {
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
        // POI's parser files the functions that came after the first file format (IFERROR, AVERAGEIF, IFS, SWITCH, ...) as a NAME
        // followed by a call of "#external#". Such a pair is accepted only when the name is an allowed function written without
        // a sheet, and every other name or sheet-qualified name is refused.
        int pendingCalls = 0;
        for (int i = 0; i < tokens.length; i++) {
            Ptg t = tokens[i];
            String kind = t.getClass().getSimpleName();
            if (t instanceof NameXPxg n) {
                String name = n.getNameName() == null ? "" : n.getNameName().toUpperCase(Locale.ROOT).replaceFirst("^_XLFN\\.", "");
                if (n.getSheetName() == null && FUNCTIONS.contains(name)) {
                    pendingCalls++;
                } else {
                    problems.add("token:NameXPxg");
                }
            } else if (t instanceof AbstractFunctionPtg f && f.getName().equals("#external#")) {
                if (pendingCalls-- <= 0) {
                    problems.add("function:#external#");
                }
            } else if (t instanceof AbstractFunctionPtg f) {
                String name = f.getName().toUpperCase(Locale.ROOT);
                if (!FUNCTIONS.contains(name)) {
                    problems.add("function:" + f.getName());
                } else if (name.equals("TEXT")) {
                    // the second argument is the last thing pushed before the function: it must be a listed format as a constant
                    if (i == 0 || !(tokens[i - 1] instanceof StringPtg s) || !TEXT_FORMATS.contains(s.getValue())) {
                        problems.add("text_format_not_allowed");
                    }
                } else if (name.equals("COUNT") && t instanceof FuncVarPtg v) {
                    for (int k = 1; k <= v.getNumberOfOperands(); k++) {
                        String arg = i - k < 0 ? "" : tokens[i - k].getClass().getSimpleName();
                        if (!arg.equals("RefPtg") && !arg.equals("AreaPtg")) {
                            problems.add("count_needs_cells");
                            break;
                        }
                    }
                }
            } else if (!TOKENS.contains(kind)) {
                problems.add("token:" + kind);
            }
        }
        if (pendingCalls > 0) {
            problems.add("name_not_allowed");
        }
        return problems;
    }
}
