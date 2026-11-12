package app.platform.sheets.internal;

import app.platform.sheets.internal.SpreadsheetImporter.Format;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * POC 2, WP2 and WP3: the 605-document shim of POC 1 run again against the REVISED draft of format version 2 ({@link PocV2Doc}: a
 * list of sheets with a title, a fillable flag, a freeze, a print set-up and typed input cells). For every document the real
 * version 1 validator accepts: (1) the version 1 path still gives the same canonical text and hash; (2) the version 1 document read
 * as a version 2 document with one sheet is accepted by the new validator; (3) written back as version 1 it has IDENTICAL bytes and
 * hash; (4) an archived copy keeps its hash. Then the guards of the other direction: a version 2 document is refused by the unchanged
 * version 1 validator, a version 2 document with typed cells too, and a four-sheet document has a canonical text and hash that do not
 * depend on the order of the keys (and a hash that does depend on the order of the TABS, because the order of the tabs is content).
 */
public final class PocV2Shim {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final SheetGridValidator V1 = new SheetGridValidator(SheetProperties.Limits.standard(), new SheetSchemas());

    private PocV2Shim() {
    }

    public static void main(String[] args) throws Exception {
        List<byte[]> documents = new ArrayList<>();
        Random random = new Random(20261112);
        for (int i = 0; i < 600; i++) {
            long seed = 1 + random.nextInt(1_000_000);
            int dimension = 1 + random.nextInt(2);
            int rows = dimension == 1 ? 0 : 1 + random.nextInt(60);
            int columns = 2 + random.nextInt(30);
            documents.add(GridDocs.bytes(GridDocs.template(rows, columns, dimension, random.nextDouble() * 0.5, seed)));
        }
        documents.add(GridDocs.bytes(GridDocs.minimal()));
        documents.add(GridDocs.bytes(GridDocs.minimalOneDimension()));
        int fromUploads = 0;
        SpreadsheetImporter importer = SpreadsheetImporterTest.importer(ImportProperties.standard());
        List<byte[]> workbooks = new ArrayList<>(List.of(SampleForms.analysisForm(), SampleForms.analysisForm(false)));
        if (args.length > 0) {
            workbooks.add(Files.readAllBytes(Path.of(args[0])));
        }
        for (byte[] workbook : workbooks) {
            try {
                documents.add(importer.read(workbook, Format.XLSX, SpreadsheetImporterTest.NAMED_ROWS).checked().canonical()
                        .getBytes(StandardCharsets.UTF_8));
                fromUploads++;
            } catch (ImportRefusal refusal) {
                System.out.println("  one workbook was refused by the upload (" + refusal.code() + "): not part of the run");
            }
        }
        int total = 0;
        int valid = 0;
        int v1Same = 0;
        int v2Accepted = 0;
        int backSameText = 0;
        int backSameHash = 0;
        int archived = 0;
        for (byte[] bytes : documents) {
            total++;
            SheetGridValidator.Result direct = V1.check(bytes);
            if (!direct.problems().isEmpty()) {
                continue;
            }
            valid++;
            SheetGridValidator.Result stored = V1.check(direct.checked().canonical().getBytes(StandardCharsets.UTF_8));
            if (stored.checked().canonical().equals(direct.checked().canonical())
                    && java.util.Arrays.equals(stored.checked().hash(), direct.checked().hash())) {
                v1Same++;
            }
            String v2 = PocV1Shim.toV2(direct.checked().canonical());
            PocV2Doc.Result r2 = PocV2Doc.check(v2.getBytes(StandardCharsets.UTF_8));
            if (r2.accepted()) {
                v2Accepted++;
                // back to version 1: the one sheet with no version 2 parts, written by the unchanged version 1 path
                SheetGridValidator.Result back = PocV1Shim.toV1(r2.checked().canonical());
                if (back.problems().isEmpty() && back.checked().canonical().equals(direct.checked().canonical())) {
                    backSameText++;
                }
                if (back.problems().isEmpty() && java.util.Arrays.equals(back.checked().hash(), direct.checked().hash())) {
                    backSameHash++;
                }
            }
            byte[] canonical = direct.checked().canonical().getBytes(StandardCharsets.UTF_8);
            java.io.ByteArrayOutputStream gz = new java.io.ByteArrayOutputStream();
            try (java.util.zip.GZIPOutputStream out = new java.util.zip.GZIPOutputStream(gz)) {
                out.write(canonical);
            }
            byte[] readBack = new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(gz.toByteArray())).readAllBytes();
            if (java.util.Arrays.equals(SheetGridWriter.sha256(readBack), direct.checked().hash())) {
                archived++;
            }
        }
        System.out.println("POC 2 version-2 shim (revised draft): documents=" + total + " (from uploads " + fromUploads + ") valid=" + valid
                + " ; version 1 path same text and hash=" + v1Same + " ; read as version 2, one sheet, accepted by the new validator=" + v2Accepted
                + " ; back to version 1: same canonical text=" + backSameText + ", same SHA-256=" + backSameHash + " ; archive round trip keeps the hash=" + archived);

        // the other direction
        boolean v2Refused = !V1.check(PocV1Shim.toV2(V1.check(GridDocs.bytes(GridDocs.minimal())).checked().canonical()).getBytes(StandardCharsets.UTF_8))
                .problems().isEmpty();
        boolean typedRefused = !V1.check(PocFill3.plantLikeTemplate()).problems().isEmpty();
        System.out.println("a version 2 document is refused by the unchanged version 1 validator=" + v2Refused
                + " ; so is a version 2 document with typed cells=" + typedRefused);

        // four sheets: canonical text and hash do not depend on the order of the keys; they do depend on the order of the tabs
        List<Object> four = new ArrayList<>();
        for (int i = 1; i <= 4; i++) {
            java.util.Map<String, Object> s = PocFill3.dataSheet("t" + i);
            s.put("title", "Tab " + i);
            four.add(s);
        }
        String doc = GridDocs.json(GridDocs.map("formatVersion", 2, "sheets", four));
        PocV2Doc.Result a = PocV2Doc.check(doc.getBytes(StandardCharsets.UTF_8));
        PocV2Doc.Result b = PocV2Doc.check(PocV2Doc.reversedKeys(doc).getBytes(StandardCharsets.UTF_8));
        java.util.Collections.swap(four, 0, 1);
        PocV2Doc.Result c = PocV2Doc.check(GridDocs.json(GridDocs.map("formatVersion", 2, "sheets", four)).getBytes(StandardCharsets.UTF_8));
        boolean keyOrder = a.accepted() && b.accepted() && a.checked().canonical().equals(b.checked().canonical())
                && java.util.Arrays.equals(a.checked().hash(), b.checked().hash());
        boolean tabOrder = a.accepted() && c.accepted() && !java.util.Arrays.equals(a.checked().hash(), c.checked().hash());
        System.out.println("four-sheet document: accepted=" + a.accepted() + ", same text and hash when the keys are in reverse order=" + keyOrder
                + ", the order of the tabs is part of the hash=" + tabOrder + ", " + a.checked().bytes() + " bytes");
        JsonNode node = JSON.readTree(a.checked().canonical());
        System.out.println("canonical form starts " + node.get("formatVersion") + " with " + node.get("sheets").size() + " sheets");
        // limits of the list
        List<Object> nine = new ArrayList<>();
        for (int i = 1; i <= 9; i++) {
            nine.add(PocFill3.dataSheet("t" + i));
        }
        PocV2Doc.Result tooMany = PocV2Doc.check(GridDocs.json(GridDocs.map("formatVersion", 2, "sheets", nine)).getBytes(StandardCharsets.UTF_8));
        List<Object> dup = new ArrayList<>(List.of(PocFill3.dataSheet("same"), PocFill3.dataSheet("same")));
        PocV2Doc.Result dupKey = PocV2Doc.check(GridDocs.json(GridDocs.map("formatVersion", 2, "sheets", dup)).getBytes(StandardCharsets.UTF_8));
        // the same identifier in two sheets is allowed (identifiers are unique within a sheet)
        List<Object> sameIds = new ArrayList<>(List.of(PocFill3.dataSheet("one"), PocFill3.dataSheet("two")));
        PocV2Doc.Result sameIdOk = PocV2Doc.check(GridDocs.json(GridDocs.map("formatVersion", 2, "sheets", sameIds)).getBytes(StandardCharsets.UTF_8));
        System.out.println("nine sheets refused=" + codes(tooMany) + " ; duplicate sheet key refused=" + codes(dupKey)
                + " ; the same column identifiers in two sheets accepted=" + sameIdOk.accepted());
        boolean ok = valid == total && v1Same == valid && v2Accepted == valid && backSameText == valid && backSameHash == valid
                && archived == valid && v2Refused && typedRefused && keyOrder && tabOrder && !tooMany.accepted() && !dupKey.accepted() && sameIdOk.accepted();
        System.exit(ok ? 0 : 1);
    }

    private static String codes(PocV2Doc.Result r) {
        return r.accepted() ? "ACCEPTED" : r.problems().stream().map(PocV2Doc.Problem::code).distinct().toList().toString();
    }
}
