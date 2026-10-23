package app.platform.sheets.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.LongRange;

/**
 * Properties of the canonical form of a fill-in (FF1, ADR-0192) over many generated fill-ins of a sheet with every
 * column
 * type: the same fill-in has the same text and the same hash whatever order its keys arrive in; the canonical text is a
 * fixed point (checking it again gives itself); and what is stored reads back as the same document. A hash that
 * depended
 * on the order of keys would make two saves of one fill-in look different, and a canonical text that moved when it was
 * checked again would make "has this changed?" lie.
 */
class FillPropertyTest {

    private static final SheetProperties.Limits LIMITS = SheetProperties.Limits.standard();
    private static final FillValidator VALIDATOR = new FillValidator(new FillProperties(1_000_000, 1000, 6), LIMITS);
    private static final String[] WORDS = {"Fire", "Exit", "Guard", "Check", "Valid", "Panel", "Alarm", "Stock"};

    private static Map<String, Object> grid() {
        Map<String, Object> grid = GridDocs.minimalOneDimension();
        List<Map<String, Object>> columns = GridDocs.list(grid, "columns");
        columns.clear();
        columns.add(GridDocs.column("t", "Text", "text"));
        columns.add(GridDocs.column("n", "Number", "number"));
        columns.add(GridDocs.column("d", "Date", "date"));
        columns.add(GridDocs.column("p", "Result", "picklist"));
        columns.add(GridDocs.column("b", "Done", "boolean"));
        return grid;
    }

    private static FillStructure structure() {
        return new FillStructure(SheetGridReader.read(GridDocs.JSON.readTree(GridDocs.json(grid()))));
    }

    /** A fill-in of up to forty rows; each cell is empty or holds a value that fits its column. */
    private static Map<String, Object> fill(long seed) {
        Random random = new Random(seed);
        List<Map<String, Object>> rows = new ArrayList<>();
        int count = random.nextInt(41);
        for (int i = 0; i < count; i++) {
            Map<String, Object> cells = new LinkedHashMap<>();
            if (random.nextBoolean()) {
                cells.put("t", WORDS[random.nextInt(WORDS.length)] + " " + random.nextInt(100));
            }
            if (random.nextBoolean()) {
                cells.put("n", (random.nextBoolean() ? "-" : "") + random.nextInt(100_000) + "."
                        + random.nextInt(100));
            }
            if (random.nextBoolean()) {
                cells.put("d", String.format("20%02d-%02d-%02d", random.nextInt(30), 1 + random.nextInt(12),
                        1 + random.nextInt(28)));
            }
            if (random.nextBoolean()) {
                cells.put("p", List.of("Pass", "Fail", "Not applicable").get(random.nextInt(3)));
            }
            if (random.nextBoolean()) {
                cells.put("b", random.nextBoolean());
            }
            if (random.nextInt(5) == 0) {
                cells.put("t", ""); // an empty text is nothing
            }
            rows.add(GridDocs.map("id", String.format("row%03d", i), "cells", cells));
        }
        return GridDocs.map("formatVersion", 1, "rows", rows);
    }

    @Property(tries = 60)
    void theHashDoesNotDependOnTheOrderOfKeys(@ForAll @LongRange(min = 1, max = 1_000_000) long seed) {
        Map<String, Object> doc = fill(seed);
        FillValidator.Result first = VALIDATOR.check(GridDocs.bytes(doc), structure());
        assertThat(first.problems()).isEmpty();

        for (int i = 0; i < 3; i++) {
            FillValidator.Result other = VALIDATOR.check(GridDocs.bytes(GridDocs.shuffled(doc,
                    new Random(seed * 31 + i))), structure());
            assertThat(other.checked().hash()).isEqualTo(first.checked().hash());
            assertThat(other.checked().canonical()).isEqualTo(first.checked().canonical());
        }
    }

    @Property(tries = 60)
    void theCanonicalTextIsAFixedPointAndReadsBack(@ForAll @LongRange(min = 1, max = 1_000_000) long seed) {
        FillValidator.Result first = VALIDATOR.check(GridDocs.bytes(fill(seed)), structure());

        FillValidator.Result again = VALIDATOR.check(first.checked().canonical().getBytes(StandardCharsets.UTF_8),
                structure());

        assertThat(again.problems()).isEmpty();
        assertThat(again.checked().canonical()).isEqualTo(first.checked().canonical());
        assertThat(again.checked().hash()).isEqualTo(first.checked().hash());
        assertThat(SheetGridWriter.hashOf(first.checked().canonical()))
                .isEqualTo(SheetGridWriter.hex(first.checked().hash()));
        assertThat(FillValidator.write(VALIDATOR.read(first.checked().canonical())))
                .isEqualTo(first.checked().canonical());
    }

    @Property(tries = 40)
    void oneChangedValueGivesAnotherHash(@ForAll @LongRange(min = 1, max = 1_000_000) long seed) {
        Map<String, Object> doc = fill(seed);
        @SuppressWarnings("unchecked") // the document is built by this class
        List<Map<String, Object>> rows = (List<Map<String, Object>>) doc.get("rows");
        rows.add(GridDocs.map("id", "extra", "cells", GridDocs.map("t", "A")));
        FillValidator.Result first = VALIDATOR.check(GridDocs.bytes(doc), structure());

        rows.remove(rows.size() - 1);
        rows.add(GridDocs.map("id", "extra", "cells", GridDocs.map("t", "B")));
        FillValidator.Result changed = VALIDATOR.check(GridDocs.bytes(doc), structure());

        assertThat(changed.problems()).isEmpty();
        assertThat(changed.checked().hash()).isNotEqualTo(first.checked().hash());
    }
}
