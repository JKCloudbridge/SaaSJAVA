package app.platform.sheets.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Nothing of format version 1 moved (SH1A, ADR-0251): the proof, turned from the shim of the proof of concept into a
 * test. For every document of the generated set, the platform's sample forms and the documents of the golden file:
 *
 * <ul>
 *   <li>the validator that now dispatches on the format gives the canonical text and the SHA-256 that the version 1
 *       path alone gives;</li>
 *   <li>read as a document of one sheet and written back as format version 1, it has the same bytes and the same
 *       hash;</li>
 *   <li>an archived copy (the canonical text compressed) keeps its hash;</li>
 *   <li>the grid a reader that knows only format version 1 gets from the version 2 form is the same grid.</li>
 * </ul>
 *
 * <p>The comparison with what version 1 gave <em>before</em> version 2 existed is {@link SheetFormatOneGoldenTest}'s.
 */
class SheetFormatCompatibilityTest {

    private static final SheetGridValidator VALIDATOR = new SheetGridValidator(SheetProperties.Limits.standard(),
            new SheetSchemas(), true);

    private static Map<String, byte[]> everyDocument() {
        Map<String, byte[]> all = new LinkedHashMap<>(SheetFormatOneGoldenTest.documents());
        all.putAll(SheetFormatOneGoldenTest.sampleForms());
        return all;
    }

    @Test
    void theValidatorThatDispatchesGivesWhatTheVersionOnePathAloneGives() {
        everyDocument().forEach((name, bytes) -> {
            SheetGridValidator.Result viaDispatch = VALIDATOR.check(bytes);
            SheetGridValidator.Result alone = VALIDATOR.checkVersionOne(bytes);

            assertThat(viaDispatch.problems()).as(name).isEmpty();
            assertThat(viaDispatch.checked().canonical()).as(name).isEqualTo(alone.checked().canonical());
            assertThat(viaDispatch.checked().hash()).as(name).isEqualTo(alone.checked().hash());
            assertThat(viaDispatch.checked().formatVersion()).as(name).isEqualTo(1);
        });
    }

    @Test
    void aVersionOneDocumentReadAsOneSheetAndWrittenBackAsVersionOneIsIdentical() {
        int checked = 0;
        for (Map.Entry<String, byte[]> entry : everyDocument().entrySet()) {
            String name = entry.getKey();
            SheetGridValidator.Result one = VALIDATOR.check(entry.getValue());

            SheetGridValidator.Result two = VALIDATOR.check(TwoDocs.asOneSheet(one.checked().canonical())
                    .getBytes(StandardCharsets.UTF_8));

            assertThat(two.problems()).as(name + " as one sheet").isEmpty();
            assertThat(two.checked().formatVersion()).as(name).isEqualTo(2);
            SheetGridValidator.Result back = VALIDATOR.check(TwoDocs.backToVersionOne(two.checked().canonical()));
            assertThat(back.problems()).as(name + " back").isEmpty();
            assertThat(back.checked().canonical()).as(name + " canonical text").isEqualTo(one.checked().canonical());
            assertThat(back.checked().hash()).as(name + " hash").isEqualTo(one.checked().hash());
            checked++;
        }
        assertThat(checked).isGreaterThan(120);
    }

    @Test
    void anArchivedCopyKeepsItsHashInBothFormats() throws IOException {
        for (Map.Entry<String, byte[]> entry : everyDocument().entrySet()) {
            SheetGridValidator.Result one = VALIDATOR.check(entry.getValue());
            SheetGridValidator.Result two = VALIDATOR.check(TwoDocs.asOneSheet(one.checked().canonical())
                    .getBytes(StandardCharsets.UTF_8));
            for (SheetGridValidator.Checked checked : new SheetGridValidator.Checked[] {one.checked(),
                two.checked()}) {
                byte[] unpacked = gunzip(gzip(checked.canonical().getBytes(StandardCharsets.UTF_8)));

                assertThat(SheetGridWriter.sha256(unpacked)).as(entry.getKey()).isEqualTo(checked.hash());
            }
        }
    }

    @Test
    void aReaderThatKnowsOnlyVersionOneGetsTheSameGridFromEitherForm() {
        everyDocument().forEach((name, bytes) -> {
            SheetGridValidator.Result one = VALIDATOR.check(bytes);
            SheetGridValidator.Result two = VALIDATOR.check(TwoDocs.asOneSheet(one.checked().canonical())
                    .getBytes(StandardCharsets.UTF_8));
            JsonNode storedOne = GridDocs.JSON.readTree(one.checked().canonical());
            JsonNode storedTwo = GridDocs.JSON.readTree(two.checked().canonical());

            assertThat(SheetGridReader.read(storedTwo)).as(name).isEqualTo(SheetGridReader.read(storedOne));
        });
    }

    @Test
    void aDocumentOfVersionTwoIsRefusedByTheVersionOneValidatorSoNoneIsStoredByAccident() {
        SheetGridValidator.Result one = VALIDATOR.check(GridDocs.bytes(GridDocs.minimal()));
        byte[] two = TwoDocs.asOneSheet(one.checked().canonical()).getBytes(StandardCharsets.UTF_8);

        assertThat(VALIDATOR.checkVersionOne(two).accepted()).isFalse();
        assertThat(VALIDATOR.checkVersionOne(TwoDocs.bytes(TwoDocs.rich())).accepted()).isFalse();
    }

    @Test
    void theMessageOfTheSwitchIsTheMessageOfTheCopyRefusal() {
        assertThat(app.platform.sheets.SheetMoveRefusal.Reason.EARLIER_FORMAT.words())
                .isEqualTo(SheetFormats.EARLIER_FORMAT);
    }

    private static byte[] gzip(byte[] plain) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
            gz.write(plain);
        }
        return out.toByteArray();
    }

    private static byte[] gunzip(byte[] packed) throws IOException {
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(packed))) {
            return in.readAllBytes();
        }
    }
}
