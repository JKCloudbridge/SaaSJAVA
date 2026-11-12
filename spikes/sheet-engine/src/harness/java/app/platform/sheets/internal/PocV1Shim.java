package app.platform.sheets.internal;

import app.platform.sheets.internal.SpreadsheetImporter.Format;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * POC 1, WP5 and WP7: a format-version-2 SHIM over the real validator. Two claims are tested on every stored-shape document we can make:
 * (1) dispatch: a version 1 document goes through the unchanged version 1 path, so its canonical text and SHA-256 are what they
 * always were; (2) the "several sheets" door: a version 2 document is {"formatVersion":2,"sheets":[{"key":"main", ...the version 1
 * body without its format number...}]}; a version 1 document read as "one sheet with the default key" and written back gives the
 * same bytes and the same hash. The documents: 600 generated templates of every dimension and size the property test uses, the
 * two minimal documents, the platform's sample form read through the upload, and the owner's plant workbook read through the upload.
 */
public final class PocV1Shim {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final SheetGridValidator VALIDATOR = new SheetGridValidator(SheetProperties.Limits.standard(),
            new SheetSchemas());

    private PocV1Shim() {
    }

    /** The version 2 reading of a version 1 canonical document. */
    static String toV2(String v1Canonical) {
        ObjectNode body = (ObjectNode) JSON.readTree(v1Canonical);
        body.remove("formatVersion");
        ObjectNode sheet = JSON.createObjectNode();
        sheet.put("key", "main");
        sheet.setAll(body);
        ArrayNode sheets = JSON.createArrayNode();
        sheets.add(sheet);
        ObjectNode root = JSON.createObjectNode();
        root.put("formatVersion", 2);
        root.set("sheets", sheets);
        return JSON.writeValueAsString(root);
    }

    /** Back: the one sheet with the default key becomes a version 1 document, checked and written by the unchanged path. */
    static SheetGridValidator.Result toV1(String v2) {
        JsonNode root = JSON.readTree(v2);
        ObjectNode body = (ObjectNode) root.get("sheets").get(0).deepCopy();
        body.remove("key");
        body.put("formatVersion", 1);
        return VALIDATOR.check(JSON.writeValueAsString(body).getBytes(StandardCharsets.UTF_8));
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
                documents.add(importer.read(workbook, Format.XLSX, SpreadsheetImporterTest.NAMED_ROWS).checked()
                        .canonical().getBytes(StandardCharsets.UTF_8));
                fromUploads++;
            } catch (ImportRefusal refusal) {
                System.out.println("  one workbook was refused by the upload (" + refusal.code() + "): not part of the run");
            }
        }

        int total = 0, valid = 0, dispatchSame = 0, roundTripSame = 0, hashSame = 0, archivedSame = 0, v2Shaped = 0;
        for (byte[] bytes : documents) {
            total++;
            SheetGridValidator.Result direct = VALIDATOR.check(bytes);
            if (!direct.problems().isEmpty()) {
                continue;
            }
            valid++;
            // (1) the version 1 path is the only path for a version 1 document: same text, same hash
            SheetGridValidator.Result stored = VALIDATOR.check(direct.checked().canonical().getBytes(StandardCharsets.UTF_8));
            if (stored.checked().canonical().equals(direct.checked().canonical())
                    && java.util.Arrays.equals(stored.checked().hash(), direct.checked().hash())) {
                dispatchSame++;
            }
            // (2) the version 2 reading and back
            String v2 = toV2(direct.checked().canonical());
            JsonNode node = JSON.readTree(v2);
            if (node.get("formatVersion").asInt() == 2 && node.get("sheets").size() == 1
                    && "main".equals(node.get("sheets").get(0).get("key").asString())) {
                v2Shaped++;
            }
            SheetGridValidator.Result back = toV1(v2);
            if (back.problems().isEmpty() && back.checked().canonical().equals(direct.checked().canonical())) {
                roundTripSame++;
            }
            if (back.problems().isEmpty() && java.util.Arrays.equals(back.checked().hash(), direct.checked().hash())) {
                hashSame++;
            }
            // an archived fill-in keeps the canonical cells as one gzip document and the row keeps their hash: the same
            // bytes must come out of the same gzip, and the hash of the bytes is the row's hash
            byte[] canonical = direct.checked().canonical().getBytes(StandardCharsets.UTF_8);
            java.io.ByteArrayOutputStream gz = new java.io.ByteArrayOutputStream();
            try (java.util.zip.GZIPOutputStream out = new java.util.zip.GZIPOutputStream(gz)) {
                out.write(canonical);
            }
            byte[] readBack = new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(gz.toByteArray())).readAllBytes();
            if (java.util.Arrays.equals(SheetGridWriter.sha256(readBack), direct.checked().hash())) {
                archivedSame++;
            }
        }
        System.out.println("WP5/WP7 version-2 shim: documents=" + total + " (of which from uploads " + fromUploads
                + ") valid=" + valid + " ; version 1 path gives the same canonical text and hash=" + dispatchSame
                + " ; read as version 2 (one sheet, key main) has the expected shape=" + v2Shaped
                + " ; and back: same canonical text=" + roundTripSame + ", same SHA-256=" + hashSame
                + " ; archive round trip keeps the hash=" + archivedSame);
        // a version 2 document that does not name its sheets must not be accepted by the version 1 validator
        SheetGridValidator.Result refused = VALIDATOR.check("{\"formatVersion\":2,\"sheets\":[]}".getBytes(StandardCharsets.UTF_8));
        System.out.println("a version 2 document is refused by the unchanged version 1 validator (so nothing can store one by accident): "
                + !refused.problems().isEmpty());
        System.exit(valid == total && dispatchSame == valid && roundTripSame == valid && hashSame == valid && archivedSame == valid
                && !refused.problems().isEmpty() ? 0 : 1);
    }

    static Map<String, Object> unused() {
        return Map.of();
    }
}
