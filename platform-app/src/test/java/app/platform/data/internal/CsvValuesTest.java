package app.platform.data.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.platform.metadata.DefinitionKind;
import app.platform.metadata.FieldDefinition;
import app.platform.metadata.FieldType;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The reading of a cell of an import file (ADR-0123): text in, the value the write pipeline takes out. */
class CsvValuesTest {

    private static FieldDefinition field(FieldType type) {
        return new FieldDefinition("Deal__c", "value__c", "Value", "", DefinitionKind.CUSTOM, type, false, false,
                null, null, false, 0);
    }

    @Test
    void numbersAreReadAsDecimalsFromPlainDigits() {
        for (FieldType type : List.of(FieldType.NUMBER, FieldType.DECIMAL, FieldType.CURRENCY, FieldType.PERCENT)) {
            assertThat(CsvValues.convert(field(type), " 12.50 ", null)).isEqualTo(new BigDecimal("12.50"));
            assertThat(CsvValues.convert(field(type), "-3", null)).isEqualTo(new BigDecimal("-3"));
        }
    }

    @Test
    void aNumberWithAnExponentASeparatorOrWordsIsRefusedWithoutRepeatingIt() {
        for (String bad : List.of("1e5", "1,000", "twelve", "1.2.3", "--4", "0x10", "")) {
            assertThatThrownBy(() -> CsvValues.convert(field(FieldType.DECIMAL), bad.isEmpty() ? " " : bad, null))
                    .isInstanceOf(CsvValues.Problem.class).hasMessageNotContaining(bad.isEmpty() ? "zz" : bad);
        }
    }

    @Test
    void trueAndFalseAreReadInAnyCaseAndNothingElseIsABoolean() {
        assertThat(CsvValues.convert(field(FieldType.BOOLEAN), "TRUE", null)).isEqualTo(Boolean.TRUE);
        assertThat(CsvValues.convert(field(FieldType.BOOLEAN), " False ", null)).isEqualTo(Boolean.FALSE);
        assertThatThrownBy(() -> CsvValues.convert(field(FieldType.BOOLEAN), "yes", null))
                .isInstanceOf(CsvValues.Problem.class);
        assertThatThrownBy(() -> CsvValues.convert(field(FieldType.BOOLEAN), "1", null))
                .isInstanceOf(CsvValues.Problem.class);
    }

    @Test
    void aMultiSelectPicklistIsSplitOnSemicolonsAndEmptyPartsAreDropped() {
        assertThat(CsvValues.convert(field(FieldType.MULTI_PICKLIST), "a; b;;c ", null))
                .isEqualTo(List.of("a", "b", "c"));
    }

    @Test
    void textKeepsItsSpacesButOtherTypesAreTrimmed() {
        assertThat(CsvValues.convert(field(FieldType.TEXT), "  two  spaces ", null)).isEqualTo("  two  spaces ");
        assertThat(CsvValues.convert(field(FieldType.DATE), " 2030-01-31 ", null)).isEqualTo("2030-01-31");
        assertThat(CsvValues.convert(field(FieldType.EMAIL), " user-a@example.test ", null))
                .isEqualTo("user-a@example.test");
    }

    @Test
    void aLinkIsTheIdentifierAsWrittenOrWhateverTheLookupFinds() {
        String id = "01a1311b-3b3f-79d7-8c8e-effddcd1239e";

        assertThat(CsvValues.convert(field(FieldType.LOOKUP), id, null)).isEqualTo(id);
        assertThat(CsvValues.convert(field(FieldType.LOOKUP), "EXT-1", text -> id)).isEqualTo(id);
        assertThatThrownBy(() -> CsvValues.convert(field(FieldType.MASTER_DETAIL), "EXT-9", text -> {
            throw new CsvValues.Problem("No record has that external identifier.");
        })).isInstanceOf(CsvValues.Problem.class);
    }
}
