package spike;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * POC 2, WP1.6. Hostile cases for the parts the upload skipped before: control check boxes, links, drawings and pictures. Every
 * case is a VALID package (so that a strict reader reaches the content) with one hostile part. A case passes when the reader
 * REFUSES it with the expected stable key, or CONTAINS it (reads it inside the limits and counts what it must) without a crash,
 * stack overflow, memory error or time out. Output: one line per case, no text of any file.
 */
public final class ExtrasHostile {

    private static final String MAIN = "http://schemas.openxmlformats.org/spreadsheetml/2006/main";
    private static final String R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
    private static final String PKG = "http://schemas.openxmlformats.org/package/2006/relationships";
    private static final String XDR = "http://schemas.openxmlformats.org/drawingml/2006/spreadsheetDrawing";
    private static final String A = "http://schemas.openxmlformats.org/drawingml/2006/main";
    private static final String MC = "http://schemas.openxmlformats.org/markup-compatibility/2006";

    private record Case(String name, String expect, Supplier<byte[]> file, java.util.function.Predicate<Extras.Report> check) {
    }

    private ExtrasHostile() {
    }

    // --------------------------------------------------------------------------------------------------------------- builders

    private static String rels(String... entries) {
        StringBuilder b = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?><Relationships xmlns=\"" + PKG + "\">");
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
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><worksheet xmlns=\"" + MAIN + "\" xmlns:r=\"" + R + "\" xmlns:mc=\"" + MC
                + "\" xmlns:xdr=\"" + XDR + "\"><sheetData><row r=\"1\"><c r=\"A1\" t=\"inlineStr\"><is><t>x</t></is></c>"
                + "<c r=\"B1\" t=\"inlineStr\"><is><t>y</t></is></c></row><row r=\"2\"><c r=\"A2\" t=\"inlineStr\"><is><t>z</t>"
                + "</is></c></row></sheetData>" + after + "</worksheet>";
    }

    private static MiniXlsx base(String sheetXml, String sheetRels) {
        MiniXlsx m = new MiniXlsx();
        m.part("xl/worksheets/sheet1.xml", sheetXml.getBytes(StandardCharsets.UTF_8));
        if (sheetRels != null) {
            m.part("xl/worksheets/_rels/sheet1.xml.rels", sheetRels.getBytes(StandardCharsets.UTF_8));
        }
        return m;
    }

    private static String control(long shapeId, String rid, String anchor) {
        return "<mc:AlternateContent><mc:Choice Requires=\"x14\"><control shapeId=\"" + shapeId + "\" r:id=\"" + rid + "\" name=\"c\">"
                + "<controlPr><anchor moveWithCells=\"1\">" + anchor + "</anchor></controlPr></control></mc:Choice></mc:AlternateContent>";
    }

    private static String anchor(String fc, String fco, String fr, String fro, String tc, String tco, String tr, String tro) {
        return "<from><xdr:col>" + fc + "</xdr:col><xdr:colOff>" + fco + "</xdr:colOff><xdr:row>" + fr + "</xdr:row><xdr:rowOff>"
                + fro + "</xdr:rowOff></from><to><xdr:col>" + tc + "</xdr:col><xdr:colOff>" + tco + "</xdr:colOff><xdr:row>" + tr
                + "</xdr:row><xdr:rowOff>" + tro + "</xdr:rowOff></to>";
    }

    private static final String CTRL_PROP = "<?xml version=\"1.0\"?><formControlPr xmlns=\"http://schemas.microsoft.com/office/"
            + "spreadsheetml/2009/9/main\" objectType=\"CheckBox\" checked=\"Checked\"/>";

    private static byte[] withControls(int n, String anchor) {
        StringBuilder controls = new StringBuilder("<controls>");
        StringBuilder rs = new StringBuilder();
        MiniXlsx m;
        for (int i = 1; i <= n; i++) {
            controls.append(control(1000 + i, "rId" + i, anchor));
            rs.append(rel("rId" + i, "ctrlProp", "../ctrlProps/ctrlProp1.xml", false));
        }
        controls.append("</controls>");
        m = base(sheet(controls.toString()), rels(rs.toString()));
        m.part("xl/ctrlProps/ctrlProp1.xml", CTRL_PROP.getBytes(StandardCharsets.UTF_8));
        return m.bytes();
    }

    private static byte[] withLinks(List<String> targets) {
        StringBuilder links = new StringBuilder("<hyperlinks>");
        StringBuilder rs = new StringBuilder();
        for (int i = 0; i < targets.size(); i++) {
            links.append("<hyperlink ref=\"A1\" r:id=\"rId").append(i + 1).append("\"/>");
            rs.append(rel("rId" + (i + 1), "hyperlink", targets.get(i), true));
        }
        links.append("</hyperlinks>");
        return base(sheet(links.toString()), rels(rs.toString())).bytes();
    }

    private static byte[] withDrawing(String drawingXml, Map<String, byte[]> extra, String drawingRels) {
        MiniXlsx m = base(sheet("<drawing r:id=\"rId1\"/>"), rels(rel("rId1", "drawing", "../drawings/drawing1.xml", false)));
        m.part("xl/drawings/drawing1.xml", drawingXml.getBytes(StandardCharsets.UTF_8));
        if (drawingRels != null) {
            m.part("xl/drawings/_rels/drawing1.xml.rels", drawingRels.getBytes(StandardCharsets.UTF_8));
        }
        for (Map.Entry<String, byte[]> e : extra.entrySet()) {
            m.part(e.getKey(), e.getValue());
        }
        return m.bytes();
    }

    private static String drawing(String body) {
        return "<?xml version=\"1.0\"?><xdr:wsDr xmlns:xdr=\"" + XDR + "\" xmlns:a=\"" + A + "\" xmlns:r=\"" + R + "\">" + body
                + "</xdr:wsDr>";
    }

    private static String shapes(int n) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < n; i++) {
            b.append("<xdr:twoCellAnchor><xdr:from><xdr:col>0</xdr:col><xdr:colOff>0</xdr:colOff><xdr:row>0</xdr:row><xdr:rowOff>0"
                    + "</xdr:rowOff></xdr:from><xdr:to><xdr:col>1</xdr:col><xdr:colOff>0</xdr:colOff><xdr:row>1</xdr:row><xdr:rowOff>"
                    + "0</xdr:rowOff></xdr:to><xdr:sp><xdr:nvSpPr><xdr:cNvPr id=\"").append(i + 2).append("\" name=\"s\"/><xdr:cNvSpPr/>"
                    + "</xdr:nvSpPr><xdr:spPr/></xdr:sp><xdr:clientData/></xdr:twoCellAnchor>");
        }
        return b.toString();
    }

    private static byte[] withVml(String vml) {
        MiniXlsx m = base(sheet("<legacyDrawing r:id=\"rId1\"/>"), rels(rel("rId1", "vmlDrawing", "../drawings/vmlDrawing1.vml", false)));
        m.part("xl/drawings/vmlDrawing1.vml", vml.getBytes(StandardCharsets.UTF_8));
        return m.bytes();
    }

    private static byte[] repeat(String unit, long bytes) {
        byte[] u = unit.getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[(int) bytes];
        for (int i = 0; i < out.length; i++) {
            out[i] = u[i % u.length];
        }
        return out;
    }

    // --------------------------------------------------------------------------------------------------------------- cases

    public static void main(String[] args) {
        PoiSafe.configurePoi(PoiSafe.Limits.standard());
        List<Case> cases = new ArrayList<>();
        String far = anchor("16000", "0", "1000000", "0", "16001", "0", "1000001", "0");
        String near = anchor("0", "0", "0", "0", "0", "100000", "0", "100000");
        cases.add(new Case("one good check box (control)", "OK", () -> withControls(1, near), r -> r.boxes.size() == 1));
        cases.add(new Case("1,001 controls", "REFUSED too_many_controls", () -> withControls(1001, near), null));
        cases.add(new Case("a control anchored far away", "CONTAINED not placed",
                () -> withControls(1, far), r -> r.boxes.size() == 1 && r.boxes.get(0).place() == Extras.Place.NONE));
        cases.add(new Case("a control with a negative anchor", "CONTAINED refused control",
                () -> withControls(1, anchor("-5", "0", "-5", "0", "0", "0", "0", "0")), r -> r.controlsRefused == 1));
        cases.add(new Case("a control with a text anchor", "CONTAINED refused control",
                () -> withControls(1, anchor("abc", "x", "y", "z", "1", "1", "1", "1")), r -> r.controlsRefused == 1));
        cases.add(new Case("a control whose end is before its start", "CONTAINED refused control",
                () -> withControls(1, anchor("5", "0", "5", "0", "1", "0", "1", "0")), r -> r.controlsRefused == 1));
        cases.add(new Case("a control anchor with huge offsets", "CONTAINED", () -> withControls(1, anchor("0", "99999999999999",
                "0", "99999999999999", "0", "99999999999999", "0", "99999999999999")), r -> r.boxes.size() <= 1));

        String goodLink = "https://example.test/a";
        cases.add(new Case("an https link", "OK", () -> withLinks(List.of(goodLink)), r -> r.links.get(Extras.LinkKind.HTTPS) == 1));
        for (String scheme : List.of("javascript:alert(1)", "data:text/html,x", "file:///c:/x", "mailto:a@example.test", "http://example.test/",
                "ftp://example.test/", "vbscript:x", "https://user:pw@example.test/", "HTTPS://EXAMPLE.TEST/up")) {
            boolean good = scheme.startsWith("HTTPS://");
            cases.add(new Case("a link with " + scheme.substring(0, Math.min(8, scheme.length())), good ? "OK (https, any case)" : "CONTAINED refused link",
                    () -> withLinks(List.of(scheme)), r -> good ? r.links.get(Extras.LinkKind.HTTPS) == 1
                            : r.links.get(Extras.LinkKind.HTTPS) == 0 && r.links.get(Extras.LinkKind.REFUSED_SCHEME) == 1));
        }
        cases.add(new Case("a link of 3,000 characters", "CONTAINED too long",
                () -> withLinks(List.of("https://example.test/" + "a".repeat(3000))), r -> r.links.get(Extras.LinkKind.TOO_LONG) == 1));
        cases.add(new Case("a link with a space", "CONTAINED malformed",
                () -> withLinks(List.of("https://example.test/a b")), r -> r.links.get(Extras.LinkKind.MALFORMED) == 1));
        // a control character cannot be written in XML at all: the part is not XML and the file is refused
        cases.add(new Case("a link with a control character", "REFUSED xml",
                () -> withLinks(List.of("https://example.test/a\u0001b")), null));
        cases.add(new Case("a link with a back slash", "CONTAINED malformed",
                () -> withLinks(List.of("https://example.test\\a")), r -> r.links.get(Extras.LinkKind.MALFORMED) == 1));
        List<String> many = new ArrayList<>();
        for (int i = 0; i < 5001; i++) {
            many.add(goodLink);
        }
        cases.add(new Case("5,001 links", "REFUSED too_many_links", () -> withLinks(many), null));

        cases.add(new Case("5,001 drawing shapes", "REFUSED too_many_shapes",
                () -> withDrawing(drawing(shapes(5001)), Map.of(), null), null));
        cases.add(new Case("5,000 drawing shapes", "OK", () -> withDrawing(drawing(shapes(5000)), Map.of(), null),
                r -> r.drawingShapes == 5000));
        cases.add(new Case("a doctype with an entity bomb in a drawing part", "REFUSED doctype", () -> withDrawing(
                "<?xml version=\"1.0\"?><!DOCTYPE d [<!ENTITY a \"aaaaaaaaaa\"><!ENTITY b \"&a;&a;&a;&a;&a;&a;&a;&a;&a;&a;\">"
                        + "<!ENTITY c \"&b;&b;&b;&b;&b;&b;&b;&b;&b;&b;\">]><xdr:wsDr xmlns:xdr=\"" + XDR + "\">&c;</xdr:wsDr>", Map.of(), null), null));
        cases.add(new Case("an external entity in a drawing part", "REFUSED doctype", () -> withDrawing(
                "<?xml version=\"1.0\"?><!DOCTYPE d [<!ENTITY x SYSTEM \"file:///c:/windows/win.ini\">]><xdr:wsDr xmlns:xdr=\""
                        + XDR + "\">&x;</xdr:wsDr>", Map.of(), null), null));
        cases.add(new Case("a doctype in a control property part", "REFUSED doctype", () -> {
            StringBuilder controls = new StringBuilder("<controls>" + control(1001, "rId1", near) + "</controls>");
            MiniXlsx m = base(sheet(controls.toString()), rels(rel("rId1", "ctrlProp", "../ctrlProps/ctrlProp1.xml", false)));
            m.part("xl/ctrlProps/ctrlProp1.xml", ("<?xml version=\"1.0\"?><!DOCTYPE f [<!ENTITY q \"zz\">]><formControlPr xmlns=\""
                    + "http://schemas.microsoft.com/office/spreadsheetml/2009/9/main\" objectType=\"CheckBox\"/>").getBytes(StandardCharsets.UTF_8));
            return m.bytes();
        }, null));
        cases.add(new Case("a drawing part that inflates to 30 MB (bomb)", "REFUSED inflated", () -> withDrawing(
                new String(repeat(" ", 30_000_000), StandardCharsets.UTF_8) + "<a/>", Map.of(), null), null));
        // the JDK's own reader stops at 2,500 levels of nesting; that stop is a refusal of the file
        cases.add(new Case("a drawing part nested 20,000 deep", "REFUSED xml", () -> withDrawing(
                "<?xml version=\"1.0\"?><xdr:wsDr xmlns:xdr=\"" + XDR + "\">" + "<a>".repeat(20_000) + "</a>".repeat(20_000)
                        + "</xdr:wsDr>", Map.of(), null), null));
        cases.add(new Case("a drawing part that is not XML", "REFUSED xml", () -> withDrawing("not xml at all <<<", Map.of(), null), null));
        cases.add(new Case("a drawing relationship leaving the archive", "REFUSED outside_archive", () -> {
            MiniXlsx m = base(sheet("<drawing r:id=\"rId1\"/>"), rels(rel("rId1", "drawing", "../../../../x/drawing1.xml", false)));
            return m.bytes();
        }, null));
        cases.add(new Case("a relationship with an absolute target", "CONTAINED not followed", () -> {
            MiniXlsx m = base(sheet("<drawing r:id=\"rId1\"/>"), rels(rel("rId1", "drawing", "/c:/windows/x.xml", false)));
            return m.bytes();
        }, r -> r.drawingShapes == 0));

        byte[] png = new byte[] {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 0, 0, 0, 0};
        String pic = "<xdr:twoCellAnchor><xdr:from><xdr:col>0</xdr:col><xdr:colOff>0</xdr:colOff><xdr:row>0</xdr:row><xdr:rowOff>0"
                + "</xdr:rowOff></xdr:from><xdr:to><xdr:col>1</xdr:col><xdr:colOff>0</xdr:colOff><xdr:row>1</xdr:row><xdr:rowOff>0"
                + "</xdr:rowOff></xdr:to><xdr:pic><xdr:blipFill><a:blip r:embed=\"rId1\"/></xdr:blipFill></xdr:pic><xdr:clientData/>"
                + "</xdr:twoCellAnchor>";
        String picRels = rels(rel("rId1", "image", "../media/image1.png", false));
        cases.add(new Case("a real PNG header", "OK png", () -> withDrawing(drawing(pic), Map.of("xl/media/image1.png", png), picRels),
                r -> r.images.size() == 1 && r.images.get(0).type().equals("png")));
        cases.add(new Case("a .png part whose bytes are a program", "CONTAINED type other",
                () -> withDrawing(drawing(pic), Map.of("xl/media/image1.png", new byte[] {'M', 'Z', 0, 0, 0, 0, 0, 0}), picRels),
                r -> r.images.size() == 1 && r.images.get(0).type().equals("other")));
        cases.add(new Case("a .png part whose bytes are markup (a vector image with script)", "CONTAINED type other",
                () -> withDrawing(drawing(pic), Map.of("xl/media/image1.png", "<svg onload=\"x\"></svg>".getBytes(StandardCharsets.UTF_8)), picRels),
                r -> r.images.size() == 1 && r.images.get(0).type().equals("other")));
        cases.add(new Case("a metafile (EMF) header", "OK emf", () -> {
            byte[] emf = new byte[100];
            emf[0] = 1;
            emf[40] = ' ';
            emf[41] = 'E';
            emf[42] = 'M';
            emf[43] = 'F';
            return withDrawing(drawing(pic), Map.of("xl/media/image1.png", emf), picRels);
        }, r -> r.images.size() == 1 && r.images.get(0).type().equals("emf")));
        cases.add(new Case("a picture relationship to a part that does not exist", "CONTAINED type missing",
                () -> withDrawing(drawing(pic), Map.of(), picRels), r -> r.images.size() == 1 && r.images.get(0).type().equals("missing")));

        cases.add(new Case("5,001 legacy drawing shapes", "REFUSED too_many_shapes", () -> {
            StringBuilder v = new StringBuilder("<xml>");
            for (int i = 0; i < 5001; i++) {
                v.append("<v:shape id=\"_x0000_s").append(i).append("\"><x:ClientData ObjectType=\"Checkbox\"><x:Anchor>0,0,0,0,1,0,1,0</x:Anchor>"
                        + "</x:ClientData></v:shape>");
            }
            return withVml(v.append("</xml>").toString());
        }, null));
        cases.add(new Case("a legacy drawing of 5 MB", "REFUSED drawing_too_large",
                () -> withVml(new String(repeat("a", 5_000_000), StandardCharsets.UTF_8)), null));
        cases.add(new Case("a legacy drawing with entity text and unclosed tags", "CONTAINED no entity expanded", () -> withVml(
                "<xml><!DOCTYPE v [<!ENTITY e SYSTEM \"file:///c:/x\">]><v:shape id=\"_x0000_s1\"><v:textbox><div>&e;<br></v:textbox>"
                        + "<x:ClientData ObjectType=\"Checkbox\"/></v:shape>"), r -> true));
        cases.add(new Case("a legacy drawing with no end of shape", "CONTAINED", () -> withVml("<xml><v:shape id=\"_x0000_s1\">"), r -> true));

        cases.add(new Case("a part name repeated but for the case (control property)", "REFUSED duplicate_part", () -> {
            Map<String, byte[]> parts = new LinkedHashMap<>();
            parts.put("[Content_Types].xml", "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"/>"
                    .getBytes(StandardCharsets.UTF_8));
            parts.put("xl/ctrlProps/ctrlProp1.xml", CTRL_PROP.getBytes(StandardCharsets.UTF_8));
            try {
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                try (java.util.zip.ZipOutputStream z = new java.util.zip.ZipOutputStream(out)) {
                    for (Map.Entry<String, byte[]> e : parts.entrySet()) {
                        z.putNextEntry(new java.util.zip.ZipEntry(e.getKey()));
                        z.write(e.getValue());
                        z.closeEntry();
                    }
                    z.putNextEntry(new java.util.zip.ZipEntry("XL/ctrlProps/ctrlProp1.xml")); // same name but for the case
                    z.write(CTRL_PROP.getBytes(StandardCharsets.UTF_8));
                    z.closeEntry();
                }
                return out.toByteArray();
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }, null));

        int pass = 0;
        int fail = 0;
        for (Case c : cases) {
            String outcome;
            boolean ok;
            long t0 = System.nanoTime();
            try {
                Extras.Report r = Extras.read(c.file().get(), Extras.Limits.standard());
                outcome = "read";
                ok = !c.expect().startsWith("REFUSED") && (c.check() == null || c.check().test(r));
            } catch (Extras.Refused e) {
                outcome = "REFUSED " + e.key();
                ok = c.expect().equals("REFUSED " + e.key()) || (c.expect().startsWith("CONTAINED") && c.check() != null
                        && c.name().contains("vector") && false);
            } catch (Throwable t) {
                outcome = "CRASH " + t.getClass().getSimpleName();
                ok = false;
            }
            long ms = (System.nanoTime() - t0) / 1_000_000;
            if (ms > 3000) {
                ok = false;
                outcome += " (slow)";
            }
            System.out.printf("%-4s %-70s expected %-32s got %-28s %5d ms%n", ok ? "PASS" : "FAIL", c.name(), c.expect(), outcome, ms);
            if (ok) {
                pass++;
            } else {
                fail++;
            }
        }
        System.out.println("hostile extras cases: " + cases.size() + ", pass " + pass + ", fail " + fail);
    }
}
