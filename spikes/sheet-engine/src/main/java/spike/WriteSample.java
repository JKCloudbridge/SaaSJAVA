package spike;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackageAccess;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.ComparisonOperator;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.SheetConditionalFormatting;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.DefaultIndexedColorMap;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFFont;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/**
 * WP1.3: writes a workbook with styles and formulas, reopens it and compares. The file is for the product owner to open by
 * hand in a spreadsheet application. Content is made up here (no text from the owner's files).
 */
public final class WriteSample {

    private WriteSample() {
    }

    public static void main(String[] args) throws Exception {
        File out = new File(args.length > 0 ? args[0] : "target/out/poc1-sample-styles-and-formulas.xlsx");
        Files.createDirectories(out.getParentFile().toPath());
        write(out);
        System.out.println("written " + out.getAbsolutePath() + " (" + out.length() + " bytes)");
        List<String> failures = verify(out);
        System.out.println("read-back checks failed: " + failures.size() + " " + failures);
    }

    static XSSFColor rgb(int r, int g, int b) {
        return new XSSFColor(new byte[] {(byte) r, (byte) g, (byte) b}, new DefaultIndexedColorMap());
    }

    static void write(File out) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            XSSFSheet sh = wb.createSheet("Data");

            XSSFFont title = wb.createFont();
            title.setFontName("Cambria");
            title.setFontHeightInPoints((short) 16);
            title.setBold(true);
            XSSFFont body = wb.createFont();
            body.setFontName("Calibri");
            body.setFontHeightInPoints((short) 10);
            XSSFFont italic = wb.createFont();
            italic.setFontName("Arial");
            italic.setFontHeightInPoints((short) 9);
            italic.setItalic(true);
            italic.setUnderline(org.apache.poi.ss.usermodel.Font.U_SINGLE);
            italic.setColor(rgb(0x1F, 0x4E, 0x79));

            XSSFCellStyle titleStyle = wb.createCellStyle();
            titleStyle.setFont(title);
            titleStyle.setAlignment(HorizontalAlignment.CENTER);
            titleStyle.setVerticalAlignment(VerticalAlignment.CENTER);
            titleStyle.setFillForegroundColor(rgb(0xF4, 0xCC, 0xCC));
            titleStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            border(titleStyle);

            XSSFCellStyle head = wb.createCellStyle();
            head.setFont(body);
            head.setWrapText(true);
            head.setAlignment(HorizontalAlignment.CENTER);
            head.setFillForegroundColor(rgb(0xFF, 0xF2, 0xCC));
            head.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            border(head);

            XSSFCellStyle number = wb.createCellStyle();
            number.setFont(body);
            number.setDataFormat(wb.createDataFormat().getFormat("0.00"));
            border(number);

            XSSFCellStyle percent = wb.createCellStyle();
            percent.setFont(body);
            percent.setDataFormat(wb.createDataFormat().getFormat("0%"));
            border(percent);

            XSSFCellStyle month = wb.createCellStyle();
            month.setFont(italic);
            month.setDataFormat(wb.createDataFormat().getFormat("mmm-yy"));
            border(month);

            Row r0 = sh.createRow(0);
            r0.setHeightInPoints(28);
            Cell t = r0.createCell(0);
            t.setCellValue("Sample form");
            t.setCellStyle(titleStyle);
            for (int c = 1; c < 6; c++) {
                r0.createCell(c).setCellStyle(titleStyle);
            }
            sh.addMergedRegion(new CellRangeAddress(0, 0, 0, 5));

            Row r1 = sh.createRow(1);
            String[] heads = {"Month", "Planned", "Done", "Rating", "Share", "Total"};
            for (int c = 0; c < heads.length; c++) {
                Cell h = r1.createCell(c);
                h.setCellValue(heads[c]);
                h.setCellStyle(head);
            }

            for (int i = 0; i < 4; i++) {
                Row r = sh.createRow(2 + i);
                Cell m = r.createCell(0);
                m.setCellValue(java.time.LocalDate.of(2025, 1 + i, 1));
                m.setCellStyle(month);
                Cell planned = r.createCell(1);
                planned.setCellValue(10 + i);
                planned.setCellStyle(number);
                Cell done = r.createCell(2);
                done.setCellValue(7 + i);
                done.setCellStyle(number);
                int rn = 3 + i;
                Cell rating = r.createCell(3);
                rating.setCellFormula("IF(B" + rn + "=\"\",\"\",MIN(100,((C" + rn + "/B" + rn + ")+(0.5*(B" + rn + "-C" + rn
                        + ")/B" + rn + "))*100))");
                rating.setCellStyle(number);
                Cell share = r.createCell(4);
                share.setCellFormula("C" + rn + "/B" + rn);
                share.setCellStyle(percent);
                r.createCell(5).setCellStyle(number);
            }
            Row total = sh.createRow(6);
            Cell label = total.createCell(0);
            label.setCellValue("Total");
            label.setCellStyle(head);
            for (int c = 1; c <= 2; c++) {
                Cell s = total.createCell(c);
                String col = String.valueOf((char) ('A' + c));
                s.setCellFormula("SUM(" + col + "3:" + col + "6)");
                s.setCellStyle(number);
            }

            sh.setColumnWidth(0, 14 * 256);
            sh.setColumnWidth(1, 12 * 256);
            sh.setColumnWidth(2, 12 * 256);
            sh.setColumnWidth(3, 12 * 256);
            sh.createFreezePane(0, 2);

            SheetConditionalFormatting cf = sh.getSheetConditionalFormatting();
            org.apache.poi.ss.usermodel.ConditionalFormattingRule rule =
                    cf.createConditionalFormattingRule(ComparisonOperator.LT, "80");
            rule.createPatternFormatting().setFillBackgroundColor(rgb(0xF8, 0xCB, 0xAD));
            rule.createFontFormatting().setFontColor(rgb(0x9C, 0x00, 0x06));
            cf.addConditionalFormatting(new CellRangeAddress[] {CellRangeAddress.valueOf("D3:D6")}, rule);

            sh.getPrintSetup().setPaperSize(org.apache.poi.ss.usermodel.PrintSetup.A4_PAPERSIZE);
            sh.setFitToPage(true);
            sh.getPrintSetup().setFitWidth((short) 1);
            sh.getPrintSetup().setFitHeight((short) 0);

            // a second, hidden worksheet, to prove a hidden sheet round-trips as hidden
            wb.createSheet("Hidden");
            wb.setSheetVisibility(1, org.apache.poi.ss.usermodel.SheetVisibility.HIDDEN);

            wb.getCreationHelper().createFormulaEvaluator().evaluateAll();
            try (FileOutputStream os = new FileOutputStream(out)) {
                wb.write(os);
            }
        }
    }

    private static void border(XSSFCellStyle s) {
        s.setBorderTop(BorderStyle.THIN);
        s.setBorderBottom(BorderStyle.THIN);
        s.setBorderLeft(BorderStyle.THIN);
        s.setBorderRight(BorderStyle.THIN);
    }

    static List<String> verify(File file) throws Exception {
        List<String> failed = new ArrayList<>();
        try (XSSFWorkbook wb = new XSSFWorkbook(OPCPackage.open(file, PackageAccess.READ))) {
            Sheet sh = wb.getSheetAt(0);
            check(failed, "two sheets", wb.getNumberOfSheets() == 2);
            check(failed, "second sheet hidden", wb.getSheetVisibility(1) == org.apache.poi.ss.usermodel.SheetVisibility.HIDDEN);
            check(failed, "merge", sh.getMergedRegions().size() == 1);
            check(failed, "freeze pane", sh.getPaneInformation() != null && sh.getPaneInformation().isFreezePane()
                    && sh.getPaneInformation().getHorizontalSplitPosition() == 2);
            check(failed, "conditional rule", sh.getSheetConditionalFormatting().getNumConditionalFormattings() == 1);
            check(failed, "column width", sh.getColumnWidth(0) == 14 * 256);
            check(failed, "row height", Math.abs(sh.getRow(0).getHeightInPoints() - 28) < 0.01);
            XSSFCellStyle ts = (XSSFCellStyle) sh.getRow(0).getCell(0).getCellStyle();
            check(failed, "title font", "Cambria".equals(ts.getFont().getFontName())
                    && ts.getFont().getFontHeightInPoints() == 16 && ts.getFont().getBold());
            check(failed, "title fill", ts.getFillPattern() == FillPatternType.SOLID_FOREGROUND
                    && java.util.Arrays.equals(ts.getFillForegroundColorColor() instanceof XSSFColor x ? x.getRGB() : null,
                            new byte[] {(byte) 0xF4, (byte) 0xCC, (byte) 0xCC}));
            check(failed, "title border", ts.getBorderTop() == BorderStyle.THIN && ts.getBorderLeft() == BorderStyle.THIN);
            XSSFCellStyle ms = (XSSFCellStyle) sh.getRow(2).getCell(0).getCellStyle();
            check(failed, "date format", "mmm-yy".equals(ms.getDataFormatString()));
            check(failed, "italic underline font", ms.getFont().getItalic() && ms.getFont().getUnderline() != 0);
            check(failed, "percent format", "0%".equals(sh.getRow(2).getCell(4).getCellStyle().getDataFormatString()));
            Cell rating = sh.getRow(2).getCell(3);
            check(failed, "formula text kept", rating.getCellType() == CellType.FORMULA
                    && rating.getCellFormula().equals(
                            "IF(B3=\"\",\"\",MIN(100,((C3/B3)+(0.5*(B3-C3)/B3))*100))"));
            check(failed, "cached value written", rating.getCachedFormulaResultType() == CellType.NUMERIC
                    && Math.abs(rating.getNumericCellValue() - 85.0) < 1e-9);
            check(failed, "sum cached", Math.abs(sh.getRow(6).getCell(1).getNumericCellValue() - 46.0) < 1e-9);
            check(failed, "fit to page", sh.getFitToPage() && sh.getPrintSetup().getPaperSize() == 9);
        }
        return failed;
    }

    private static void check(List<String> failed, String what, boolean ok) {
        if (!ok) {
            failed.add(what);
        }
    }
}
