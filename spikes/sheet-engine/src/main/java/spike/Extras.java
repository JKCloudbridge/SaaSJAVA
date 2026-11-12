package spike;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * POC 2, WP1. The smallest safe reader of what the upload skips today: form-control check boxes, hyperlinks, the shapes and
 * pictures of the drawing layer and the embedded objects. It reads the archive from BYTES (never opens the owner's file), after
 * the same archive pre-scan as the rest (duplicate part names, {@code ..}, macros, sizes counted while inflating), and every
 * XML part with a StAX reader that has the document type declaration switched off and nothing resolved. A legacy drawing part
 * (VML) is not well-formed XML, so it is scanned by a bounded text scanner that expands no entity and runs nothing.
 *
 * <p>Nothing read from a drawing or a link (a name, a caption, a link address, a file name, picture bytes) is ever printed or
 * kept: the report holds counts, positions and stable keys. A caption is compared with "yes" and "no" in memory only.
 */
public final class Extras {

    /** A bound; reaching one refuses the file with its key. */
    public record Limits(int maxPartBytes, int maxControls, int maxLinks, int maxShapes, int maxLinkChars, int maxSheets,
            int maxCols, int maxRows) {
        public static Limits standard() {
            return new Limits(20_000_000, 1_000, 5_000, 5_000, 2_000, 8, 16_384, 1_048_576);
        }
    }

    public enum Caption { YES, NO, OTHER, NONE }

    /** EXACT: the whole box lies in one cell (or one merged area). CENTRE: it spans cells, so the cell holding its centre is used.
     *  NEAREST: the centre lies outside the table, so the nearest cell of the table within three cells is used. NONE: unplaced. */
    public enum Place { EXACT, CENTRE, NEAREST, NONE }

    public enum LinkKind { HTTPS, INTERNAL, REFUSED_SCHEME, TOO_LONG, MALFORMED }

    public record Box(int sheet, long shapeId, boolean checked, Caption caption, Place place, int row, int col, String objectType) {
    }

    public record Pair(int sheet, int yesRow, int yesCol, int noRow, int noCol) {
    }

    public record Image(String type, int bytes, long widthUnits, long heightUnits) {
    }

    /** Counts only. */
    public static final class Report {
        public final List<Box> boxes = new ArrayList<>();
        public final List<Pair> pairs = new ArrayList<>();
        public final Map<LinkKind, Integer> links = new LinkedHashMap<>();
        public int hiddenSheets;
        public int ctrlPropParts;
        public int nonCheckBoxControls;
        public int embeddedObjects;
        public int drawingShapes;
        public int drawingShapesWithText;
        public int drawingShapesOnTable;
        public int drawingTextShapesOnTable;
        public int drawingTextShapesOnEmptyCell;
        public int drawingPictures;
        public int drawingOther;
        public int controlsRefused;
        public int matchedById;
        public int matchedByAnchor;
        public int unmatchedCaption;
        public final List<Image> images = new ArrayList<>();
        public final List<Image> media = new ArrayList<>();
        public int externalLinkParts;
        public int vmlShapes;
        public final List<int[]> embeddedAt = new ArrayList<>(); // {row, column} of each embedded object icon, zero based
        public long millis;

        public int count(Place p) {
            int n = 0;
            for (Box b : boxes) {
                if (b.place() == p) {
                    n++;
                }
            }
            return n;
        }
    }

    /** A refusal with a stable key; never a message built from the file. */
    public static final class Refused extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Refused(String key) {
            super(key, null, false, false);
        }

        public String key() {
            return getMessage();
        }
    }

    private static final String NS_MAIN = "http://schemas.openxmlformats.org/spreadsheetml/2006/main";
    private static final long EMU_PER_PIXEL = 9_525L;

    private Extras() {
    }

    // ---------------------------------------------------------------------------------------------------------------

    public static Report read(byte[] file, Limits lim) {
        long start = System.nanoTime();
        PoiSafe.Limits archive = PoiSafe.Limits.standard();
        if (file.length > archive.maxFileBytes()) {
            throw new Refused("file_too_large");
        }
        int externalLinks;
        try {
            externalLinks = PoiSafe.prescan(file, archive);
        } catch (IOException e) {
            throw new Refused("not_a_zip");
        } catch (RuntimeException e) {
            throw new Refused(e.getMessage() == null ? "archive" : e.getMessage());
        }
        Map<String, byte[]> xml = new HashMap<>();
        Map<String, int[]> binary = new HashMap<>(); // size
        Map<String, byte[]> heads = new HashMap<>();
        readParts(file, lim, xml, binary, heads);
        Report rep = new Report();
        rep.externalLinkParts = externalLinks;
        for (LinkKind k : LinkKind.values()) {
            rep.links.put(k, 0);
        }
        // workbook: the order and state of the sheets, and the part of each
        List<String[]> sheets = workbookSheets(xml, lim); // {name, state, part}
        if (sheets.size() > lim.maxSheets() * 4) {
            throw new Refused("too_many_sheets");
        }
        Styles styles = readStyles(xml.get("xl/styles.xml"));
        int idx = 0;
        for (String[] s : sheets) {
            if (!s[1].equals("visible")) {
                rep.hiddenSheets++;
                idx++;
                continue;
            }
            readSheet(idx, s[2], xml, binary, heads, styles, lim, rep);
            idx++;
        }
        for (String name : binary.keySet()) {
            if (name.startsWith("xl/media/")) {
                rep.media.add(sniff(name, binary, heads));
            }
        }
        rep.millis = (System.nanoTime() - start) / 1_000_000;
        return rep;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // parts

    private static void readParts(byte[] file, Limits lim, Map<String, byte[]> xml, Map<String, int[]> binary,
            Map<String, byte[]> heads) {
        byte[] buffer = new byte[16_384];
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(file))) {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                String name = e.getName();
                boolean text = name.endsWith(".xml") || name.endsWith(".rels") || name.endsWith(".vml");
                ByteArrayOutputStream body = new ByteArrayOutputStream();
                long size = 0;
                int n;
                byte[] head = new byte[64];
                int headLen = 0;
                while ((n = zip.read(buffer)) > 0) {
                    if (headLen < head.length) {
                        int c = Math.min(n, head.length - headLen);
                        System.arraycopy(buffer, 0, head, headLen, c);
                        headLen += c;
                    }
                    size += n;
                    if (size > lim.maxPartBytes()) {
                        throw new Refused("part_too_large");
                    }
                    if (text) {
                        body.write(buffer, 0, n);
                    }
                }
                if (text) {
                    xml.put(name, body.toByteArray());
                } else {
                    binary.put(name, new int[] {(int) size});
                    heads.put(name, java.util.Arrays.copyOf(head, headLen));
                }
            }
        } catch (IOException ex) {
            throw new Refused("not_a_zip");
        }
    }

    private static XMLStreamReader open(byte[] part) {
        // The document type declaration is refused by looking for it (XML is case sensitive; a declaration must be upper case),
        // and the reader is also told to support neither DTDs nor external entities.
        String head = new String(part, 0, Math.min(part.length, 4_096), StandardCharsets.ISO_8859_1);
        if (head.contains("<!DOCTYPE") || head.contains("<!ENTITY")) {
            throw new Refused("doctype");
        }
        XMLInputFactory f = XMLInputFactory.newFactory();
        f.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        f.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        f.setProperty(XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, false);
        f.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, true);
        try {
            return f.createXMLStreamReader(new ByteArrayInputStream(part));
        } catch (XMLStreamException e) {
            throw new Refused("xml");
        }
    }

    private static String attr(XMLStreamReader r, String local) {
        for (int i = 0; i < r.getAttributeCount(); i++) {
            if (r.getAttributeLocalName(i).equals(local)) {
                return r.getAttributeValue(i);
            }
        }
        return null;
    }

    private static long num(String s, long fallback) {
        if (s == null) {
            return fallback;
        }
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static double dbl(String s, double fallback) {
        if (s == null) {
            return fallback;
        }
        try {
            double d = Double.parseDouble(s.trim());
            return Double.isFinite(d) ? d : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String resolve(String base, String target) {
        // base is the folder of the part that holds the relationship ("xl/worksheets/"); only simple relative targets are followed
        String path = target.startsWith("/") ? target.substring(1) : base + target;
        List<String> out = new ArrayList<>();
        for (String seg : path.split("/")) {
            if (seg.equals("..")) {
                if (out.isEmpty()) {
                    throw new Refused("outside_archive");
                }
                out.remove(out.size() - 1);
            } else if (!seg.isEmpty() && !seg.equals(".")) {
                out.add(seg);
            }
        }
        return String.join("/", out);
    }

    private static Map<String, String[]> relationships(byte[] rels, String base) {
        Map<String, String[]> map = new HashMap<>(); // id -> {type tail, target, mode}
        if (rels == null) {
            return map;
        }
        XMLStreamReader r = open(rels);
        try {
            int count = 0;
            while (r.hasNext()) {
                if (r.next() == XMLStreamConstants.START_ELEMENT && r.getLocalName().equals("Relationship")) {
                    if (++count > 20_000) {
                        throw new Refused("too_many_relationships");
                    }
                    String type = attr(r, "Type");
                    String tail = type == null ? "" : type.substring(type.lastIndexOf('/') + 1);
                    String target = attr(r, "Target");
                    String mode = attr(r, "TargetMode");
                    if (target != null && attr(r, "Id") != null) {
                        boolean external = "External".equals(mode);
                        map.put(attr(r, "Id"), new String[] {tail, external ? target : resolve(base, target), external ? "E" : "I"});
                    }
                }
            }
        } catch (XMLStreamException e) {
            throw new Refused("xml");
        }
        return map;
    }

    private static List<String[]> workbookSheets(Map<String, byte[]> xml, Limits lim) {
        byte[] wb = xml.get("xl/workbook.xml");
        if (wb == null) {
            throw new Refused("not_xlsx");
        }
        Map<String, String[]> rels = relationships(xml.get("xl/_rels/workbook.xml.rels"), "xl/");
        List<String[]> out = new ArrayList<>();
        XMLStreamReader r = open(wb);
        try {
            while (r.hasNext()) {
                if (r.next() == XMLStreamConstants.START_ELEMENT && r.getLocalName().equals("sheet")) {
                    String state = attr(r, "state");
                    String rid = null;
                    for (int i = 0; i < r.getAttributeCount(); i++) {
                        if (r.getAttributeLocalName(i).equals("id")) {
                            rid = r.getAttributeValue(i);
                        }
                    }
                    String[] rel = rid == null ? null : rels.get(rid);
                    if (rel == null || !rel[0].equals("worksheet")) {
                        continue;
                    }
                    out.add(new String[] {"", state == null ? "visible" : state, rel[1]});
                    if (out.size() > lim.maxSheets() * 4) {
                        throw new Refused("too_many_sheets");
                    }
                }
            }
        } catch (XMLStreamException e) {
            throw new Refused("xml");
        }
        return out;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // styles: which cell formats have a border (the extent rule of ADR-0250)

    private record Styles(Set<Integer> borderedFormats) {
    }

    private static Styles readStyles(byte[] part) {
        Set<Integer> bordered = new HashSet<>();
        if (part == null) {
            return new Styles(bordered);
        }
        List<Boolean> borders = new ArrayList<>();
        XMLStreamReader r = open(part);
        try {
            boolean inBorders = false;
            boolean inXfs = false;
            boolean current = false;
            int depthInBorder = 0;
            int xf = 0;
            while (r.hasNext()) {
                int ev = r.next();
                if (ev == XMLStreamConstants.START_ELEMENT) {
                    String n = r.getLocalName();
                    if (n.equals("borders")) {
                        inBorders = true;
                    } else if (inBorders && n.equals("border")) {
                        current = false;
                        depthInBorder = 1;
                        if (borders.size() > 64_000) {
                            throw new Refused("styles");
                        }
                    } else if (inBorders && depthInBorder == 1 && (n.equals("left") || n.equals("right") || n.equals("top")
                            || n.equals("bottom"))) {
                        String st = attr(r, "style");
                        if (st != null && !st.equals("none")) {
                            current = true;
                        }
                    } else if (n.equals("cellXfs")) {
                        inXfs = true;
                    } else if (inXfs && n.equals("xf")) {
                        long b = num(attr(r, "borderId"), 0);
                        if (b >= 0 && b < borders.size() && borders.get((int) b)) {
                            bordered.add(xf);
                        }
                        xf++;
                        if (xf > 64_000) {
                            throw new Refused("styles");
                        }
                    }
                } else if (ev == XMLStreamConstants.END_ELEMENT) {
                    String n = r.getLocalName();
                    if (n.equals("borders")) {
                        inBorders = false;
                    } else if (inBorders && n.equals("border")) {
                        borders.add(current);
                        depthInBorder = 0;
                    } else if (n.equals("cellXfs")) {
                        inXfs = false;
                    }
                }
            }
        } catch (XMLStreamException e) {
            throw new Refused("xml");
        }
        return new Styles(bordered);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // one worksheet

    private record Anchor(long fromCol, long fromColOff, long fromRow, long fromRowOff, long toCol, long toColOff, long toRow,
            long toRowOff) {
    }

    private record RawControl(long shapeId, String rid, Anchor anchor) {
    }

    private record Merge(int r0, int c0, int r1, int c1) {
    }

    private static final class Geometry {
        final Map<Integer, Double> colWidthChars = new HashMap<>();
        final Set<Integer> hiddenCols = new HashSet<>();
        final Map<Integer, Double> rowHeightPt = new HashMap<>();
        final Set<Integer> hiddenRows = new HashSet<>();
        double defaultColChars = 8.43 + 0.71;
        double defaultRowPt = 15;
        final List<Merge> merges = new ArrayList<>();
        final Set<Long> valued = new HashSet<>();
        // extent of the table: cells with text or a border, and merged areas holding a bordered cell
        int minRow = Integer.MAX_VALUE;
        int minCol = Integer.MAX_VALUE;
        int maxRow = -1;
        int maxCol = -1;

        long colPx(int c) {
            if (hiddenCols.contains(c)) {
                return 0;
            }
            Double w = colWidthChars.get(c);
            return Math.round((w == null ? defaultColChars : w) * 7);
        }

        long rowPx(int r) {
            if (hiddenRows.contains(r)) {
                return 0;
            }
            Double h = rowHeightPt.get(r);
            return Math.round((h == null ? defaultRowPt : h) * 96 / 72);
        }

        void include(int r, int c) {
            minRow = Math.min(minRow, r);
            minCol = Math.min(minCol, c);
            maxRow = Math.max(maxRow, r);
            maxCol = Math.max(maxCol, c);
        }
    }

    private static void readSheet(int sheetIndex, String part, Map<String, byte[]> xml, Map<String, int[]> binary,
            Map<String, byte[]> heads, Styles styles, Limits lim, Report rep) {
        byte[] sheet = xml.get(part);
        if (sheet == null) {
            throw new Refused("missing_part");
        }
        String base = part.substring(0, part.lastIndexOf('/') + 1);
        String relName = base + "_rels/" + part.substring(part.lastIndexOf('/') + 1) + ".rels";
        Map<String, String[]> rels = relationships(xml.get(relName), base);
        Geometry g = new Geometry();
        List<RawControl> controls = new ArrayList<>();
        Set<Long> controlIds = new HashSet<>();
        Set<Long> oleIds = new HashSet<>();
        List<String[]> hyperlinks = new ArrayList<>(); // {location, rid}
        String drawingRid = null;
        String legacyRid = null;

        XMLStreamReader r = open(sheet);
        try {
            long cellsRead = 0;
            long curShape = -1;
            String curRid = null;
            long[] a = new long[8];
            int aPos = -1; // 0..3 from, 4..7 to
            boolean inFrom = false;
            boolean inTo = false;
            String leaf = null;
            boolean inControl = false;
            int cellRow = 0;
            int cellCol = 0;
            while (r.hasNext()) {
                int ev = r.next();
                if (ev == XMLStreamConstants.START_ELEMENT) {
                    boolean main = NS_MAIN.equals(r.getNamespaceURI());
                    String n = main ? r.getLocalName() : "x:" + r.getLocalName();
                    switch (n) {
                        case "sheetFormatPr" -> {
                            g.defaultRowPt = dbl(attr(r, "defaultRowHeight"), 15);
                            double def = dbl(attr(r, "defaultColWidth"), -1);
                            if (def > 0) {
                                g.defaultColChars = def;
                            } else {
                                g.defaultColChars = Math.floor((dbl(attr(r, "baseColWidth"), 8) * 7 + 5) / 7 * 256) / 256;
                            }
                        }
                        case "col" -> {
                            long min = num(attr(r, "min"), 0);
                            long max = num(attr(r, "max"), 0);
                            if (min >= 1 && max >= min && max <= lim.maxCols()) {
                                double w = dbl(attr(r, "width"), -1);
                                boolean hidden = "1".equals(attr(r, "hidden"));
                                for (long c = min; c <= max; c++) {
                                    if (w > 0) {
                                        g.colWidthChars.put((int) (c - 1), w);
                                    }
                                    if (hidden) {
                                        g.hiddenCols.add((int) (c - 1));
                                    }
                                }
                            }
                        }
                        case "row" -> {
                            long rn = num(attr(r, "r"), 0);
                            if (rn >= 1 && rn <= lim.maxRows()) {
                                cellRow = (int) rn - 1;
                                double ht = dbl(attr(r, "ht"), -1);
                                if (ht > 0) {
                                    g.rowHeightPt.put(cellRow, ht);
                                }
                                if ("1".equals(attr(r, "hidden"))) {
                                    g.hiddenRows.add(cellRow);
                                }
                            }
                        }
                        case "c" -> {
                            cellsRead++;
                            if (cellsRead > 2_000_000) {
                                throw new Refused("too_many_cells");
                            }
                            String ref = attr(r, "r");
                            cellCol = columnOf(ref);
                            long s = num(attr(r, "s"), 0);
                            if (styles.borderedFormats().contains((int) s) && cellCol >= 0) {
                                g.include(cellRow, cellCol);
                            }
                        }
                        case "v", "t", "f" -> {
                            // a cell with a value or inline text is part of the table
                            if (cellCol >= 0) {
                                String text = null;
                                try {
                                    text = r.getElementText();
                                } catch (XMLStreamException e) {
                                    throw new Refused("xml");
                                }
                                if (text != null && !text.isBlank() && !n.equals("f")) {
                                    g.include(cellRow, cellCol);
                                    g.valued.add((long) cellRow * 16_384L + cellCol);
                                }
                            }
                        }
                        case "mergeCell" -> {
                            String ref = attr(r, "ref");
                            if (ref != null && ref.contains(":")) {
                                String[] p = ref.split(":");
                                int c0 = columnOf(p[0]);
                                int c1 = columnOf(p[1]);
                                int r0 = rowOf(p[0]);
                                int r1 = rowOf(p[1]);
                                if (c0 >= 0 && c1 >= c0 && r0 >= 0 && r1 >= r0 && g.merges.size() < 50_000) {
                                    g.merges.add(new Merge(r0, c0, r1, c1));
                                }
                            }
                        }
                        case "control" -> {
                            curShape = num(attr(r, "shapeId"), -1);
                            curRid = null;
                            for (int i = 0; i < r.getAttributeCount(); i++) {
                                if (r.getAttributeLocalName(i).equals("id")) {
                                    curRid = r.getAttributeValue(i);
                                }
                            }
                            inControl = true;
                            java.util.Arrays.fill(a, Long.MIN_VALUE);
                            if (controls.size() >= lim.maxControls()) {
                                throw new Refused("too_many_controls");
                            }
                        }
                        case "from", "x:from" -> inFrom = inControl;
                        case "to", "x:to" -> inTo = inControl;
                        case "oleObject" -> {
                            long sid = num(attr(r, "shapeId"), -1);
                            oleIds.add(sid);
                            if (oleIds.size() > lim.maxShapes()) {
                                throw new Refused("too_many_shapes");
                            }
                        }
                        case "hyperlink" -> {
                            if (hyperlinks.size() >= lim.maxLinks()) {
                                throw new Refused("too_many_links");
                            }
                            String rid = null;
                            for (int i = 0; i < r.getAttributeCount(); i++) {
                                if (r.getAttributeLocalName(i).equals("id")) {
                                    rid = r.getAttributeValue(i);
                                }
                            }
                            hyperlinks.add(new String[] {attr(r, "location"), rid});
                        }
                        case "drawing" -> drawingRid = relId(r);
                        case "legacyDrawing" -> legacyRid = relId(r);
                        default -> { }
                    }
                    // namespaced leaves of the drawing anchor (xdr:col and so on) carry their number as text
                    if ((inFrom || inTo) && (n.equals("x:col") || n.equals("x:colOff") || n.equals("x:row") || n.equals("x:rowOff"))) {
                        leaf = n.substring(2);
                    }
                } else if (ev == XMLStreamConstants.CHARACTERS && leaf != null && (inFrom || inTo)) {
                    int off = inFrom ? 0 : 4;
                    int k = switch (leaf) {
                        case "col" -> 0;
                        case "colOff" -> 1;
                        case "row" -> 2;
                        default -> 3;
                    };
                    a[off + k] = num(r.getText(), Long.MIN_VALUE);
                    leaf = null;
                } else if (ev == XMLStreamConstants.END_ELEMENT) {
                    String n = r.getLocalName();
                    if (n.equals("from")) {
                        inFrom = false;
                    } else if (n.equals("to")) {
                        inTo = false;
                    } else if (n.equals("col") || n.equals("colOff") || n.equals("row") || n.equals("rowOff")) {
                        leaf = null;
                    } else if (n.equals("control") && inControl) {
                        inControl = false;
                        if (controlIds.add(curShape)) {
                            controls.add(new RawControl(curShape, curRid, new Anchor(a[0], a[1], a[2], a[3], a[4], a[5], a[6], a[7])));
                        }
                    }
                }
            }
        } catch (XMLStreamException e) {
            throw new Refused("xml");
        }
        rep.embeddedObjects += oleIds.size();

        // links
        for (String[] h : hyperlinks) {
            rep.links.merge(classify(h, rels, lim), 1, Integer::sum);
        }

        // extent: merged areas that hold a bordered or valued cell count whole
        for (Merge m : g.merges) {
            if (g.minRow != Integer.MAX_VALUE && m.r0() >= g.minRow && m.c0() >= g.minCol && m.r0() <= g.maxRow && m.c0() <= g.maxCol) {
                g.include(m.r1(), m.c1());
            }
        }

        // the drawing layer
        if (drawingRid != null) {
            String[] rel = rels.get(drawingRid);
            if (rel != null && rel[2].equals("I") && xml.get(rel[1]) != null) {
                readDrawing(rel[1], xml, binary, heads, lim, rep, g);
            }
        }

        // legacy drawing: captions of the controls
        Map<Long, Caption> captions = new HashMap<>();
        List<Vml> vml = new ArrayList<>();
        if (legacyRid != null) {
            String[] rel = rels.get(legacyRid);
            if (rel != null && rel[2].equals("I") && xml.get(rel[1]) != null) {
                scanVml(xml.get(rel[1]), captions, vml, lim, rep);
            }
        }
        // Boxes whose shape number the legacy drawing does not know are paired with the legacy shapes no number claimed: in one
        // cell, in the order of their left edge, and only when the counts in that cell are equal.
        Set<Long> claimed = new HashSet<>();
        for (RawControl c : controls) {
            if (captions.containsKey(c.shapeId())) {
                claimed.add(c.shapeId());
            }
        }
        Map<Long, Caption> byPosition = new HashMap<>();
        Map<String, List<RawControl>> loose = new HashMap<>();
        for (RawControl c : controls) {
            if (!claimed.contains(c.shapeId())) {
                loose.computeIfAbsent(c.anchor().fromCol() + ":" + c.anchor().fromRow(), k -> new ArrayList<>()).add(c);
            }
        }
        for (Map.Entry<String, List<RawControl>> e : loose.entrySet()) {
            List<RawControl> cs = new ArrayList<>(e.getValue());
            cs.sort(java.util.Comparator.comparingLong(x -> x.anchor().fromColOff()));
            String[] rc = e.getKey().split(":");
            List<Vml> vs = new ArrayList<>();
            for (Vml v : vml) {
                boolean numbered = controlIds.contains(v.id());
                if (!numbered && v.col() == Long.parseLong(rc[0]) && v.row() == Long.parseLong(rc[1])) {
                    vs.add(v);
                }
            }
            vs.sort(java.util.Comparator.comparingInt(Vml::colOffPx));
            if (cs.size() == vs.size()) {
                for (int i = 0; i < cs.size(); i++) {
                    byPosition.put(cs.get(i).shapeId(), vs.get(i).caption());
                }
            }
        }

        // control properties and placement
        for (RawControl c : controls) {
            String[] rel = c.rid() == null ? null : rels.get(c.rid());
            String objectType = "";
            boolean checked = false;
            if (rel != null && rel[0].equals("ctrlProp") && rel[2].equals("I") && xml.get(rel[1]) != null) {
                rep.ctrlPropParts++;
                XMLStreamReader pr = open(xml.get(rel[1]));
                try {
                    while (pr.hasNext()) {
                        if (pr.next() == XMLStreamConstants.START_ELEMENT && pr.getLocalName().equals("formControlPr")) {
                            objectType = String.valueOf(attr(pr, "objectType"));
                            checked = "Checked".equals(attr(pr, "checked"));
                            break;
                        }
                    }
                } catch (XMLStreamException e) {
                    throw new Refused("xml");
                }
            }
            if (!objectType.equals("CheckBox")) {
                rep.nonCheckBoxControls++;
                continue;
            }
            Anchor an = c.anchor();
            if (an.fromCol() < 0 || an.fromRow() < 0 || an.toCol() < an.fromCol() || an.toRow() < an.fromRow()
                    || an.toCol() >= lim.maxCols() || an.toRow() >= lim.maxRows() || an.fromColOff() == Long.MIN_VALUE
                    || an.toColOff() == Long.MIN_VALUE || an.fromRowOff() == Long.MIN_VALUE || an.toRowOff() == Long.MIN_VALUE) {
                rep.controlsRefused++;
                continue;
            }
            int[] cell = new int[2];
            Place place = place(an, g, cell);
            Caption cap = captions.get(c.shapeId());
            if (cap != null) {
                rep.matchedById++;
            } else {
                cap = byPosition.get(c.shapeId());
                if (cap != null) {
                    rep.matchedByAnchor++;
                } else {
                    rep.unmatchedCaption++;
                    cap = Caption.NONE;
                }
            }
            rep.boxes.add(new Box(sheetIndex, c.shapeId(), checked, cap, place, cell[0], cell[1], objectType));
        }
        pairUp(sheetIndex, rep);
    }

    private static String relId(XMLStreamReader r) {
        for (int i = 0; i < r.getAttributeCount(); i++) {
            if (r.getAttributeLocalName(i).equals("id")) {
                return r.getAttributeValue(i);
            }
        }
        return null;
    }

    private static int columnOf(String ref) {
        if (ref == null) {
            return -1;
        }
        int v = 0;
        int i = 0;
        while (i < ref.length() && Character.isLetter(ref.charAt(i)) && i < 4) {
            v = v * 26 + (Character.toUpperCase(ref.charAt(i)) - 'A' + 1);
            i++;
        }
        return i == 0 ? -1 : v - 1;
    }

    private static int rowOf(String ref) {
        if (ref == null) {
            return -1;
        }
        int i = 0;
        while (i < ref.length() && Character.isLetter(ref.charAt(i))) {
            i++;
        }
        try {
            return Integer.parseInt(ref.substring(i)) - 1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // placement of a box in a cell

    private static long colStart(Geometry g, long col) {
        long x = 0;
        for (int c = 0; c < col; c++) {
            x += g.colPx(c);
        }
        return x * EMU_PER_PIXEL;
    }

    private static long rowStart(Geometry g, long row) {
        long y = 0;
        for (int r = 0; r < row; r++) {
            y += g.rowPx(r);
        }
        return y * EMU_PER_PIXEL;
    }

    private static int colAt(Geometry g, long x) {
        long acc = 0;
        for (int c = 0; c < 16_384; c++) {
            acc += g.colPx(c) * EMU_PER_PIXEL;
            if (x < acc) {
                return c;
            }
        }
        return 16_383;
    }

    private static int rowAt(Geometry g, long y) {
        long acc = 0;
        for (int r = 0; r < 1_048_576; r++) {
            acc += g.rowPx(r) * EMU_PER_PIXEL;
            if (y < acc) {
                return r;
            }
        }
        return 1_048_575;
    }

    private static Merge mergeOf(Geometry g, int r, int c) {
        for (Merge m : g.merges) {
            if (r >= m.r0() && r <= m.r1() && c >= m.c0() && c <= m.c1()) {
                return m;
            }
        }
        return null;
    }

    private static Place place(Anchor a, Geometry g, int[] out) {
        long x0 = colStart(g, a.fromCol()) + a.fromColOff();
        long x1 = colStart(g, a.toCol()) + a.toColOff();
        long y0 = rowStart(g, a.fromRow()) + a.fromRowOff();
        long y1 = rowStart(g, a.toRow()) + a.toRowOff();
        int cc = colAt(g, (x0 + x1) / 2);
        int cr = rowAt(g, (y0 + y1) / 2);
        // the cell of a merged area is its top left cell
        Merge m = mergeOf(g, cr, cc);
        int r = m == null ? cr : m.r0();
        int c = m == null ? cc : m.c0();
        boolean inside = g.maxRow >= 0 && cr >= g.minRow && cr <= g.maxRow && cc >= g.minCol && cc <= g.maxCol;
        if (!inside) {
            if (g.maxRow < 0) {
                return Place.NONE;
            }
            int nr = Math.max(g.minRow, Math.min(g.maxRow, cr));
            int nc = Math.max(g.minCol, Math.min(g.maxCol, cc));
            if (Math.max(Math.abs(nr - cr), Math.abs(nc - cc)) > 3) {
                return Place.NONE;
            }
            out[0] = nr;
            out[1] = nc;
            return Place.NEAREST;
        }
        out[0] = r;
        out[1] = c;
        // exact: both corners of the box fall in the same cell or in the same merged area
        int fr = rowAt(g, y0);
        int fc = colAt(g, x0);
        int lr = rowAt(g, Math.max(y0, y1 - 1));
        int lc = colAt(g, Math.max(x0, x1 - 1));
        Merge mf = mergeOf(g, fr, fc);
        Merge ml = mergeOf(g, lr, lc);
        boolean oneCell = fr == lr && fc == lc;
        boolean oneArea = mf != null && mf == ml;
        return oneCell || oneArea ? Place.EXACT : Place.CENTRE;
    }

    /** A Yes box and a No box next to each other on one row (or stacked in one column) are one question. */
    private static void pairUp(int sheet, Report rep) {
        List<Box> mine = new ArrayList<>();
        for (Box b : rep.boxes) {
            if (b.sheet() == sheet && b.place() != Place.NONE) {
                mine.add(b);
            }
        }
        Set<Long> used = new HashSet<>();
        for (Box y : mine) {
            if (y.caption() != Caption.YES || used.contains(y.shapeId())) {
                continue;
            }
            Box best = null;
            int bestD = Integer.MAX_VALUE;
            for (Box n : mine) {
                if (n.caption() != Caption.NO || used.contains(n.shapeId())) {
                    continue;
                }
                int dr = Math.abs(n.row() - y.row());
                int dc = Math.abs(n.col() - y.col());
                boolean sameRow = dr == 0 && dc <= 4;
                boolean sameCol = dc == 0 && dr <= 2;
                if ((sameRow || sameCol) && dr + dc < bestD) {
                    bestD = dr + dc;
                    best = n;
                }
            }
            if (best != null) {
                used.add(y.shapeId());
                used.add(best.shapeId());
                rep.pairs.add(new Pair(sheet, y.row(), y.col(), best.row(), best.col()));
            }
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // links

    private static LinkKind classify(String[] h, Map<String, String[]> rels, Limits lim) {
        String location = h[0];
        String rid = h[1];
        if (rid == null) {
            return location == null ? LinkKind.MALFORMED : LinkKind.INTERNAL;
        }
        String[] rel = rels.get(rid);
        if (rel == null || !rel[2].equals("E")) {
            return LinkKind.MALFORMED;
        }
        String target = rel[1];
        if (target.length() > lim.maxLinkChars()) {
            return LinkKind.TOO_LONG;
        }
        for (int i = 0; i < target.length(); i++) {
            char ch = target.charAt(i);
            if (ch <= 0x20 || ch == 0x7f || ch == '\\') {
                return LinkKind.MALFORMED;
            }
        }
        try {
            URI u = new URI(target);
            if (u.getScheme() == null) {
                return LinkKind.MALFORMED;
            }
            if (u.getScheme().toLowerCase(Locale.ROOT).equals("https") && u.getHost() != null && u.getUserInfo() == null) {
                return LinkKind.HTTPS;
            }
            return LinkKind.REFUSED_SCHEME;
        } catch (java.net.URISyntaxException e) {
            return LinkKind.MALFORMED;
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // the drawing layer (counts and the kind of every picture, never the text)

    private static void readDrawing(String part, Map<String, byte[]> xml, Map<String, int[]> binary, Map<String, byte[]> heads,
            Limits lim, Report rep, Geometry g) {
        String base = part.substring(0, part.lastIndexOf('/') + 1);
        Map<String, String[]> rels = relationships(xml.get(base + "_rels/" + part.substring(part.lastIndexOf('/') + 1) + ".rels"), base);
        XMLStreamReader r = open(xml.get(part));
        try {
            int shapes = 0;
            boolean inSp = false;
            boolean spHasText = false;
            boolean inT = false;
            boolean inFrom = false;
            String leaf = null;
            long fromCol = -1;
            long fromRow = -1;
            while (r.hasNext()) {
                int ev = r.next();
                if (ev == XMLStreamConstants.START_ELEMENT) {
                    String n = r.getLocalName();
                    if (n.equals("from")) {
                        inFrom = true;
                    } else if (inFrom && (n.equals("col") || n.equals("row"))) {
                        leaf = n;
                    }
                    if (n.equals("sp")) {
                        rep.drawingShapes++;
                        inSp = true;
                        spHasText = false;
                        if (++shapes > lim.maxShapes()) {
                            throw new Refused("too_many_shapes");
                        }
                    } else if (n.equals("pic")) {
                        rep.drawingPictures++;
                        shapes++;
                    } else if (n.equals("graphicFrame") || n.equals("cxnSp") || n.equals("grpSp")) {
                        rep.drawingOther++;
                        shapes++;
                    } else if (n.equals("t") && inSp) {
                        inT = true;
                    } else if (n.equals("blip")) {
                        String rid = null;
                        for (int i = 0; i < r.getAttributeCount(); i++) {
                            if (r.getAttributeLocalName(i).equals("embed")) {
                                rid = r.getAttributeValue(i);
                            }
                        }
                        String[] rel = rid == null ? null : rels.get(rid);
                        if (rel != null && rel[2].equals("I")) {
                            rep.images.add(sniff(rel[1], binary, heads));
                        }
                    }
                } else if (ev == XMLStreamConstants.CHARACTERS && leaf != null) {
                    if (leaf.equals("col")) {
                        fromCol = num(r.getText(), -1);
                    } else {
                        fromRow = num(r.getText(), -1);
                    }
                    leaf = null;
                } else if (ev == XMLStreamConstants.CHARACTERS && inT) {
                    if (!r.getText().isBlank()) {
                        spHasText = true;
                    }
                } else if (ev == XMLStreamConstants.END_ELEMENT) {
                    String n = r.getLocalName();
                    if (n.equals("from")) {
                        inFrom = false;
                    } else if (n.equals("t")) {
                        inT = false;
                    } else if (n.equals("sp")) {
                        inSp = false;
                        boolean inside = g.maxRow >= 0 && fromRow >= g.minRow && fromRow <= g.maxRow && fromCol >= g.minCol
                                && fromCol <= g.maxCol;
                        if (inside) {
                            rep.drawingShapesOnTable++;
                        }
                        if (spHasText) {
                            rep.drawingShapesWithText++;
                            if (inside) {
                                rep.drawingTextShapesOnTable++;
                                Merge mm = mergeOf(g, (int) fromRow, (int) fromCol);
                                long key = mm == null ? fromRow * 16_384L + fromCol : (long) mm.r0() * 16_384L + mm.c0();
                                if (!g.valued.contains(key)) {
                                    rep.drawingTextShapesOnEmptyCell++;
                                }
                            }
                        }
                    }
                }
            }
        } catch (XMLStreamException e) {
            throw new Refused("xml");
        }
    }

    /** The kind of a picture from its first bytes, and for a vector metafile its size; never its content. */
    static Image sniff(String part, Map<String, int[]> binary, Map<String, byte[]> heads) {
        byte[] h = heads.get(part);
        int[] size = binary.get(part);
        if (h == null || size == null) {
            return new Image("missing", 0, 0, 0);
        }
        if (h.length >= 8 && (h[0] & 0xff) == 0x89 && h[1] == 'P' && h[2] == 'N' && h[3] == 'G') {
            return new Image("png", size[0], 0, 0);
        }
        if (h.length >= 3 && (h[0] & 0xff) == 0xff && (h[1] & 0xff) == 0xd8 && (h[2] & 0xff) == 0xff) {
            return new Image("jpeg", size[0], 0, 0);
        }
        if (h.length >= 44 && h[0] == 1 && h[1] == 0 && h[2] == 0 && h[3] == 0 && h[40] == ' ' && h[41] == 'E' && h[42] == 'M'
                && h[43] == 'F') {
            long w = (int) (u32(h, 8 + 8) - u32(h, 8));
            long hgt = (int) (u32(h, 8 + 12) - u32(h, 8 + 4));
            return new Image("emf", size[0], w, hgt);
        }
        if (h.length >= 4 && (h[0] & 0xff) == 0xd7 && (h[1] & 0xff) == 0xcd) {
            return new Image("wmf", size[0], 0, 0);
        }
        return new Image("other", size[0], 0, 0);
    }

    private static long u32(byte[] b, int o) {
        return (b[o] & 0xffL) | (b[o + 1] & 0xffL) << 8 | (b[o + 2] & 0xffL) << 16 | (b[o + 3] & 0xffL) << 24;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // the legacy (VML) drawing: a bounded text scan, no XML parser, no entity expanded

    private record Vml(long id, int col, int colOffPx, int row, Caption caption) {
    }

    private static void scanVml(byte[] part, Map<Long, Caption> captions, List<Vml> vml, Limits lim, Report rep) {
        if (part.length > 4_000_000) {
            throw new Refused("drawing_too_large");
        }
        String s = new String(part, StandardCharsets.UTF_8);
        int pos = 0;
        int shapes = 0;
        while (true) {
            int a = s.indexOf("<v:shape ", pos);
            if (a < 0) {
                break;
            }
            int end = s.indexOf("</v:shape>", a);
            if (end < 0) {
                break;
            }
            if (++shapes > lim.maxShapes()) {
                throw new Refused("too_many_shapes");
            }
            String shape = s.substring(a, end);
            long id = -1;
            int idAt = shape.indexOf("id=\"_x0000_s");
            if (idAt >= 0) {
                int from = idAt + "id=\"_x0000_s".length();
                int to = from;
                while (to < shape.length() && Character.isDigit(shape.charAt(to)) && to - from < 12) {
                    to++;
                }
                if (to > from) {
                    id = Long.parseLong(shape.substring(from, to));
                }
            }
            int tb = shape.indexOf("<v:textbox");
            Caption caption = Caption.NONE;
            if (tb >= 0) {
                int tbEnd = shape.indexOf("</v:textbox>", tb);
                String inner = shape.substring(tb, tbEnd < 0 ? shape.length() : tbEnd);
                StringBuilder text = new StringBuilder();
                boolean tag = false;
                for (int i = 0; i < inner.length(); i++) {
                    char ch = inner.charAt(i);
                    if (ch == '<') {
                        tag = true;
                    } else if (ch == '>') {
                        tag = false;
                    } else if (!tag) {
                        text.append(ch);
                    }
                }
                String t = text.toString().trim().toLowerCase(Locale.ROOT);
                caption = t.isEmpty() ? Caption.NONE : t.equals("yes") ? Caption.YES : t.equals("no") ? Caption.NO : Caption.OTHER;
            }
            if (shape.contains("ObjectType=\"Pict\"")) {
                // an embedded object drawn as an icon: only the cell it stands in is kept (column, row)
                int an = shape.indexOf("<x:Anchor>");
                int anEnd = an < 0 ? -1 : shape.indexOf("</x:Anchor>", an);
                if (an >= 0 && anEnd > an && anEnd - an < 200) {
                    String[] p = shape.substring(an + 10, anEnd).replaceAll("\\s+", "").split(",");
                    if (p.length >= 4 && rep.embeddedAt.size() < 5000) {
                        rep.embeddedAt.add(new int[] {(int) num(p[2], -1), (int) num(p[0], -1)});
                    }
                }
            }
            if (id >= 0 && shape.contains("ObjectType=\"Checkbox\"")) {
                captions.put(id, caption);
                // the same box is also found by its anchor (columns, rows and offsets in pixels), for the files in which the
                // shape numbers of the sheet and of the legacy drawing differ
                int an = shape.indexOf("<x:Anchor>");
                int anEnd = an < 0 ? -1 : shape.indexOf("</x:Anchor>", an);
                if (an >= 0 && anEnd > an && anEnd - an < 200) {
                    String[] p = shape.substring(an + 10, anEnd).replaceAll("\\s+", "").split(",");
                    if (p.length >= 4) {
                        vml.add(new Vml(id, (int) num(p[0], -1), (int) num(p[1], 0), (int) num(p[2], -1), caption));
                    }
                }
            }
            pos = end + 10;
        }
        rep.vmlShapes += shapes;
    }

    // ---------------------------------------------------------------------------------------------------------------

    public static void main(String[] args) throws Exception {
        for (String path : args) {
            byte[] file = java.nio.file.Files.readAllBytes(java.nio.file.Path.of(path));
            Report r;
            try {
                r = read(file, Limits.standard());
            } catch (Refused e) {
                System.out.println("REFUSED " + e.key());
                continue;
            }
            System.out.println("check boxes: " + r.boxes.size() + " (ticked " + r.boxes.stream().filter(Box::checked).count()
                    + ") placed exact " + r.count(Place.EXACT) + ", by centre " + r.count(Place.CENTRE) + ", nearest "
                    + r.count(Place.NEAREST) + ", none " + r.count(Place.NONE));
            long yes = r.boxes.stream().filter(b -> b.caption() == Caption.YES).count();
            long no = r.boxes.stream().filter(b -> b.caption() == Caption.NO).count();
            long other = r.boxes.stream().filter(b -> b.caption() == Caption.OTHER).count();
            long none = r.boxes.stream().filter(b -> b.caption() == Caption.NONE).count();
            System.out.println("captions: yes " + yes + ", no " + no + ", other " + other + ", none " + none + "; pairs " + r.pairs.size()
                    + "; caption found by shape number " + r.matchedById + ", by anchor " + r.matchedByAnchor + ", not found "
                    + r.unmatchedCaption);
            System.out.println("links: " + r.links + "; embedded objects " + r.embeddedObjects + "; drawing shapes "
                    + r.drawingShapes + " (with text " + r.drawingShapesWithText + "; starting inside the table "
                    + r.drawingShapesOnTable + ", of which with text " + r.drawingTextShapesOnTable + ", starting on a cell with no text "
                    + r.drawingTextShapesOnEmptyCell + "), pictures " + r.drawingPictures
                    + ", other " + r.drawingOther + "; vml shapes " + r.vmlShapes + "; ctrlProp parts " + r.ctrlPropParts
                    + "; other controls " + r.nonCheckBoxControls + "; refused controls " + r.controlsRefused
                    + "; hidden sheets " + r.hiddenSheets + "; external link parts " + r.externalLinkParts);
            for (Image i : r.images) {
                System.out.println("picture in the drawing: " + i.type() + " " + i.bytes() + " bytes " + i.widthUnits() + "x" + i.heightUnits());
            }
            for (Image i : r.media) {
                System.out.println("media part: " + i.type() + " " + i.bytes() + " bytes, header size " + i.widthUnits() + "x" + i.heightUnits()
                        + " device units");
            }
            if (args.length == 1 || System.getProperty("positions") != null) {
                StringBuilder sb = new StringBuilder("positions (row,col,place,caption,ticked):");
                for (Box b : r.boxes) {
                    sb.append(" [").append(b.row()).append(',').append(b.col()).append(',').append(b.place().name().charAt(0))
                            .append(',').append(switch (b.caption()) {
                                case YES -> 'Y';
                                case NO -> 'O';
                                case OTHER -> '?';
                                case NONE -> '-';
                            }).append(b.checked() ? ",T" : "").append(']');
                }
                System.out.println(sb);
                StringBuilder ps = new StringBuilder("pairs (yes row,col -> no row,col):");
                for (Pair p : r.pairs) {
                    ps.append(" [").append(p.yesRow()).append(',').append(p.yesCol()).append("->").append(p.noRow()).append(',')
                            .append(p.noCol()).append(']');
                }
                System.out.println(ps);
            }
            System.out.println("read in " + r.millis + " ms");
        }
    }
}
