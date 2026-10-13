package app.platform.data.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.data.SlotCopy;
import app.platform.metadata.FieldType;
import app.platform.metadata.SlotClass;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** The slot columns of the record table (Sprint 16, ADR-0101, ADR-0102): the code and the table agree. */
class SlotColumnTest {

    @Test
    void theNumberOfSlotsOfEachKindIsTheNumberOfColumnsOfThatKind() {
        for (SlotClass kind : SlotClass.values()) {
            assertThat(SlotColumn.of(kind)).as(kind.name()).hasSize(kind.capacity());
        }
        assertThat(SlotColumn.values()).hasSize(22);
    }

    @Test
    void theColumnNamesAreDistinctAndHaveTheShapeOfASlot() {
        Set<String> names = Arrays.stream(SlotColumn.values()).map(SlotColumn::column).collect(Collectors.toSet());

        assertThat(names).hasSize(22).allMatch(name -> name.matches("s_(text|num|date|ts|ref|bool)_[1-8]"));
    }

    @Test
    void aColumnIsFoundByItsNameAndNothingElseIsAColumn() {
        assertThat(SlotColumn.byColumn("s_text_3")).contains(SlotColumn.TEXT_3);
        assertThat(SlotColumn.byColumn("payload")).isEmpty();
        assertThat(SlotColumn.byColumn("s_text_1; drop table x")).isEmpty();
    }

    @Test
    void everyTypeThatCanBeIndexedHasAKindWithFreeColumns() {
        for (FieldType type : FieldType.values()) {
            SlotClass.of(type).ifPresent(kind -> assertThat(SlotColumn.of(kind)).isNotEmpty());
        }
    }

    @Test
    void theConversionOfAValueIsTheSameForAWriteAndForTheFill() {
        SlotColumn number = SlotColumn.NUM_1;
        SlotColumn day = SlotColumn.DATE_1;

        assertThat(number.fromParameter("v")).isEqualTo("cast(cast(:v as numeric) as double precision)");
        assertThat(number.fromPayload("f")).contains("pg_input_is_valid").contains("as numeric");
        assertThat(day.fromParameter("v")).isEqualTo("cast(:v as date)");
        assertThat(day.fromPayload("f")).contains("'date'").contains("cast(:f as text)");
    }

    @Test
    void theTextOfAStoredValueIsWhatTheDatabaseConverts() {
        assertThat(RecordSlots.textOf("abc")).isEqualTo("abc");
        assertThat(RecordSlots.textOf(new BigDecimal("12.50"))).isEqualTo("12.50");
        assertThat(RecordSlots.textOf(7)).isEqualTo("7");
        assertThat(RecordSlots.textOf(Boolean.TRUE)).isEqualTo("true");
        assertThat(RecordSlots.textOf(null)).isNull();
        assertThat(RecordSlots.textOf(List.of("a"))).as("a list is no single value").isNull();
    }

    @Test
    void aCopyPrintsTheColumnsAndNeverTheValues() {
        SlotCopy copy = new SlotCopy(java.util.Map.of("s_text_1", "a secret value"));

        assertThat(copy.toString()).contains("s_text_1").doesNotContain("secret");
        assertThat(SlotCopy.none().columns()).isEmpty();
    }
}
