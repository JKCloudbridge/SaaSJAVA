package spike;

import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import javax.xml.parsers.SAXParserFactory;

import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.xssf.eventusermodel.ReadOnlySharedStringsTable;
import org.apache.poi.xssf.eventusermodel.XSSFReader;
import org.apache.poi.xssf.eventusermodel.XSSFSheetXMLHandler;
import org.apache.poi.xssf.model.StylesTable;
import org.apache.poi.xssf.usermodel.XSSFComment;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.xml.sax.InputSource;
import org.xml.sax.XMLReader;

/**
 * Where does the time go, cold? Variant "model" builds the whole workbook object; variant "events" streams each worksheet through
 * SAX (POI's event API) and keeps only counts of cells, formulas and styled cells. Run one variant per JVM so each is cold.
 */
public final class Breakdown {

    private Breakdown() {
    }

    public static void main(String[] args) throws Exception {
        byte[] file = Files.readAllBytes(Path.of(args[1]));
        PoiSafe.Limits l = PoiSafe.Limits.standard();
        PoiSafe.configurePoi(l);
        long t0 = System.nanoTime();
        PoiSafe.prescan(file, l);
        long t1 = System.nanoTime();
        System.out.println(args[0] + ": pre-scan " + ms(t0, t1) + " ms");
        if (args[0].equals("model")) {
            try (OPCPackage pkg = OPCPackage.open(new ByteArrayInputStream(file))) {
                long t2 = System.nanoTime();
                System.out.println("  package opened " + ms(t1, t2) + " ms");
                try (XSSFWorkbook wb = new XSSFWorkbook(pkg)) {
                    long t3 = System.nanoTime();
                    System.out.println("  workbook model built " + ms(t2, t3) + " ms");
                    int cells = 0;
                    for (org.apache.poi.ss.usermodel.Sheet s : wb) {
                        for (org.apache.poi.ss.usermodel.Row r : s) {
                            cells += r.getPhysicalNumberOfCells();
                        }
                    }
                    System.out.println("  walked " + cells + " cells " + ms(t3, System.nanoTime()) + " ms");
                }
            }
        } else {
            try (OPCPackage pkg = OPCPackage.open(new ByteArrayInputStream(file))) {
                long t2 = System.nanoTime();
                System.out.println("  package opened " + ms(t1, t2) + " ms");
                XSSFReader reader = new XSSFReader(pkg);
                StylesTable styles = reader.getStylesTable();
                ReadOnlySharedStringsTable strings = new ReadOnlySharedStringsTable(pkg);
                long t3 = System.nanoTime();
                System.out.println("  styles and shared strings read " + ms(t2, t3) + " ms");
                int[] counts = new int[2];
                XSSFReader.SheetIterator it = (XSSFReader.SheetIterator) reader.getSheetsData();
                SAXParserFactory f = SAXParserFactory.newInstance();
                f.setNamespaceAware(true);
                f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
                while (it.hasNext()) {
                    try (var in = it.next()) {
                        XMLReader xml = f.newSAXParser().getXMLReader();
                        xml.setContentHandler(new XSSFSheetXMLHandler(styles, null, strings,
                                new org.apache.poi.xssf.eventusermodel.XSSFSheetXMLHandler.SheetContentsHandler() {
                                    public void startRow(int rowNum) { }
                                    public void endRow(int rowNum) { }
                                    public void cell(String ref, String value, org.apache.poi.ss.usermodel.Comment c) {
                                        counts[0]++;
                                    }
                                }, new DataFormatter(), false));
                        xml.parse(new InputSource(in));
                    }
                }
                System.out.println("  streamed " + counts[0] + " cells " + ms(t3, System.nanoTime()) + " ms");
            }
        }
        System.out.println("  total " + ms(t0, System.nanoTime()) + " ms");
    }

    private static long ms(long a, long b) {
        return (b - a) / 1_000_000;
    }
}
