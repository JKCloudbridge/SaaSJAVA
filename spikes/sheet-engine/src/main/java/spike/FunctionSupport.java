package spike;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.apache.poi.ss.formula.WorkbookEvaluator;

/** POC 2, WP5.1. What POI 6.0.0's own evaluator lists for the proposed function set (its own lists, not a guess). */
public final class FunctionSupport {

    static final Map<String, List<String>> PROPOSED = new LinkedHashMap<>();

    static {
        PROPOSED.put("arithmetic", List.of("ABS", "MOD", "INT", "ROUND", "ROUNDUP", "ROUNDDOWN", "SQRT", "POWER", "PRODUCT"));
        PROPOSED.put("conditional", List.of("IF", "IFERROR", "AND", "OR", "NOT", "ISNUMBER", "ISTEXT", "ISBLANK", "SUMIF", "COUNTIF",
                "AVERAGEIF", "IFS", "SWITCH"));
        PROPOSED.put("statistical", List.of("SUM", "AVERAGE", "MEDIAN", "MODE", "STDEV", "VAR", "COUNT", "COUNTA", "MIN", "MAX", "LARGE",
                "SMALL", "RANK"));
        PROPOSED.put("casting and text", List.of("VALUE", "N", "TEXT", "CONCATENATE", "LEFT", "RIGHT", "LEN", "UPPER", "TRIM"));
        PROPOSED.put("kept out for ever", List.of("INDIRECT", "TODAY", "NOW", "RAND", "RANDBETWEEN", "WEBSERVICE", "HYPERLINK", "OFFSET",
                "EVALUATE", "CALL", "REGISTER.ID", "FILTERXML", "ENCODEURL", "INFO", "CELL"));
    }

    private FunctionSupport() {
    }

    public static void main(String[] args) {
        Set<String> supported = new TreeSet<>(WorkbookEvaluator.getSupportedFunctionNames());
        Set<String> not = new TreeSet<>(WorkbookEvaluator.getNotSupportedFunctionNames());
        System.out.println("POI evaluator lists " + supported.size() + " supported and " + not.size() + " not supported function names");
        for (Map.Entry<String, List<String>> e : PROPOSED.entrySet()) {
            StringBuilder yes = new StringBuilder();
            StringBuilder no = new StringBuilder();
            StringBuilder unknown = new StringBuilder();
            for (String f : e.getValue()) {
                if (supported.contains(f)) {
                    yes.append(f).append(' ');
                } else if (not.contains(f)) {
                    no.append(f).append(' ');
                } else {
                    unknown.append(f).append(' ');
                }
            }
            System.out.println(e.getKey() + ": supported [" + yes.toString().trim() + "] not supported [" + no.toString().trim()
                    + "] not listed [" + unknown.toString().trim() + "]");
        }
    }
}
