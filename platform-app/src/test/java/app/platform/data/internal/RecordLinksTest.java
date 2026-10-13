package app.platform.data.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.metadata.DefinitionKind;
import app.platform.metadata.DeleteBehaviour;
import app.platform.metadata.FieldConfiguration.ReferenceConfiguration;
import app.platform.metadata.FieldConfiguration.TextConfiguration;
import app.platform.metadata.FieldDefinition;
import app.platform.metadata.FieldType;
import app.platform.metadata.ObjectDefinition;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** What a link field does when the record it points at is removed, and what a junction is (Sprint 16, ADR-0106). */
class RecordLinksTest {

    private static FieldDefinition link(String name, FieldType type, boolean required, DeleteBehaviour behaviour) {
        return new FieldDefinition("Thing__c", name, name, "", DefinitionKind.CUSTOM, type, required, false, null,
                new ReferenceConfiguration("Parent__c", behaviour, false, ""), false, 0);
    }

    private static ObjectDefinition object(FieldDefinition... fields) {
        List<FieldDefinition> all = new ArrayList<>();
        all.add(new FieldDefinition("Thing__c", "id", "id", "", DefinitionKind.SYSTEM, FieldType.TEXT, true, true,
                null, new TextConfiguration(36), false, 0));
        all.addAll(List.of(fields));
        return new ObjectDefinition("Thing__c", "Thing", "Things", "", DefinitionKind.CUSTOM, null, true, 0, all);
    }

    @Test
    void aMasterDetailAlwaysCascades() {
        assertThat(RecordLinks.behaviourOf(link("m__c", FieldType.MASTER_DETAIL, true, DeleteBehaviour.CLEAR)))
                .isEqualTo(DeleteBehaviour.CASCADE);
    }

    @Test
    void aLookupDoesWhatItsSettingSaysUnlessItIsRequired() {
        assertThat(RecordLinks.behaviourOf(link("a__c", FieldType.LOOKUP, false, DeleteBehaviour.CLEAR)))
                .isEqualTo(DeleteBehaviour.CLEAR);
        assertThat(RecordLinks.behaviourOf(link("b__c", FieldType.LOOKUP, false, DeleteBehaviour.REFUSE)))
                .isEqualTo(DeleteBehaviour.REFUSE);
        assertThat(RecordLinks.behaviourOf(link("c__c", FieldType.LOOKUP, true, DeleteBehaviour.CLEAR)))
                .as("a record without its required value would be invalid").isEqualTo(DeleteBehaviour.REFUSE);
    }

    @Test
    void aJunctionIsAnObjectWithExactlyTwoMasterDetailFields() {
        FieldDefinition one = link("a__c", FieldType.MASTER_DETAIL, true, DeleteBehaviour.CASCADE);
        FieldDefinition two = link("b__c", FieldType.MASTER_DETAIL, true, DeleteBehaviour.CASCADE);
        FieldDefinition three = link("c__c", FieldType.MASTER_DETAIL, true, DeleteBehaviour.CASCADE);
        FieldDefinition lookup = link("d__c", FieldType.LOOKUP, false, DeleteBehaviour.CLEAR);

        assertThat(RecordLinks.isJunction(object(one, two))).isTrue();
        assertThat(RecordLinks.isJunction(object(one, two, lookup))).isTrue();
        assertThat(RecordLinks.isJunction(object(one))).isFalse();
        assertThat(RecordLinks.isJunction(object(one, two, three))).isFalse();
        assertThat(RecordLinks.isJunction(object(lookup))).isFalse();
        assertThat(RecordLinks.masterDetails(object(two, one)).stream().map(FieldDefinition::apiName))
                .containsExactly("a__c", "b__c");
    }

    @Test
    void theLinkFieldsOfAnObjectAreItsLookupsAndMasterDetails() {
        FieldDefinition one = link("a__c", FieldType.MASTER_DETAIL, true, DeleteBehaviour.CASCADE);
        FieldDefinition lookup = link("d__c", FieldType.LOOKUP, false, DeleteBehaviour.CLEAR);

        assertThat(RecordLinks.referenceFields(object(one, lookup))).containsExactly(one, lookup);
    }

    @Test
    void anIdentifierIsReadOnlyFromTheTextOfOne() {
        UUID id = UUID.fromString("01a1311b-3b3f-79d7-8c8e-effddcd1239e");

        assertThat(RecordLinks.uuidOf(id.toString())).isEqualTo(id);
        assertThat(RecordLinks.uuidOf("nope")).isNull();
        assertThat(RecordLinks.uuidOf(null)).isNull();
        assertThat(RecordLinks.uuidOf(5)).isNull();
        assertThat(RecordLinks.uuidOf("x".repeat(36))).isNull();
    }
}
