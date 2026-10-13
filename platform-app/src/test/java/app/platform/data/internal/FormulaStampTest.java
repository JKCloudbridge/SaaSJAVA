package app.platform.data.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.metadata.DefinitionKind;
import app.platform.metadata.FieldConfiguration.FormulaConfiguration;
import app.platform.metadata.FieldConfiguration.TextConfiguration;
import app.platform.metadata.FieldDefinition;
import app.platform.metadata.FieldType;
import app.platform.metadata.ObjectDefinition;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The fingerprint of an object's formulas (Sprint 16A, ADR-0113, ADR-0114): it changes when a formula is edited, added
 * or removed, and only then; it is the same for the same formulas on every instance; it never holds the text.
 */
class FormulaStampTest {

    private static FieldDefinition formula(String name, String expression, FieldType result, boolean retired) {
        return new FieldDefinition("Thing__c", name, name, "label of " + name, DefinitionKind.CUSTOM,
                FieldType.FORMULA, false, false, null, new FormulaConfiguration(expression, result, true), retired,
                0);
    }

    private static FieldDefinition text(String name, String label) {
        return new FieldDefinition("Thing__c", name, label, "", DefinitionKind.CUSTOM, FieldType.TEXT, false, false,
                null, new TextConfiguration(255), false, 0);
    }

    private static ObjectDefinition object(FieldDefinition... fields) {
        return new ObjectDefinition("Thing__c", "Thing", "Things", "", DefinitionKind.CUSTOM, null, true, 0,
                new ArrayList<>(List.of(fields)));
    }

    @Test
    void anObjectWithoutFormulasHasNoStamp() {
        assertThat(FormulaStamp.of(object(text("a__c", "a")))).isEqualTo(FormulaStamp.NONE).isEmpty();
    }

    @Test
    void editingAFormulaChangesTheStamp() {
        String before = FormulaStamp.of(object(formula("f__c", "a__c * 2", FieldType.NUMBER, false)));
        String after = FormulaStamp.of(object(formula("f__c", "a__c * 3", FieldType.NUMBER, false)));

        assertThat(before).isNotEmpty().isNotEqualTo(after);
    }

    @Test
    void addingOrRemovingAFormulaChangesTheStampAndTheResultTypeIsPartOfIt() {
        FieldDefinition one = formula("f__c", "1", FieldType.NUMBER, false);
        FieldDefinition two = formula("g__c", "2", FieldType.NUMBER, false);

        assertThat(FormulaStamp.of(object(one))).isNotEqualTo(FormulaStamp.of(object(one, two)));
        assertThat(FormulaStamp.of(object(one)))
                .isNotEqualTo(FormulaStamp.of(object(formula("f__c", "1", FieldType.DECIMAL, false))));
    }

    @Test
    void aLabelAnotherFieldOrTheOrderOfTheFieldsDoesNotChangeIt() {
        FieldDefinition one = formula("f__c", "1", FieldType.NUMBER, false);
        FieldDefinition two = formula("g__c", "2", FieldType.NUMBER, false);
        String stamp = FormulaStamp.of(object(one, two));

        assertThat(FormulaStamp.of(object(two, one))).isEqualTo(stamp);
        assertThat(FormulaStamp.of(object(one, text("a__c", "A"), two))).isEqualTo(stamp);
        assertThat(FormulaStamp.of(object(one, text("a__c", "Another label"), two))).isEqualTo(stamp);
        assertThat(FormulaStamp.of(object(formula("f__c", "1", FieldType.NUMBER, false), two))).isEqualTo(stamp);
    }

    @Test
    void aRetiredFormulaDoesNotCount() {
        FieldDefinition retired = formula("f__c", "1", FieldType.NUMBER, true);

        assertThat(FormulaStamp.of(object(retired))).isEmpty();
    }

    @Test
    void theStampIsAShortHashThatDoesNotHoldTheText() {
        String stamp = FormulaStamp.of(object(formula("f__c", "SECRET-EXPRESSION", FieldType.TEXT, false)));

        assertThat(stamp).hasSize(32).matches("[0-9a-f]+").doesNotContain("SECRET");
    }
}
