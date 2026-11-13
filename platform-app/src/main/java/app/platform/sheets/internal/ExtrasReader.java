package app.platform.sheets.internal;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * Reads what a worksheet holds that is not a cell and that the library does not give (SH1B, ADR-0253 amendment from
 * the second proof of concept): check boxes, links, the shapes of the drawing layer and the places where embedded
 * files stood. It reads from the bytes the archive scan already vetted, with the same stream reader of ours (the
 * document type refused, nothing resolved, nothing fetched); the old-style drawing part is not well-formed XML, so a
 * bounded text scanner reads it, expanding no entity and running nothing.
 *
 * <p><strong>What it keeps.</strong> Counts, cell positions and the https address of a link. It keeps no name, no
 * caption, no file name, no picture byte and no text of a shape: a caption is compared with "yes" and "no" in memory
 * and dropped. A check box is a control drawn over the cells; it is placed in the cell that holds the whole of it, else
 * in the cell that holds its centre, else in the nearest cell of the table within three cells, and otherwise it is
 * reported as not placed, never guessed. A Yes box and a No box in one cell, in one row up to four cells apart or in
 * one column up to two rows apart are one question and become one yes/no cell. Embedded files, other controls,
 * shapes, pictures and links that do not lead to a secure web address are counted and never run or followed.
 */
final class ExtrasReader {

    private static final int MAX_CONTROLS = 1_000;
    private static final int MAX_LINKS = 5_000;
    private static final int MAX_SHAPES = 5_000;
    private static final int MAX_LINK_CHARS = 2_000;
    private static final int MAX_RELATIONSHIPS = 20_000;
    private static final int MAX_DRAWING_BYTES = 4_000_000;
    private static final int MAX_COLUMN = 16_384;
    private static final int MAX_ROW = 1_048_576;
    private static final long EMU_PER_PIXEL = 9_525L;
    private static final int NEAREST_CELLS = 3;
    private static final String NS_MAIN = "http://schemas.openxmlformats.org/spreadsheetml/2006/main";

    private enum Caption { YES, NO, OTHER, NONE }

    private enum Place { EXACT, CENTRE, NEAREST, NONE }

    private enum LinkKind { HTTPS, INTERNAL, OTHER }

    private record Anchor(long fromCol, long fromColOff, long fromRow, long fromRowOff, long toCol, long toColOff,
            long toRow, long toRowOff) {
    }

    private record RawControl(long shapeId, String rid, Anchor anchor) {
    }

    private record Box(long shapeId, Caption caption, Place place, int row, int column) {
    }

    private record Vml(long id, int column, int offsetPixels, int row, Caption caption) {
    }

    /** A relationship: the kind (the last part of its type), the target, and whether it leaves the workbook. */
    private record Relation(String kind, String target, boolean external) {
    }

    private ExtrasReader() {
    }

    /**
     * Reads the extras of one worksheet into the sheet.
     *
     * @throws ImportRefusal when a part is over a bound or is not what it says
     */
    static void read(XlsxArchive.Parts parts, String sheetPart, RawSheet sheet, SheetProperties.Limits limits) {
        byte[] bytes = parts.part(sheetPart);
        if (bytes == null) {
            return;
        }
        String base = sheetPart.substring(0, sheetPart.lastIndexOf('/') + 1);
        Map<String, Relation> rels = relations(parts.part(base + "_rels/"
                + sheetPart.substring(sheetPart.lastIndexOf('/') + 1) + ".rels"), base);
        Found found = scanSheet(bytes);
        sheet.notes.add(ImportNote.EMBEDDED_FILES, found.embedded.size());
        links(found, rels, sheet);

        String drawing = target(rels, found.drawingRid, "drawing");
        if (drawing != null && parts.part(drawing) != null) {
            drawing(parts, drawing, sheet);
        }
        Map<Long, Caption> captions = new HashMap<>();
        List<Vml> vml = new ArrayList<>();
        String legacy = target(rels, found.legacyRid, "vmlDrawing");
        if (legacy != null && parts.part(legacy) != null) {
            scanVml(parts.part(legacy), captions, vml, sheet);
        }
        if (!sheet.isEmpty()) {
            boxes(parts, found, rels, captions, vml, sheet);
        }
    }

    private static String target(Map<String, Relation> rels, String id, String kind) {
        Relation relation = id == null ? null : rels.get(id);
        return relation != null && !relation.external() && kind.equals(relation.kind()) ? relation.target() : null;
    }

    // ---- the sheet part ----

    /** What the worksheet XML says about controls, links, embedded objects and where the drawings are. */
    private static final class Found {
        final List<RawControl> controls = new ArrayList<>();
        final Set<Long> controlIds = new HashSet<>();
        final Set<Long> embedded = new HashSet<>();
        final List<String[]> hyperlinks = new ArrayList<>();
        String drawingRid;
        String legacyRid;
    }

    private static Found scanSheet(byte[] bytes) {
        Found found = new Found();
        XMLStreamReader r = Xml.reader(bytes);
        try {
            long shape = -1;
            String rid = null;
            long[] a = new long[8];
            boolean inFrom = false;
            boolean inTo = false;
            String leaf = null;
            boolean inControl = false;
            while (r.hasNext()) {
                int event = r.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    boolean main = NS_MAIN.equals(r.getNamespaceURI());
                    String name = main ? r.getLocalName() : "x:" + r.getLocalName();
                    switch (name) {
                        case "control" -> {
                            shape = number(Xml.attr(r, "shapeId"), -1);
                            rid = Xml.relationshipId(r);
                            inControl = true;
                            Arrays.fill(a, Long.MIN_VALUE);
                            if (found.controls.size() >= MAX_CONTROLS) {
                                throw refuse("too_many_controls", "The sheet has more form controls than can be "
                                        + "read.");
                            }
                        }
                        case "from", "x:from" -> inFrom = inControl;
                        case "to", "x:to" -> inTo = inControl;
                        case "oleObject" -> {
                            found.embedded.add(number(Xml.attr(r, "shapeId"), -1));
                            if (found.embedded.size() > MAX_SHAPES) {
                                throw refuse("too_many_shapes", "The sheet has more shapes than can be read.");
                            }
                        }
                        case "hyperlink" -> {
                            if (found.hyperlinks.size() >= MAX_LINKS) {
                                throw refuse("too_many_links", "The sheet has more links than can be read.");
                            }
                            found.hyperlinks.add(new String[] {Xml.attr(r, "ref"), Xml.attr(r, "location"),
                                Xml.relationshipId(r)});
                        }
                        case "drawing" -> found.drawingRid = Xml.relationshipId(r);
                        case "legacyDrawing" -> found.legacyRid = Xml.relationshipId(r);
                        default -> {
                        }
                    }
                    if ((inFrom || inTo) && ("x:col".equals(name) || "x:colOff".equals(name) || "x:row".equals(name)
                            || "x:rowOff".equals(name))) {
                        leaf = name.substring(2);
                    }
                } else if (event == XMLStreamConstants.CHARACTERS && leaf != null && (inFrom || inTo)) {
                    int offset = inFrom ? 0 : 4;
                    int k = switch (leaf) {
                        case "col" -> 0;
                        case "colOff" -> 1;
                        case "row" -> 2;
                        default -> 3;
                    };
                    a[offset + k] = number(r.getText(), Long.MIN_VALUE);
                    leaf = null;
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    String name = r.getLocalName();
                    if ("from".equals(name)) {
                        inFrom = false;
                    } else if ("to".equals(name)) {
                        inTo = false;
                    } else if ("col".equals(name) || "colOff".equals(name) || "row".equals(name)
                            || "rowOff".equals(name)) {
                        leaf = null;
                    } else if ("control".equals(name) && inControl) {
                        inControl = false;
                        if (found.controlIds.add(shape)) {
                            found.controls.add(new RawControl(shape, rid, new Anchor(a[0], a[1], a[2], a[3], a[4],
                                    a[5], a[6], a[7])));
                        }
                    }
                }
            }
        } catch (XMLStreamException e) {
            throw ImportRefusal.notXlsx();
        } finally {
            Xml.close(r);
        }
        return found;
    }

    // ---- relationships ----

    private static Map<String, Relation> relations(byte[] rels, String base) {
        Map<String, Relation> map = new HashMap<>();
        if (rels == null) {
            return map;
        }
        XMLStreamReader r = Xml.reader(rels);
        try {
            while (r.hasNext()) {
                if (r.next() == XMLStreamConstants.START_ELEMENT && "Relationship".equals(r.getLocalName())) {
                    if (map.size() >= MAX_RELATIONSHIPS) {
                        throw refuse("too_many_relationships", "The file has more relationships than can be read.");
                    }
                    String type = Xml.attr(r, "Type");
                    String target = Xml.attr(r, "Target");
                    String id = Xml.attr(r, "Id");
                    if (target == null || id == null) {
                        continue;
                    }
                    boolean external = "External".equals(Xml.attr(r, "TargetMode"));
                    String kind = type == null ? "" : type.substring(type.lastIndexOf('/') + 1);
                    map.put(id, new Relation(kind, external ? target : resolve(base, target), external));
                }
            }
        } catch (XMLStreamException e) {
            throw ImportRefusal.notXlsx();
        } finally {
            Xml.close(r);
        }
        return map;
    }

    /** A relative target made into a part name, never leaving the archive. */
    private static String resolve(String base, String target) {
        String path = target.startsWith("/") ? target.substring(1) : base + target;
        List<String> out = new ArrayList<>();
        for (String segment : path.split("/")) {
            if ("..".equals(segment)) {
                if (out.isEmpty()) {
                    throw refuse("outside_archive", "The file has a relationship that leaves the workbook.");
                }
                out.remove(out.size() - 1);
            } else if (!segment.isEmpty() && !".".equals(segment)) {
                out.add(segment);
            }
        }
        return String.join("/", out);
    }

    // ---- links ----

    private static void links(Found found, Map<String, Relation> rels, RawSheet sheet) {
        for (String[] link : found.hyperlinks) {
            String address = link[2] == null ? null : httpsAddress(rels.get(link[2]));
            LinkKind kind = address != null ? LinkKind.HTTPS : link[2] == null && link[1] != null
                    ? LinkKind.INTERNAL : LinkKind.OTHER;
            int[] at = firstCell(link[0]);
            switch (kind) {
                case HTTPS -> {
                    if (at != null) {
                        sheet.links.put(((long) at[0] << 32) | at[1], address);
                    } else {
                        sheet.notes.add(ImportNote.LINKS_NOT_KEPT);
                    }
                }
                case INTERNAL -> sheet.notes.add(ImportNote.LINKS_INSIDE);
                default -> sheet.notes.add(ImportNote.LINKS_NOT_KEPT);
            }
        }
    }

    /** The address of a link when it is a secure web address that is safe to keep, else null. */
    private static String httpsAddress(Relation relation) {
        if (relation == null || !relation.external()) {
            return null;
        }
        String target = relation.target();
        if (target.length() > MAX_LINK_CHARS) {
            return null;
        }
        for (int i = 0; i < target.length(); i++) {
            char c = target.charAt(i);
            if (c <= 0x20 || c >= 0x7f || c == '\\') {
                return null;
            }
        }
        try {
            URI uri = new URI(target);
            if (uri.getScheme() != null && "https".equals(uri.getScheme().toLowerCase(Locale.ROOT))
                    && uri.getHost() != null && !uri.getHost().isEmpty() && uri.getUserInfo() == null) {
                return "https" + target.substring(5);
            }
            return null;
        } catch (URISyntaxException e) {
            // The exception's text quotes the address: say nothing of it.
            return null;
        }
    }

    private static int[] firstCell(String ref) {
        if (ref == null) {
            return null;
        }
        int colon = ref.indexOf(':');
        return WorkbookReader.reference(colon < 0 ? ref : ref.substring(0, colon));
    }

    // ---- the drawing layer: counted, never read for its text ----

    private static void drawing(XlsxArchive.Parts parts, String part, RawSheet sheet) {
        XMLStreamReader r = Xml.reader(parts.part(part));
        int shapes = 0;
        try {
            while (r.hasNext()) {
                if (r.next() != XMLStreamConstants.START_ELEMENT) {
                    continue;
                }
                String name = r.getLocalName();
                if ("sp".equals(name) || "graphicFrame".equals(name) || "cxnSp".equals(name)
                        || "grpSp".equals(name)) {
                    if (++shapes > MAX_SHAPES) {
                        throw refuse("too_many_shapes", "The sheet has more shapes than can be read.");
                    }
                }
            }
        } catch (XMLStreamException e) {
            throw ImportRefusal.notXlsx();
        } finally {
            Xml.close(r);
        }
        sheet.notes.add(ImportNote.SHAPES, shapes);
    }

    // ---- the old-style drawing: a bounded text scan, no parser, no entity expanded ----

    private static void scanVml(byte[] part, Map<Long, Caption> captions, List<Vml> vml, RawSheet sheet) {
        if (part.length > MAX_DRAWING_BYTES) {
            throw refuse("drawing_too_large", "A drawing in the file is larger than can be read.");
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
            if (++shapes > MAX_SHAPES) {
                throw refuse("too_many_shapes", "The sheet has more shapes than can be read.");
            }
            String shape = s.substring(a, end);
            long id = vmlId(shape);
            boolean checkbox = shape.contains("ObjectType=\"Checkbox\"");
            Caption caption = checkbox ? caption(shape) : Caption.NONE;
            int[] anchor = anchor(shape);
            if (shape.contains("ObjectType=\"Pict\"") && anchor != null && sheet.embeddedAt.size() < MAX_SHAPES) {
                sheet.embeddedAt.add(new RawSheet.Place(anchor[2], anchor[0]));
            }
            if (id >= 0 && checkbox) {
                captions.put(id, caption);
                if (anchor != null) {
                    vml.add(new Vml(id, anchor[0], anchor[1], anchor[2], caption));
                }
            }
            pos = end + 10;
        }
    }

    private static long vmlId(String shape) {
        int at = shape.indexOf("id=\"_x0000_s");
        if (at < 0) {
            return -1;
        }
        int from = at + "id=\"_x0000_s".length();
        int to = from;
        while (to < shape.length() && Character.isDigit(shape.charAt(to)) && to - from < 12) {
            to++;
        }
        return to > from ? Long.parseLong(shape.substring(from, to)) : -1;
    }

    private static Caption caption(String shape) {
        int box = shape.indexOf("<v:textbox");
        if (box < 0) {
            return Caption.NONE;
        }
        int boxEnd = shape.indexOf("</v:textbox>", box);
        String inner = shape.substring(box, boxEnd < 0 ? shape.length() : boxEnd);
        StringBuilder text = new StringBuilder();
        boolean tag = false;
        for (int i = 0; i < inner.length(); i++) {
            char c = inner.charAt(i);
            if (c == '<') {
                tag = true;
            } else if (c == '>') {
                tag = false;
            } else if (!tag) {
                text.append(c);
            }
        }
        String t = text.toString().trim().toLowerCase(Locale.ROOT);
        return t.isEmpty() ? Caption.NONE : "yes".equals(t) ? Caption.YES : "no".equals(t) ? Caption.NO
                : Caption.OTHER;
    }

    /** The first column, its offset in pixels, the first row and the rest of an old-style anchor, or null. */
    private static int[] anchor(String shape) {
        int at = shape.indexOf("<x:Anchor>");
        int end = at < 0 ? -1 : shape.indexOf("</x:Anchor>", at);
        if (at < 0 || end <= at || end - at >= 200) {
            return null;
        }
        String[] p = shape.substring(at + 10, end).replaceAll("\\s+", "").split(",");
        if (p.length < 4) {
            return null;
        }
        return new int[] {(int) number(p[0], -1), (int) number(p[1], 0), (int) number(p[2], -1)};
    }

    // ---- check boxes ----

    private static void boxes(XlsxArchive.Parts parts, Found found, Map<String, Relation> rels,
            Map<Long, Caption> captions, List<Vml> vml, RawSheet sheet) {
        Geometry g = new Geometry(sheet);
        Map<Long, Caption> byPosition = positional(found, captions, vml);
        List<Box> boxes = new ArrayList<>();
        int other = 0;
        for (RawControl control : found.controls) {
            Relation rel = control.rid() == null ? null : rels.get(control.rid());
            if (rel == null || rel.external() || !"ctrlProp".equals(rel.kind()) || parts.part(rel.target()) == null
                    || !isCheckBox(parts.part(rel.target()))) {
                other++;
                continue;
            }
            Anchor an = control.anchor();
            if (an.fromCol() < 0 || an.fromRow() < 0 || an.toCol() < an.fromCol() || an.toRow() < an.fromRow()
                    || an.toCol() >= MAX_COLUMN || an.toRow() >= MAX_ROW || an.fromColOff() == Long.MIN_VALUE
                    || an.toColOff() == Long.MIN_VALUE || an.fromRowOff() == Long.MIN_VALUE
                    || an.toRowOff() == Long.MIN_VALUE) {
                other++;
                continue;
            }
            int[] cell = new int[2];
            Place place = g.place(an, cell);
            Caption caption = captions.get(control.shapeId());
            if (caption == null) {
                caption = byPosition.getOrDefault(control.shapeId(), Caption.NONE);
            }
            boxes.add(new Box(control.shapeId(), caption, place, cell[0], cell[1]));
        }
        sheet.notes.add(ImportNote.FORM_CONTROLS, other);
        place(boxes, sheet);
    }

    private static boolean isCheckBox(byte[] part) {
        XMLStreamReader r = Xml.reader(part);
        try {
            while (r.hasNext()) {
                if (r.next() == XMLStreamConstants.START_ELEMENT && "formControlPr".equals(r.getLocalName())) {
                    return "CheckBox".equals(Xml.attr(r, "objectType"));
                }
            }
        } catch (XMLStreamException e) {
            throw ImportRefusal.notXlsx();
        } finally {
            Xml.close(r);
        }
        return false;
    }

    /**
     * The boxes whose shape number the old-style drawing does not know are paired with the shapes of that drawing no
     * number claimed: in one cell, in the order of their left edge, and only when the counts in that cell are equal.
     */
    private static Map<Long, Caption> positional(Found found, Map<Long, Caption> captions, List<Vml> vml) {
        Set<Long> claimed = new HashSet<>();
        for (RawControl control : found.controls) {
            if (captions.containsKey(control.shapeId())) {
                claimed.add(control.shapeId());
            }
        }
        Map<String, List<RawControl>> loose = new HashMap<>();
        for (RawControl control : found.controls) {
            if (!claimed.contains(control.shapeId())) {
                loose.computeIfAbsent(control.anchor().fromCol() + ":" + control.anchor().fromRow(),
                        key -> new ArrayList<>()).add(control);
            }
        }
        Map<Long, Caption> out = new HashMap<>();
        for (Map.Entry<String, List<RawControl>> entry : loose.entrySet()) {
            List<RawControl> controls = new ArrayList<>(entry.getValue());
            controls.sort(Comparator.comparingLong(x -> x.anchor().fromColOff()));
            String[] cell = entry.getKey().split(":");
            long column = Long.parseLong(cell[0]);
            long row = Long.parseLong(cell[1]);
            List<Vml> shapes = new ArrayList<>();
            for (Vml shape : vml) {
                if (!found.controlIds.contains(shape.id()) && shape.column() == column && shape.row() == row) {
                    shapes.add(shape);
                }
            }
            shapes.sort(Comparator.comparingInt(Vml::offsetPixels));
            if (controls.size() == shapes.size()) {
                for (int i = 0; i < controls.size(); i++) {
                    out.put(controls.get(i).shapeId(), shapes.get(i).caption());
                }
            }
        }
        return out;
    }

    /** Pairs a Yes box with a No box, then records the cells that become yes/no cells and the boxes left unplaced. */
    private static void place(List<Box> boxes, RawSheet sheet) {
        List<Box> placed = new ArrayList<>();
        for (Box box : boxes) {
            if (box.place() == Place.NONE) {
                sheet.unplacedBoxes.add(new RawSheet.Place(box.row(), box.column()));
            } else {
                placed.add(box);
            }
        }
        Set<Long> used = new HashSet<>();
        Map<Long, Integer> cells = new HashMap<>();
        for (Box yes : placed) {
            if (yes.caption() != Caption.YES || used.contains(yes.shapeId())) {
                continue;
            }
            Box best = null;
            int distance = Integer.MAX_VALUE;
            for (Box no : placed) {
                if (no.caption() != Caption.NO || used.contains(no.shapeId())) {
                    continue;
                }
                int dr = Math.abs(no.row() - yes.row());
                int dc = Math.abs(no.column() - yes.column());
                boolean sameRow = dr == 0 && dc <= 4;
                boolean sameColumn = dc == 0 && dr <= 2;
                if ((sameRow || sameColumn) && dr + dc < distance) {
                    distance = dr + dc;
                    best = no;
                }
            }
            if (best != null) {
                used.add(yes.shapeId());
                used.add(best.shapeId());
                sheet.boxPairs++;
                cells.merge(((long) yes.row() << 32) | yes.column(), 2, Integer::sum);
            }
        }
        for (Box box : placed) {
            if (!used.contains(box.shapeId())) {
                cells.merge(((long) box.row() << 32) | box.column(), 1, Integer::sum);
            }
        }
        sheet.boxesPlaced = placed.size();
        cells.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> sheet.yesNo.add(
                new RawSheet.YesNo((int) (entry.getKey() >> 32), (int) (entry.getKey() & 0xffffffffL),
                        entry.getValue())));
    }

    // ---- geometry: where a box lies on the sheet ----

    /** The cell sizes of a sheet along one direction, in pixels, with a binary search for the cell at a position. */
    private static final class Axis {
        private final long fallback;
        private final int[] first;
        private final int[] length;
        private final long[] size;
        private final long[] before;

        Axis(long fallback, List<int[]> segments, List<Long> sizes) {
            this.fallback = fallback;
            List<Integer> order = new ArrayList<>();
            for (int i = 0; i < segments.size(); i++) {
                order.add(i);
            }
            order.sort(Comparator.comparingInt(i -> segments.get(i)[0]));
            first = new int[order.size()];
            length = new int[order.size()];
            size = new long[order.size()];
            before = new long[order.size() + 1];
            int count = 0;
            int next = 0;
            for (int i : order) {
                int[] segment = segments.get(i);
                int from = Math.max(segment[0], next);
                if (segment[1] < from) {
                    continue;
                }
                first[count] = from;
                length[count] = segment[1] - from + 1;
                size[count] = sizes.get(i);
                before[count + 1] = before[count] + (long) length[count] * (size[count] - fallback);
                next = segment[1] + 1;
                count++;
            }
        }

        /** The distance from the start to the start of this cell, in pixels. */
        long start(long index) {
            int lo = 0;
            int hi = first.length;
            while (lo < hi) {
                int mid = (lo + hi) >>> 1;
                if (first[mid] < index) {
                    lo = mid + 1;
                } else {
                    hi = mid;
                }
            }
            long delta = before[lo];
            if (lo > 0) {
                int last = lo - 1;
                long end = (long) first[last] + length[last];
                if (end > index) {
                    delta -= (end - index) * (size[last] - fallback);
                }
            }
            return index * fallback + delta;
        }

        /** The cell that holds a position, in pixels from the start. */
        int at(long position, int most) {
            int lo = 0;
            int hi = most;
            while (lo < hi) {
                int mid = (lo + hi + 1) >>> 1;
                if (start(mid) <= position) {
                    lo = mid;
                } else {
                    hi = mid - 1;
                }
            }
            return lo;
        }
    }

    private static final class Geometry {
        private final Axis columns;
        private final Axis rows;
        private final List<RawSheet.Area> merges;
        private final int top;
        private final int bottom;
        private final int right;

        Geometry(RawSheet sheet) {
            List<int[]> segments = new ArrayList<>();
            List<Long> sizes = new ArrayList<>();
            for (RawSheet.ColumnSpec spec : sheet.columnSpecs) {
                segments.add(new int[] {spec.first(), spec.last()});
                sizes.add(spec.hidden() ? 0L : Math.round(spec.chars() * 7));
            }
            columns = new Axis(Math.round(sheet.defaultColumnChars * 7), segments, sizes);
            List<int[]> rowSegments = new ArrayList<>();
            List<Long> rowSizes = new ArrayList<>();
            sheet.rowSpecs.forEach((row, spec) -> {
                rowSegments.add(new int[] {row, row});
                rowSizes.add(spec.hidden() ? 0L : Math.round(spec.points() * 96 / 72));
            });
            rows = new Axis(Math.round(sheet.defaultRowPoints * 96 / 72), rowSegments, rowSizes);
            merges = sheet.merges;
            RawSheet.Extent extent = sheet.extent();
            top = extent.top();
            bottom = extent.bottom();
            right = extent.right();
        }

        private RawSheet.Area mergeOf(int row, int column) {
            for (RawSheet.Area area : merges) {
                if (row >= area.top() && row <= area.bottom() && column >= area.left() && column <= area.right()) {
                    return area;
                }
            }
            return null;
        }

        Place place(Anchor a, int[] out) {
            long x0 = columns.start(a.fromCol()) * EMU_PER_PIXEL + a.fromColOff();
            long x1 = columns.start(a.toCol()) * EMU_PER_PIXEL + a.toColOff();
            long y0 = rows.start(a.fromRow()) * EMU_PER_PIXEL + a.fromRowOff();
            long y1 = rows.start(a.toRow()) * EMU_PER_PIXEL + a.toRowOff();
            int cc = columns.at((x0 + x1) / 2 / EMU_PER_PIXEL, MAX_COLUMN - 1);
            int cr = rows.at((y0 + y1) / 2 / EMU_PER_PIXEL, MAX_ROW - 1);
            RawSheet.Area merged = mergeOf(cr, cc);
            boolean inside = cr >= top && cr <= bottom && cc >= 0 && cc <= right;
            if (!inside) {
                int nr = Math.max(top, Math.min(bottom, cr));
                int nc = Math.max(0, Math.min(right, cc));
                if (Math.max(Math.abs(nr - cr), Math.abs(nc - cc)) > NEAREST_CELLS) {
                    return Place.NONE;
                }
                out[0] = nr;
                out[1] = nc;
                return Place.NEAREST;
            }
            out[0] = merged == null ? cr : merged.top();
            out[1] = merged == null ? cc : merged.left();
            int fr = rows.at(y0 / EMU_PER_PIXEL, MAX_ROW - 1);
            int fc = columns.at(x0 / EMU_PER_PIXEL, MAX_COLUMN - 1);
            int lr = rows.at(Math.max(y0, y1 - 1) / EMU_PER_PIXEL, MAX_ROW - 1);
            int lc = columns.at(Math.max(x0, x1 - 1) / EMU_PER_PIXEL, MAX_COLUMN - 1);
            RawSheet.Area first = mergeOf(fr, fc);
            RawSheet.Area last = mergeOf(lr, lc);
            boolean oneCell = fr == lr && fc == lc;
            boolean oneArea = first != null && first == last;
            return oneCell || oneArea ? Place.EXACT : Place.CENTRE;
        }
    }

    // ---- small helpers ----

    private static long number(String text, long fallback) {
        Long value = Xml.longer(text);
        return value == null ? fallback : value;
    }

    private static ImportRefusal refuse(String code, String words) {
        return new ImportRefusal(code, words);
    }
}
