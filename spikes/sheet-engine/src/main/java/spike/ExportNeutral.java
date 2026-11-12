package spike;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackageAccess;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.ConditionalFormatting;
import org.apache.poi.ss.usermodel.ConditionalFormattingRule;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.SheetVisibility;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFFont;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/**
 * Writes a neutral JSON description of a workbook for the browser candidates (the "throwaway adapter" of WP2/WP3). It carries the
 * owner's text, so the output goes to a folder outside the repository and is never committed. Colours are resolved to RGB here.
 */
public final class ExportNeutral {

    private ExportNeutral() {
    }

    public static void main(String[] args) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook(OPCPackage.open(new File(args[0]), PackageAccess.READ))) {
            Files.writeString(Path.of(args[1]), export(wb), StandardCharsets.UTF_8);
        }
        System.out.println("written " + args[1]);
    }

    static String export(XSSFWorkbook wb) {
        StringBuilder out = new StringBuilder("{\"sheets\":[");
        for (int s = 0; s < wb.getNumberOfSheets(); s++) {
            if (s > 0) {
                out.append(',');
            }
            sheet(out, wb, s);
        }
        return out.append("]}").toString();
    }

    private static void sheet(StringBuilder out, XSSFWorkbook wb, int index) {
        Sheet sh = wb.getSheetAt(index);
        Map<String, Integer> styleIds = new LinkedHashMap<>();
        List<String> styles = new ArrayList<>();
        StringBuilder cells = new StringBuilder();
        int maxRow = 0;
        int maxCol = 0;
        for (Row row : sh) {
            for (Cell cell : row) {
                XSSFCellStyle st = (XSSFCellStyle) cell.getCellStyle();
                String sj = styleJson(st);
                Integer id = styleIds.get(sj);
                if (id == null) {
                    id = styles.size();
                    styleIds.put(sj, id);
                    styles.add(sj);
                }
                CellType type = cell.getCellType();
                StringBuilder c = new StringBuilder("{\"r\":" + cell.getRowIndex() + ",\"c\":" + cell.getColumnIndex() + ",\"s\":" + id);
                switch (type) {
                    case STRING -> c.append(",\"t\":\"s\",\"v\":").append(q(cell.getStringCellValue()));
                    case NUMERIC -> c.append(",\"t\":\"n\",\"v\":").append(num(cell.getNumericCellValue()));
                    case BOOLEAN -> c.append(",\"t\":\"b\",\"v\":").append(cell.getBooleanCellValue());
                    case ERROR -> c.append(",\"t\":\"e\"");
                    case FORMULA -> {
                        c.append(",\"t\":\"f\",\"f\":").append(q(cell.getCellFormula()));
                        switch (cell.getCachedFormulaResultType()) {
                            case NUMERIC -> c.append(",\"v\":").append(num(cell.getNumericCellValue())).append(",\"vt\":\"n\"");
                            case STRING -> c.append(",\"v\":").append(q(cell.getStringCellValue())).append(",\"vt\":\"s\"");
                            case BOOLEAN -> c.append(",\"v\":").append(cell.getBooleanCellValue()).append(",\"vt\":\"b\"");
                            default -> c.append(",\"vt\":\"e\"");
                        }
                    }
                    default -> c.append(",\"t\":\"z\"");
                }
                c.append('}');
                if (cells.length() > 0) {
                    cells.append(',');
                }
                cells.append(c);
                maxRow = Math.max(maxRow, cell.getRowIndex() + 1);
                maxCol = Math.max(maxCol, cell.getColumnIndex() + 1);
            }
        }
        out.append("{\"index\":").append(index).append(",\"hidden\":")
                .append(wb.getSheetVisibility(index) != SheetVisibility.VISIBLE).append(",\"rows\":").append(maxRow)
                .append(",\"cols\":").append(maxCol);
        org.apache.poi.ss.util.PaneInformation pane = sh.getPaneInformation();
        out.append(",\"freeze\":{\"rows\":").append(pane != null && pane.isFreezePane() ? pane.getHorizontalSplitPosition() : 0)
                .append(",\"cols\":").append(pane != null && pane.isFreezePane() ? pane.getVerticalSplitPosition() : 0).append('}');
        out.append(",\"colWidths\":[");
        for (int c = 0; c < maxCol; c++) {
            out.append(c > 0 ? "," : "").append(Math.round(sh.getColumnWidth(c) / 256.0 * 7 + 5));
        }
        out.append("],\"rowHeights\":{");
        boolean first = true;
        for (Row row : sh) {
            if (row.getHeightInPoints() != sh.getDefaultRowHeightInPoints()) {
                out.append(first ? "" : ",").append('"').append(row.getRowNum()).append("\":")
                        .append(Math.round(row.getHeightInPoints() * 96 / 72));
                first = false;
            }
        }
        out.append("},\"merges\":[");
        List<CellRangeAddress> merges = sh.getMergedRegions();
        for (int i = 0; i < merges.size(); i++) {
            CellRangeAddress m = merges.get(i);
            out.append(i > 0 ? "," : "").append("{\"r0\":").append(m.getFirstRow()).append(",\"c0\":").append(m.getFirstColumn())
                    .append(",\"r1\":").append(m.getLastRow()).append(",\"c1\":").append(m.getLastColumn()).append('}');
        }
        out.append("],\"styles\":[").append(String.join(",", styles)).append("],\"cells\":[").append(cells).append("],\"cf\":[");
        boolean firstCf = true;
        for (int i = 0; i < sh.getSheetConditionalFormatting().getNumConditionalFormattings(); i++) {
            ConditionalFormatting cf = sh.getSheetConditionalFormatting().getConditionalFormattingAt(i);
            for (int k = 0; k < cf.getNumberOfRules(); k++) {
                ConditionalFormattingRule rule = cf.getRule(k);
                out.append(firstCf ? "" : ",");
                firstCf = false;
                out.append("{\"ranges\":[");
                CellRangeAddress[] ranges = cf.getFormattingRanges();
                for (int j = 0; j < ranges.length; j++) {
                    CellRangeAddress r = ranges[j];
                    out.append(j > 0 ? "," : "").append("{\"r0\":").append(r.getFirstRow()).append(",\"c0\":")
                            .append(r.getFirstColumn()).append(",\"r1\":").append(r.getLastRow()).append(",\"c1\":")
                            .append(r.getLastColumn()).append('}');
                }
                out.append("],\"op\":").append(rule.getComparisonOperation()).append(",\"f1\":").append(q(rule.getFormula1()))
                        .append(",\"fill\":").append(rule.getPatternFormatting() == null ? "null"
                                : colour(rule.getPatternFormatting().getFillBackgroundColorColor()))
                        .append(",\"color\":").append(rule.getFontFormatting() == null ? "null"
                                : colour(rule.getFontFormatting().getFontColor())).append('}');
            }
        }
        out.append("]}");
    }

    private static String styleJson(XSSFCellStyle st) {
        XSSFFont f = st.getFont();
        StringBuilder s = new StringBuilder("{\"font\":{\"name\":").append(q(f.getFontName())).append(",\"size\":")
                .append(f.getFontHeightInPoints()).append(",\"bold\":").append(f.getBold()).append(",\"italic\":")
                .append(f.getItalic()).append(",\"underline\":").append(f.getUnderline() != 0).append(",\"color\":")
                .append(colour(f.getXSSFColor())).append("},\"fill\":")
                .append(st.getFillPattern() == FillPatternType.NO_FILL ? "null" : colour(st.getFillForegroundColorColor()))
                .append(",\"border\":{");
        String[] names = {"t", "r", "b", "l"};
        BorderStyle[] bs = {st.getBorderTop(), st.getBorderRight(), st.getBorderBottom(), st.getBorderLeft()};
        XSSFColor[] bc = {st.getTopBorderXSSFColor(), st.getRightBorderXSSFColor(), st.getBottomBorderXSSFColor(),
            st.getLeftBorderXSSFColor()};
        boolean first = true;
        for (int i = 0; i < 4; i++) {
            if (bs[i] != BorderStyle.NONE) {
                s.append(first ? "" : ",").append('"').append(names[i]).append("\":{\"style\":").append(q(bs[i].name()))
                        .append(",\"color\":").append(colour(bc[i])).append('}');
                first = false;
            }
        }
        s.append("},\"numFmt\":").append(q(st.getDataFormatString())).append(",\"h\":").append(q(st.getAlignment().name()))
                .append(",\"v\":").append(q(st.getVerticalAlignment().name())).append(",\"wrap\":").append(st.getWrapText())
                .append('}');
        return s.toString();
    }

    private static String colour(org.apache.poi.ss.usermodel.Color c) {
        if (!(c instanceof XSSFColor x)) {
            return "null";
        }
        byte[] rgb = x.getRGBWithTint();
        if (rgb == null) {
            rgb = x.getRGB();
        }
        if (rgb == null && x.isIndexed()) {
            try {
                short[] t = org.apache.poi.hssf.util.HSSFColor.HSSFColorPredefined.values()[0].getTriplet().length == 3
                        ? null : null;
            } catch (RuntimeException ignored) {
                // fall through
            }
            for (org.apache.poi.hssf.util.HSSFColor.HSSFColorPredefined p
                    : org.apache.poi.hssf.util.HSSFColor.HSSFColorPredefined.values()) {
                if (p.getIndex() == x.getIndexed()) {
                    short[] t = p.getTriplet();
                    rgb = new byte[] {(byte) t[0], (byte) t[1], (byte) t[2]};
                }
            }
        }
        if (rgb == null || rgb.length < 3) {
            return "null";
        }
        int o = rgb.length == 4 ? 1 : 0;
        return String.format("\"#%02X%02X%02X\"", rgb[o] & 0xff, rgb[o + 1] & 0xff, rgb[o + 2] & 0xff);
    }

    private static String num(double d) {
        return d == Math.rint(d) && Math.abs(d) < 1e15 ? Long.toString((long) d) : Double.toString(d);
    }

    private static String q(String s) {
        if (s == null) {
            return "null";
        }
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20) {
                        b.append(String.format("\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
                }
            }
        }
        return b.append('"').toString();
    }
}
