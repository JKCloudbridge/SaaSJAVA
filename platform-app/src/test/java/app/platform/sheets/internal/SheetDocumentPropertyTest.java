package app.platform.sheets.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.LongRange;

/**
 * Properties of the canonical form of format version 2 over many generated documents (SH1A, ADR-0251), the companions
 * of {@link SheetGridPropertyTest}: the same document has the same text and the same hash whatever order its keys
 * arrive in and however its colours are cased; the canonical text is a fixed point; and a changed look gives another
 * hash. A hash that depended on the order of keys would make one document look like two.
 */
class SheetDocumentPropertyTest {

    private static final SheetGridValidator VALIDATOR = new SheetGridValidator(SheetProperties.Limits.standard(),
            new SheetSchemas(), true);

    @SuppressWarnings("unchecked") // the generator in GridDocs builds the cells as maps
    private static Map<String, Object> document(long seed, boolean upperCase) {
        Random random = new Random(seed);
        int dimension = 1 + random.nextInt(2);
        int rows = dimension == 1 ? 0 : 1 + random.nextInt(40);
        int columns = 2 + random.nextInt(14);
        Map<String, Object> document = TwoDocs.wrap(GridDocs.template(rows, columns, dimension,
                random.nextDouble() * 0.5, seed));
        Map<String, Object> cell = (Map<String, Object>) TwoDocs.cells(document).get("h0:c000");
        cell.put("look", GridDocs.map("fill", colour(random, upperCase), "colour", colour(random, upperCase),
                "font", SheetLook.FONTS.get(random.nextInt(SheetLook.FONTS.size())), "wrap", random.nextBoolean(),
                "numberFormat", SheetLook.NUMBER_FORMATS.get(random.nextInt(SheetLook.NUMBER_FORMATS.size())),
                "borders", GridDocs.map("top", SheetLook.BORDER_STYLES.get(random.nextInt(6)), "left",
                        SheetLook.BORDER_STYLES.get(random.nextInt(6)))));
        return document;
    }

    private static String colour(Random random, boolean upperCase) {
        String hex = String.format("#%06x", random.nextInt(0x1000000));
        return upperCase ? "#" + hex.substring(1).toUpperCase(Locale.ROOT) : hex;
    }

    @Property(tries = 60)
    void theHashDoesNotDependOnTheOrderOfKeysOrTheCaseOfAColour(
            @ForAll @LongRange(min = 1, max = 1_000_000) long seed) {
        SheetGridValidator.Result first = VALIDATOR.check(GridDocs.bytes(document(seed, false)));
        assertThat(first.problems()).isEmpty();

        for (int i = 0; i < 3; i++) {
            Object shuffled = GridDocs.shuffled(document(seed, i % 2 == 0), new Random(seed * 31 + i));
            SheetGridValidator.Result other = VALIDATOR.check(GridDocs.bytes(shuffled));

            assertThat(other.problems()).isEmpty();
            assertThat(other.checked().hash()).isEqualTo(first.checked().hash());
            assertThat(other.checked().canonical()).isEqualTo(first.checked().canonical());
        }
    }

    @Property(tries = 60)
    void theCanonicalTextIsAFixedPoint(@ForAll @LongRange(min = 1, max = 1_000_000) long seed) {
        SheetGridValidator.Result first = VALIDATOR.check(GridDocs.bytes(document(seed, true)));

        SheetGridValidator.Result again = VALIDATOR.check(first.checked().canonical().getBytes(StandardCharsets.UTF_8));

        assertThat(again.problems()).isEmpty();
        assertThat(again.checked().canonical()).isEqualTo(first.checked().canonical());
        assertThat(again.checked().hash()).isEqualTo(first.checked().hash());
        assertThat(SheetGridWriter.hashOf(first.checked().canonical()))
                .isEqualTo(SheetGridWriter.hex(first.checked().hash()));
    }

    @Property(tries = 40)
    void aChangedLookGivesAnotherHash(@ForAll @LongRange(min = 1, max = 1_000_000) long seed) {
        Map<String, Object> document = document(seed, false);
        SheetGridValidator.Result first = VALIDATOR.check(GridDocs.bytes(document));
        @SuppressWarnings("unchecked") // built by this class
        Map<String, Object> look = (Map<String, Object>) ((Map<String, Object>) TwoDocs.cells(document)
                .get("h0:c000")).get("look");
        look.put("fill", "#010203");
        if (first.checked().canonical().contains("#010203")) {
            look.put("fill", "#030201");
        }

        SheetGridValidator.Result changed = VALIDATOR.check(GridDocs.bytes(document));

        assertThat(changed.problems()).isEmpty();
        assertThat(changed.checked().hash()).isNotEqualTo(first.checked().hash());
    }
}
