package app.platform.sheets.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The hostile cases for what the reader takes from a worksheet that is not a cell (SH1B, ADR-0253 amendment): check
 * boxes, links, the drawing layer, pictures and the old-style drawing. Every case is a <em>valid</em> package with one
 * hostile part, so that a strict reader reaches the content. A case passes when the reader refuses it with the stable
 * code named, or contains it (reads it inside the limits and counts what it must) with no crash, stack overflow,
 * memory error or time out. The cases are the 42 of the second proof of concept, restated for the product reader; the
 * files are made here, in memory, and nothing hostile is stored.
 */
class ExtrasHostileTest {

    private static final String MAIN = "http://schemas.openxmlformats.org/spreadsheetml/2006/main";
    private static final String R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
    private static final String PKG = "http://schemas.openxmlformats.org/package/2006/relationships";
    private static final String XDR = "http://schemas.openxmlformats.org/drawingml/2006/spreadsheetDrawing";
    private static final String A = "http://schemas.openxmlformats.org/drawingml/2006/main";
    private static final String MC = "http://schemas.openxmlformats.org/markup-compatibility/2006";
    private static final String CTRL_PROP = "<?xml version=\"1.0\"?><formControlPr "
            + "xmlns=\"http://schemas.microsoft.com/"
            + "office/spreadsheetml/2009/9/main\" objectType=\"CheckBox\" checked=\"Checked\"/>";
    private static final String NEAR = anchor("0", "0", "0", "0", "0", "100000", "0", "100000");
    private static final String FAR = anchor("16000", "0", "1000000", "0", "16001", "0", "1000001", "0");

    /** A case: a file, and either the code it must be refused with or a check of what was read. */
    record Case(String name, Supplier<byte[]> file, String refusedWith, Predicate<RawSheet> check) {

        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<Arguments> cases() {
        List<Case> cases = new ArrayList<>();
        cases.add(read("one good check box", () -> controls(1, NEAR), s -> s.yesNo.size() == 1
                && s.unplacedBoxes.isEmpty()));
        cases.add(refused("1,001 controls", () -> controls(1001, NEAR), "too_many_controls"));
        cases.add(read("a control anchored far away is not placed", () -> controls(1, FAR),
                s -> s.yesNo.isEmpty() && s.unplacedBoxes.size() == 1));
        cases.add(read("a control with a negative anchor is not a check box", () -> controls(1,
                anchor("-5", "0", "-5", "0", "0", "0", "0", "0")), s -> s.yesNo.isEmpty()
                        && s.notes.of(ImportNote.FORM_CONTROLS) == 1));
        cases.add(read("a control with a text anchor is not a check box", () -> controls(1,
                anchor("abc", "x", "y", "z", "1", "1", "1", "1")), s -> s.notes.of(ImportNote.FORM_CONTROLS) == 1));
        cases.add(read("a control whose end is before its start", () -> controls(1,
                anchor("5", "0", "5", "0", "1", "0", "1", "0")), s -> s.notes.of(ImportNote.FORM_CONTROLS) == 1));
        cases.add(read("a control anchor with huge offsets", () -> controls(1, anchor("0", "99999999999999", "0",
                "99999999999999", "0", "99999999999999", "0", "99999999999999")), s -> s.yesNo.size() <= 1));

        cases.add(read("an https link is kept", () -> links(List.of("https://example.test/a")),
                s -> s.links.size() == 1));
        for (String scheme : List.of("javascript:alert(1)", "data:text/html,x", "file:///c:/x", "mailto:a@example.test",
                "http://example.test/", "ftp://example.test/", "vbscript:x", "https://user:pw@example.test/")) {
            cases.add(read("a link with " + scheme.substring(0, 8) + " is not kept", () -> links(List.of(scheme)),
                    s -> s.links.isEmpty() && s.notes.of(ImportNote.LINKS_NOT_KEPT) == 1));
        }
        cases.add(read("an https link in capitals is kept in lower case",
                () -> links(List.of("HTTPS://EXAMPLE.TEST/up")),
                s -> s.links.size() == 1 && s.links.values().iterator().next().startsWith("https://")));
        cases.add(read("a link of 3,000 characters is not kept", () -> links(List.of("https://example.test/"
                + "a".repeat(3000))), s -> s.links.isEmpty() && s.notes.of(ImportNote.LINKS_NOT_KEPT) == 1));
        cases.add(read("a link with a space is not kept", () -> links(List.of("https://example.test/a b")),
                s -> s.links.isEmpty() && s.notes.of(ImportNote.LINKS_NOT_KEPT) == 1));
        cases.add(refused("a link with a control character is not XML", () -> links(List.of(
                "https://example.test/a\u0001b")), "not_xlsx"));
        cases.add(read("a link with a back slash is not kept", () -> links(List.of("https://example.test\\a")),
                s -> s.links.isEmpty() && s.notes.of(ImportNote.LINKS_NOT_KEPT) == 1));
        cases.add(read("a link inside the workbook is counted", () -> inside(), s -> s.links.isEmpty()
                && s.notes.of(ImportNote.LINKS_INSIDE) == 1));
        List<String> many = new ArrayList<>();
        for (int i = 0; i < 5001; i++) {
            many.add("https://example.test/a");
        }
        cases.add(refused("5,001 links", () -> links(many), "too_many_links"));

        cases.add(refused("5,001 drawing shapes", () -> drawing(drawing(shapes(5001)), Map.of(), null),
                "too_many_shapes"));
        cases.add(read("5,000 drawing shapes", () -> drawing(drawing(shapes(5000)), Map.of(), null),
                s -> s.notes.of(ImportNote.SHAPES) == 5000));
        cases.add(refused("a document type with an entity bomb in a drawing part", () -> drawing(
                "<?xml version=\"1.0\"?><!DOCTYPE d [<!ENTITY a \"aaaaaaaaaa\"><!ENTITY b \"&a;&a;&a;&a;&a;&a;&a;&a;\">"
                        + "<!ENTITY c \"&b;&b;&b;&b;&b;&b;&b;&b;\">]><xdr:wsDr xmlns:xdr=\"" + XDR
                                + "\">&c;</xdr:wsDr>",
                Map.of(), null), "doctype"));
        cases.add(refused("an external entity in a drawing part", () -> drawing(
                "<?xml version=\"1.0\"?><!DOCTYPE d [<!ENTITY x SYSTEM \"file:///c:/windows/win.ini\">]><xdr:wsDr "
                        + "xmlns:xdr=\"" + XDR + "\">&x;</xdr:wsDr>", Map.of(), null), "doctype"));
        cases.add(refused("a document type in a control property part", () -> {
            String sheet = sheet("<controls>" + control(1001, "rId1", NEAR) + "</controls>");
            return workbook(sheet, rels(rel("rId1", "ctrlProp", "../ctrlProps/ctrlProp1.xml", false)),
                    Map.of("xl/ctrlProps/ctrlProp1.xml", ("<?xml version=\"1.0\"?><!DOCTYPE f [<!ENTITY q \"zz\">]>"
                            + "<formControlPr xmlns=\"http://schemas.microsoft.com/office/spreadsheetml/2009/9/main\" "
                            + "objectType=\"CheckBox\"/>").getBytes(StandardCharsets.UTF_8)));
        }, "doctype"));
        cases.add(refused("a drawing part that inflates to 30 MB", () -> drawing(new String(repeat(" ", 30_000_000),
                StandardCharsets.UTF_8) + "<a/>", Map.of(), null), "inflated"));
        cases.add(refused("a drawing part nested 20,000 deep",
                () -> drawing("<?xml version=\"1.0\"?><xdr:wsDr xmlns:xdr=\""
                + XDR + "\">" + "<a>".repeat(20_000) + "</a>".repeat(20_000) + "</xdr:wsDr>", Map.of(), null),
                "not_xlsx"));
        cases.add(refused("a drawing part that is not XML", () -> drawing("not xml at all <<<", Map.of(), null),
                "not_xlsx"));
        cases.add(refused("a drawing relationship leaving the archive",
                () -> workbook(sheet("<drawing r:id=\"rId1\"/>"),
                rels(rel("rId1", "drawing", "../../../../x/drawing1.xml", false)), Map.of()), "outside_archive"));
        cases.add(read("a relationship with an absolute target is not followed", () -> workbook(
                sheet("<drawing r:id=\"rId1\"/>"), rels(rel("rId1", "drawing", "/c:/windows/x.xml", false)), Map.of()),
                s -> s.notes.of(ImportNote.SHAPES) == 0));

        byte[] png = new byte[] {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 0, 0, 0, 0};
        cases.add(read("a real PNG header is counted as an image", () -> drawing(drawing(""),
                Map.of("xl/media/image1.png", png), null), s -> s.notes.of(ImportNote.IMAGES) == 1));
        cases.add(read("a .png part whose bytes are a program is only counted", () -> drawing(drawing(""),
                Map.of("xl/media/image1.png", new byte[] {'M', 'Z', 0, 0, 0, 0, 0, 0}), null),
                s -> s.notes.of(ImportNote.IMAGES) == 1));
        cases.add(read("a .png part whose bytes are markup is only counted", () -> drawing(drawing(""),
                Map.of("xl/media/image1.png", "<svg onload=\"x\"></svg>".getBytes(StandardCharsets.UTF_8)), null),
                s -> s.notes.of(ImportNote.IMAGES) == 1));
        cases.add(read("a metafile is typed as a vector picture and never decoded", () -> {
            byte[] emf = new byte[100];
            emf[0] = 1;
            emf[40] = ' ';
            emf[41] = 'E';
            emf[42] = 'M';
            emf[43] = 'F';
            return drawing(drawing(""), Map.of("xl/media/image1.emf", emf), null);
        }, s -> s.notes.of(ImportNote.VECTOR_PICTURES) == 1 && s.notes.of(ImportNote.IMAGES) == 0));

        cases.add(refused("5,001 old-style drawing shapes", () -> {
            StringBuilder v = new StringBuilder("<xml>");
            for (int i = 0; i < 5001; i++) {
                v.append("<v:shape id=\"_x0000_s").append(i).append("\"><x:ClientData ObjectType=\"Checkbox\">"
                        + "<x:Anchor>0,0,0,0,1,0,1,0</x:Anchor></x:ClientData></v:shape>");
            }
            return vml(v.append("</xml>").toString());
        }, "too_many_shapes"));
        cases.add(refused("an old-style drawing of 5 MB", () -> vml(new String(repeat("a", 5_000_000),
                StandardCharsets.UTF_8)), "drawing_too_large"));
        cases.add(read("an old-style drawing with entity text and unclosed tags expands nothing", () -> vml(
                "<xml><!DOCTYPE v [<!ENTITY e SYSTEM \"file:///c:/x\">]><v:shape id=\"_x0000_s1\"><v:textbox><div>&e;"
                        + "<br></v:textbox><x:ClientData ObjectType=\"Checkbox\"/></v:shape>"), s -> true));
        cases.add(read("an old-style drawing with no end of shape", () -> vml("<xml><v:shape id=\"_x0000_s1\">"),
                s -> true));
        cases.add(refused("a part name repeated but for the case", () -> {
            Map<String, byte[]> parts = new LinkedHashMap<>();
            parts.put("[Content_Types].xml", ("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-"
                    + "types\"/>").getBytes(StandardCharsets.UTF_8));
            parts.put("xl/ctrlProps/ctrlProp1.xml", CTRL_PROP.getBytes(StandardCharsets.UTF_8));
            parts.put("XL/ctrlProps/ctrlProp1.xml", CTRL_PROP.getBytes(StandardCharsets.UTF_8));
            return XlsxFile.zip(parts);
        }, "duplicate_part"));
        return cases.stream().map(one -> Arguments.of(one, one.name()));
    }

    @ParameterizedTest(name = "{1}")
    @MethodSource("cases")
    @Timeout(20)
    void everyCaseIsRefusedWithItsCodeOrContained(Case one, String name) {
        byte[] file = one.file().get();
        ImportProperties settings = ImportProperties.standard();
        SheetProperties.Limits limits = SheetProperties.Limits.standard();
        if (one.refusedWith() != null) {
            Throwable thrown = null;
            try {
                new WorkbookReader(settings, limits).read(file, null);
            } catch (RuntimeException e) {
                thrown = e;
            }
            assertThat(thrown).as(name).isInstanceOf(ImportRefusal.class);
            assertThat(((ImportRefusal) thrown).code()).as(name).isEqualTo(one.refusedWith());
            assertThat(((ImportRefusal) thrown).words()).doesNotContain("file:///").doesNotContain("win.ini");
        } else {
            RawSheet sheet = new WorkbookReader(settings, limits).read(file, null);
            assertThat(one.check().test(sheet)).as(name).isTrue();
        }
    }

    // ---- the cases ----

    private static Case read(String name, Supplier<byte[]> file, Predicate<RawSheet> check) {
        return new Case(name, file, null, check);
    }

    private static Case refused(String name, Supplier<byte[]> file, String code) {
        return new Case(name, file, code, null);
    }

    // ---- making the files ----

    private static String rels(String... entries) {
        StringBuilder b = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?><Relationships xmlns=\"" + PKG
                + "\">");
        for (String e : entries) {
            b.append(e);
        }
        return b.append("</Relationships>").toString();
    }

    private static String rel(String id, String type, String target, boolean external) {
        return "<Relationship Id=\"" + id + "\" Type=\"" + R + "/" + type + "\" Target=\"" + target + "\""
                + (external ? " TargetMode=\"External\"" : "") + "/>";
    }

    private static String sheet(String after) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><worksheet xmlns=\"" + MAIN + "\" xmlns:r=\"" + R
                + "\" xmlns:mc=\"" + MC + "\" xmlns:xdr=\"" + XDR + "\"><sheetData><row r=\"1\"><c r=\"A1\" "
                + "t=\"inlineStr\"><is><t>x</t></is></c><c r=\"B1\" t=\"inlineStr\"><is><t>y</t></is></c></row>"
                + "<row r=\"2\"><c r=\"A2\" t=\"inlineStr\"><is><t>z</t></is></c></row></sheetData>" + after
                + "</worksheet>";
    }

    private static byte[] workbook(String sheetXml, String sheetRels, Map<String, byte[]> extra) {
        XlsxFile file = new XlsxFile().part("xl/worksheets/sheet1.xml", sheetXml.getBytes(StandardCharsets.UTF_8));
        if (sheetRels != null) {
            file.part("xl/worksheets/_rels/sheet1.xml.rels", sheetRels.getBytes(StandardCharsets.UTF_8));
        }
        extra.forEach(file::part);
        return file.bytes();
    }

    private static String control(long shapeId, String rid, String anchor) {
        return "<mc:AlternateContent><mc:Choice Requires=\"x14\"><control shapeId=\"" + shapeId + "\" r:id=\"" + rid
                + "\" name=\"c\"><controlPr><anchor moveWithCells=\"1\">" + anchor + "</anchor></controlPr></control>"
                + "</mc:Choice></mc:AlternateContent>";
    }

    private static String anchor(String fc, String fco, String fr, String fro, String tc, String tco, String tr,
            String tro) {
        return "<from><xdr:col>" + fc + "</xdr:col><xdr:colOff>" + fco + "</xdr:colOff><xdr:row>" + fr
                + "</xdr:row><xdr:rowOff>" + fro + "</xdr:rowOff></from><to><xdr:col>" + tc + "</xdr:col><xdr:colOff>"
                + tco + "</xdr:colOff><xdr:row>" + tr + "</xdr:row><xdr:rowOff>" + tro + "</xdr:rowOff></to>";
    }

    private static byte[] controls(int n, String anchor) {
        StringBuilder controls = new StringBuilder("<controls>");
        StringBuilder rs = new StringBuilder();
        for (int i = 1; i <= n; i++) {
            controls.append(control(1000 + i, "rId" + i, anchor));
            rs.append(rel("rId" + i, "ctrlProp", "../ctrlProps/ctrlProp1.xml", false));
        }
        controls.append("</controls>");
        return workbook(sheet(controls.toString()), rels(rs.toString()),
                Map.of("xl/ctrlProps/ctrlProp1.xml", CTRL_PROP.getBytes(StandardCharsets.UTF_8)));
    }

    private static byte[] links(List<String> targets) {
        StringBuilder links = new StringBuilder("<hyperlinks>");
        StringBuilder rs = new StringBuilder();
        for (int i = 0; i < targets.size(); i++) {
            links.append("<hyperlink ref=\"A1\" r:id=\"rId").append(i + 1).append("\"/>");
            rs.append(rel("rId" + (i + 1), "hyperlink", targets.get(i), true));
        }
        links.append("</hyperlinks>");
        return workbook(sheet(links.toString()), rels(rs.toString()), Map.of());
    }

    private static byte[] inside() {
        return workbook(sheet("<hyperlinks><hyperlink ref=\"A1\" location=\"Sheet2!A1\"/></hyperlinks>"), null,
                Map.of());
    }

    private static byte[] drawing(String drawingXml, Map<String, byte[]> extra, String drawingRels) {
        Map<String, byte[]> parts = new LinkedHashMap<>(extra);
        parts.put("xl/drawings/drawing1.xml", drawingXml.getBytes(StandardCharsets.UTF_8));
        if (drawingRels != null) {
            parts.put("xl/drawings/_rels/drawing1.xml.rels", drawingRels.getBytes(StandardCharsets.UTF_8));
        }
        return workbook(sheet("<drawing r:id=\"rId1\"/>"), rels(rel("rId1", "drawing", "../drawings/drawing1.xml",
                false)), parts);
    }

    private static String drawing(String body) {
        return "<?xml version=\"1.0\"?><xdr:wsDr xmlns:xdr=\"" + XDR + "\" xmlns:a=\"" + A + "\" xmlns:r=\"" + R + "\">"
                + body + "</xdr:wsDr>";
    }

    private static String shapes(int n) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < n; i++) {
            b.append("<xdr:twoCellAnchor><xdr:from><xdr:col>0</xdr:col><xdr:colOff>0</xdr:colOff><xdr:row>0</xdr:row>"
                    + "<xdr:rowOff>0</xdr:rowOff></xdr:from><xdr:to><xdr:col>1</xdr:col><xdr:colOff>0</xdr:colOff>"
                    + "<xdr:row>1</xdr:row><xdr:rowOff>0</xdr:rowOff></xdr:to><xdr:sp><xdr:nvSpPr><xdr:cNvPr id=\"")
                    .append(i + 2).append("\" name=\"s\"/><xdr:cNvSpPr/></xdr:nvSpPr><xdr:spPr/></xdr:sp>"
                    + "<xdr:clientData/></xdr:twoCellAnchor>");
        }
        return b.toString();
    }

    private static byte[] vml(String vml) {
        return workbook(sheet("<legacyDrawing r:id=\"rId1\"/>"), rels(rel("rId1", "vmlDrawing",
                "../drawings/vmlDrawing1.vml", false)), Map.of("xl/drawings/vmlDrawing1.vml",
                        vml.getBytes(StandardCharsets.UTF_8)));
    }

    private static byte[] repeat(String unit, int bytes) {
        byte[] u = unit.getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[bytes];
        for (int i = 0; i < out.length; i++) {
            out[i] = u[i % u.length];
        }
        return out;
    }
}
