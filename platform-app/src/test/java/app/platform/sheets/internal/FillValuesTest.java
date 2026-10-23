package app.platform.sheets.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.sheets.internal.FillValues.Kind;
import app.platform.sheets.internal.FillValues.Value;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * The reportable values of a fill-in (FF2, ADR-0193), with no database: which cells are copied for a report, and in
 * which typed form. The copy is derived from the pinned sheet and the cells alone, so it can be tested here.
 */
class FillValuesTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static SheetGrid grid(String... reportable) {
        Map<String, Object> doc = GridDocs.minimal();
        List<Map<String, Object>> columns = GridDocs.list(doc, "columns");
        columns.add(GridDocs.column("c3", "Checked on", "date"));
        columns.add(GridDocs.column("c4", "Result", "picklist"));
        columns.add(GridDocs.column("c5", "Done", "boolean"));
        for (Map<String, Object> column : columns) {
            if (List.of(reportable).contains(column.get("id"))) {
                column.put("reportable", true);
            }
        }
        return SheetGridReader.read(JSON.readTree(GridDocs.json(doc)));
    }

    @SafeVarargs
    private static FillDoc cells(Map<String, Object>... rows) {
        return new FillDoc(java.util.stream.IntStream.range(0, rows.length)
                .mapToObj(i -> new FillDoc.Row("r" + i, rows[i])).toList());
    }

    private static Map<String, Object> row(Object... pairs) {
        return GridDocs.map(pairs);
    }

    @Test
    void aVersionThatFlagsNoColumnCopiesNothing() {
        assertThat(FillValues.reportableColumns(grid())).isEmpty();
        assertThat(FillValues.of(grid(), cells(row("c1", "Door open", "c2", "12.5")))).isEmpty();
    }

    @Test
    void eachFlaggedColumnIsCopiedInTheTypeOfItsColumn() {
        SheetGrid sheet = grid("c1", "c2", "c3", "c4", "c5");

        List<Value> values = FillValues.of(sheet, cells(row("c1", "Door open", "c2", "-12.50", "c3", "2026-10-23",
                "c4", "Fail", "c5", false)));

        assertThat(values).hasSize(5);
        assertThat(values).extracting(Value::kind).containsExactly(Kind.TEXT, Kind.NUMBER, Kind.DATE, Kind.TEXT,
                Kind.BOOLEAN);
        assertThat(values.get(0).text()).isEqualTo("Door open");
        assertThat(values.get(1).number()).isEqualByComparingTo(new BigDecimal("-12.5"));
        assertThat(values.get(2).date()).isEqualTo(LocalDate.of(2026, 10, 23));
        assertThat(values.get(3).text()).as("a listed value is compared as text").isEqualTo("Fail");
        assertThat(values.get(4).flag()).as("a no is a value, not an empty cell").isFalse();
    }

    @Test
    void anUnflaggedColumnAndAnEmptyCellAreLeftOut() {
        SheetGrid sheet = grid("c2");

        List<Value> values = FillValues.of(sheet, cells(row("c1", "Not copied", "c2", "7"), row("c1", "Nothing here")));

        assertThat(values).hasSize(1);
        assertThat(values.get(0).rowId()).isEqualTo("r0");
        assertThat(values.get(0).columnId()).isEqualTo("c2");
    }

    @Test
    void everyRowOfTheFillInIsCopiedWithItsOwnRowIdentifier() {
        SheetGrid sheet = grid("c1");

        List<Value> values = FillValues.of(sheet, cells(row("c1", "a"), row("c1", "b"), row("c1", "c")));

        assertThat(values).extracting(Value::rowId).containsExactly("r0", "r1", "r2");
    }

    @Test
    void aValueThatDoesNotFitItsColumnIsLeftOutAndNeverGuessed() {
        SheetGrid sheet = grid("c2", "c3");

        List<Value> values = FillValues.of(sheet, cells(row("c2", "twelve", "c3", "2026-13-45")));

        assertThat(values).isEmpty();
    }

    @Test
    void nothingPrintsAValue() {
        SheetGrid sheet = grid("c1");

        Value value = FillValues.of(sheet, cells(row("c1", "SECRETMARK"))).get(0);

        assertThat(value.toString()).doesNotContain("SECRETMARK");
    }
}
