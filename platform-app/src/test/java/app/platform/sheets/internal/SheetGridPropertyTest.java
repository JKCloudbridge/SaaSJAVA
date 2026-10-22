package app.platform.sheets.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Random;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.LongRange;

/**
 * Properties of the canonical form (FA3, ADR-0186) over many generated grids: the same grid has the same text and the
 * same hash whatever order its keys arrive in; the canonical text is a fixed point (checking it again gives itself);
 * and a grid that is accepted stays accepted when it is written and read back. A hash that depended on the order of
 * keys would make two saves of one grid look different and one grid look like two.
 */
class SheetGridPropertyTest {

    private static final SheetGridValidator VALIDATOR = new SheetGridValidator(SheetProperties.Limits.standard(),
            new SheetSchemas());

    private static Map<String, Object> grid(long seed) {
        Random random = new Random(seed);
        int dimension = 1 + random.nextInt(2);
        int rows = dimension == 1 ? 0 : 1 + random.nextInt(40);
        int columns = 2 + random.nextInt(14);
        return GridDocs.template(rows, columns, dimension, random.nextDouble() * 0.5, seed);
    }

    @Property(tries = 60)
    void theHashDoesNotDependOnTheOrderOfKeys(@ForAll @LongRange(min = 1, max = 1_000_000) long seed) {
        Map<String, Object> doc = grid(seed);
        SheetGridValidator.Result first = VALIDATOR.check(GridDocs.bytes(doc));
        assertThat(first.problems()).isEmpty();

        for (int i = 0; i < 3; i++) {
            SheetGridValidator.Result other = VALIDATOR.check(GridDocs.bytes(GridDocs.shuffled(doc,
                    new Random(seed * 31 + i))));
            assertThat(other.checked().hash()).isEqualTo(first.checked().hash());
            assertThat(other.checked().canonical()).isEqualTo(first.checked().canonical());
        }
    }

    @Property(tries = 60)
    void theCanonicalTextIsAFixedPoint(@ForAll @LongRange(min = 1, max = 1_000_000) long seed) {
        SheetGridValidator.Result first = VALIDATOR.check(GridDocs.bytes(grid(seed)));

        SheetGridValidator.Result again = VALIDATOR.check(first.checked().canonical()
                .getBytes(StandardCharsets.UTF_8));

        assertThat(again.problems()).isEmpty();
        assertThat(again.checked().canonical()).isEqualTo(first.checked().canonical());
        assertThat(again.checked().hash()).isEqualTo(first.checked().hash());
        assertThat(SheetGridWriter.hashOf(first.checked().canonical()))
                .isEqualTo(SheetGridWriter.hex(first.checked().hash()));
    }

    @Property(tries = 40)
    void oneChangedCellGivesAnotherHash(@ForAll @LongRange(min = 1, max = 1_000_000) long seed) {
        Map<String, Object> doc = grid(seed);
        SheetGridValidator.Result first = VALIDATOR.check(GridDocs.bytes(doc));
        // The title is the top left cell of a merged area, so it can hold text whatever the shape of the grid.
        GridDocs.cells(doc).put("h0:c000", GridDocs.map("value", "Changed " + seed));

        SheetGridValidator.Result changed = VALIDATOR.check(GridDocs.bytes(doc));

        assertThat(changed.problems()).isEmpty();
        assertThat(changed.checked().hash()).isNotEqualTo(first.checked().hash());
    }
}
