package spike;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.ConditionalFormatting;
import org.apache.poi.ss.usermodel.ConditionalFormattingRule;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.SheetVisibility;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFFont;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/**
 * WP1.1: reads a workbook with POI and prints COUNTS ONLY. The text of the organization (cell values, labels, sheet names,
 * formulas) is never printed: formulas are summarised by the functions they use and by the kinds of reference.
 */
public final class Inspect {

    private Inspect() {
    }

    public static void main(String[] args) throws Exception {
        for (String path : args) {
            report(new File(path));
        }
    }

    static void report(File file) throws Exception {
        System.out.println("=== workbook (" + file.length() + " bytes)");
        try (XSSFWorkbook wb = new XSSFWorkbook(org.apache.poi.openxml4j.opc.OPCPackage.open(file,
                org.apache.poi.openxml4j.opc.PackageAccess.READ))) {
            System.out.println("sheets: " + wb.getNumberOfSheets() + ", fonts in file: " + wb.getNumberOfFonts()
                    + ", cell formats in file: " + wb.getNumCellStyles() + ", names: " + wb.getNumberOfNames()
                    + ", external links: " + wb.getExternalLinksTable().size());
            for (int i = 0; i < wb.getNumberOfSheets(); i++) {
                sheet(wb, i);
            }
        }
    }

    private static void sheet(XSSFWorkbook wb, int index) {
        Sheet sheet = wb.getSheetAt(index);
        SheetVisibility visibility = wb.getSheetVisibility(index);
        System.out.println("--- sheet #" + index + " visibility=" + visibility);

        Map<String, Integer> fontUse = new TreeMap<>();
        Map<String, Integer> fillUse = new TreeMap<>();
        Map<String, Integer> numFmtUse = new TreeMap<>();
        Map<String, Integer> alignUse = new TreeMap<>();
        Map<String, Integer> funcUse = new TreeMap<>();
        Set<String> borderCombos = new HashSet<>();
        Set<Integer> fontSizes = new HashSet<>();
        int cells = 0;
        int withText = 0;
        int bordered = 0;
        int filled = 0;
        int wrapped = 0;
        int formulas = 0;
        int textStartingWithEquals = 0;
        int formulaWithString = 0;
        int formulaWithRange = 0;
        int formulaWithOtherSheet = 0;
        int formulaWithExternal = 0;
        int formulasCachedNumeric = 0;
        int formulasCachedString = 0;
        int formulasCachedBlankString = 0;
        int formulasCachedError = 0;
        List<String> formulaRows = new ArrayList<>();

        for (Row row : sheet) {
            for (Cell cell : row) {
                cells++;
                XSSFCellStyle style = (XSSFCellStyle) cell.getCellStyle();
                XSSFFont font = style.getFont();
                fontUse.merge(font.getFontName() + "|" + (int) font.getFontHeightInPoints() + "|"
                        + (font.getBold() ? "B" : "") + (font.getItalic() ? "I" : "")
                        + (font.getUnderline() != 0 ? "U" : "") + "|" + colour(font.getXSSFColor()), 1, Integer::sum);
                fontSizes.add((int) font.getFontHeightInPoints());
                if (style.getFillPattern() != org.apache.poi.ss.usermodel.FillPatternType.NO_FILL) {
                    filled++;
                    fillUse.merge(style.getFillPattern() + "|" + colour(style.getFillForegroundColorColor()), 1,
                            Integer::sum);
                }
                String fmt = style.getDataFormatString();
                if (!"General".equals(fmt)) {
                    numFmtUse.merge(fmt, 1, Integer::sum);
                }
                if (style.getWrapText()) {
                    wrapped++;
                }
                alignUse.merge(style.getAlignment() + "/" + style.getVerticalAlignment(), 1, Integer::sum);
                boolean any = false;
                StringBuilder combo = new StringBuilder();
                for (BorderStyle b : new BorderStyle[] {style.getBorderTop(), style.getBorderRight(),
                    style.getBorderBottom(), style.getBorderLeft()}) {
                    combo.append(b).append('/');
                    any |= b != BorderStyle.NONE;
                }
                if (any) {
                    bordered++;
                    borderCombos.add(combo.toString());
                }
                CellType type = cell.getCellType();
                if (type == CellType.FORMULA) {
                    formulas++;
                    String f = cell.getCellFormula();
                    formulaRows.add(cell.getAddress().formatAsString());
                    if (f.indexOf('"') >= 0) {
                        formulaWithString++;
                    }
                    if (f.matches(".*[A-Z]+\\$?\\d+:\\$?[A-Z]+\\$?\\d+.*")) {
                        formulaWithRange++;
                    }
                    if (f.indexOf('!') >= 0) {
                        formulaWithOtherSheet++;
                    }
                    if (f.indexOf('[') >= 0) {
                        formulaWithExternal++;
                    }
                    java.util.regex.Matcher m = java.util.regex.Pattern.compile("([A-Z][A-Z0-9.]*)\\(").matcher(f);
                    while (m.find()) {
                        funcUse.merge(m.group(1), 1, Integer::sum);
                    }
                    switch (cell.getCachedFormulaResultType()) {
                        case NUMERIC -> formulasCachedNumeric++;
                        case STRING -> {
                            if (cell.getStringCellValue().isEmpty()) {
                                formulasCachedBlankString++;
                            } else {
                                formulasCachedString++;
                            }
                        }
                        case ERROR -> formulasCachedError++;
                        default -> { }
                    }
                } else if (type == CellType.STRING) {
                    withText++;
                    if (cell.getStringCellValue().startsWith("=")) {
                        textStartingWithEquals++;
                    }
                } else if (type == CellType.NUMERIC || type == CellType.BOOLEAN) {
                    withText++;
                }
            }
        }

        System.out.println("cells listed=" + cells + " with value=" + withText + " bordered=" + bordered + " filled="
                + filled + " wrapped=" + wrapped);
        System.out.println("distinct fonts used (family|pt|style|colour): " + fontUse.size() + " ; families="
                + fontUse.keySet().stream().map(k -> k.substring(0, k.indexOf('|'))).distinct().sorted().toList()
                + " ; sizes=" + new java.util.TreeSet<>(fontSizes));
        System.out.println("distinct fills used: " + fillUse.size() + " " + fillUse);
        System.out.println("distinct border combos: " + borderCombos.size());
        System.out.println("number formats used: " + numFmtUse);
        System.out.println("alignments: " + alignUse);

        List<CellRangeAddress> merges = sheet.getMergedRegions();
        System.out.println("merged areas: " + merges.size());

        Set<Integer> widths = new HashSet<>();
        int hiddenCols = 0;
        int maxCol = 0;
        for (Row r : sheet) {
            maxCol = Math.max(maxCol, r.getLastCellNum());
        }
        for (int c = 0; c < maxCol; c++) {
            widths.add(sheet.getColumnWidth(c));
            if (sheet.isColumnHidden(c)) {
                hiddenCols++;
            }
        }
        Set<Integer> heights = new HashSet<>();
        int hiddenRows = 0;
        for (Row r : sheet) {
            if (r.getZeroHeight()) {
                hiddenRows++;
            }
            heights.add((int) r.getHeight());
        }
        System.out.println("columns up to " + maxCol + ", distinct widths " + widths.size() + ", hidden columns "
                + hiddenCols + ", rows " + sheet.getPhysicalNumberOfRows() + ", distinct heights " + heights.size()
                + ", hidden rows " + hiddenRows + ", default col width " + sheet.getDefaultColumnWidth());

        org.apache.poi.ss.util.PaneInformation pane = sheet.getPaneInformation();
        System.out.println("frozen pane: " + (pane != null && pane.isFreezePane()
                ? "yes rows=" + pane.getHorizontalSplitPosition() + " cols=" + pane.getVerticalSplitPosition()
                : "no"));

        int cfBlocks = sheet.getSheetConditionalFormatting().getNumConditionalFormattings();
        int cfRules = 0;
        List<String> cfKinds = new ArrayList<>();
        for (int i = 0; i < cfBlocks; i++) {
            ConditionalFormatting cf = sheet.getSheetConditionalFormatting().getConditionalFormattingAt(i);
            for (int k = 0; k < cf.getNumberOfRules(); k++) {
                ConditionalFormattingRule rule = cf.getRule(k);
                cfRules++;
                cfKinds.add(rule.getConditionType() + "/" + rule.getComparisonOperation() + "/fill="
                        + (rule.getPatternFormatting() != null) + "/font=" + (rule.getFontFormatting() != null));
            }
        }
        System.out.println("conditional formats: blocks=" + cfBlocks + " rules=" + cfRules + " " + cfKinds);
        System.out.println("data validations: " + sheet.getDataValidations().size()
                + ", comments: " + sheet.getCellComments().size()
                + ", drawing shapes: " + (((org.apache.poi.xssf.usermodel.XSSFSheet) sheet).getDrawingPatriarch() == null
                        ? 0 : ((org.apache.poi.xssf.usermodel.XSSFSheet) sheet).getDrawingPatriarch().getShapes().size())
                + ", hyperlinks: " + sheet.getHyperlinkList().size());
        String printArea = sheet.getWorkbook().getPrintArea(index);
        System.out.println("print area: " + (printArea == null ? "none" : "yes") + ", fit to page: "
                + sheet.getFitToPage() + ", landscape: " + sheet.getPrintSetup().getLandscape()
                + ", paper size: " + sheet.getPrintSetup().getPaperSize());
        System.out.println("REAL formulas: " + formulas + " (string literal " + formulaWithString + ", range "
                + formulaWithRange + ", other sheet " + formulaWithOtherSheet + ", external " + formulaWithExternal
                + ") ; cached numeric " + formulasCachedNumeric + ", string " + formulasCachedString + ", empty string "
                + formulasCachedBlankString + ", error " + formulasCachedError + " ; functions " + funcUse);
        System.out.println("formula rows: " + new java.util.TreeSet<>(rowsOf(formulaRows)));
        System.out.println("TEXT cells starting with '=' (NOT formulas): " + textStartingWithEquals);
    }

    private static Set<Integer> rowsOf(List<String> addresses) {
        Set<Integer> rows = new HashSet<>();
        for (String a : addresses) {
            rows.add(Integer.parseInt(a.replaceAll("[A-Z]+", "")));
        }
        return rows;
    }

    private static String colour(org.apache.poi.ss.usermodel.Color c) {
        if (!(c instanceof XSSFColor x)) {
            return "-";
        }
        if (x.isThemed()) {
            return "theme" + x.getTheme() + (x.hasTint() ? "t" : "");
        }
        if (x.isIndexed()) {
            return "idx" + x.getIndexed();
        }
        if (x.isRGB()) {
            return "rgb";
        }
        return "auto";
    }
}
