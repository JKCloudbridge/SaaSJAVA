package app.platform.data.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import app.platform.metadata.DefinitionKind;
import app.platform.metadata.FieldConfiguration;
import app.platform.metadata.FieldDefinition;
import app.platform.metadata.FieldType;
import app.platform.metadata.Metadata;
import app.platform.metadata.ObjectDefinition;
import app.platform.metadata.RecordType;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Record types on records, without a database (Sprint 15, ADR-0093): which type a write leaves a record with, which
 * fields a type offers, which picklist values it allows.
 */
class RecordTypeCheckTest {

    private static FieldDefinition field(String name) {
        return new FieldDefinition("Thing__c", name, name, "", DefinitionKind.CUSTOM, FieldType.TEXT, false, false,
                null, new FieldConfiguration.TextConfiguration(10), false, 0);
    }

    private static final ObjectDefinition THING = new ObjectDefinition("Thing__c", "Thing", "Things", "",
            DefinitionKind.CUSTOM, null, true, 0, List.of(field("a__c"), field("b__c"), field("c__c")));

    private static RecordType type(String name, boolean active, boolean isDefault, boolean all, List<String> fields,
            Map<String, List<String>> picklists) {
        return new RecordType("Thing__c", name, name, "", active, isDefault, "", all, fields, picklists, 0);
    }

    private static final RecordType STANDARD = type("Standard__c", true, true, true, List.of(), Map.of());
    private static final RecordType NARROW = type("Narrow__c", true, false, false, List.of("a__c"),
            Map.of("a__c", List.of("x", "y")));
    private static final RecordType OFF = type("Off__c", false, false, true, List.of(), Map.of());

    private static RecordTypeCheck check(RecordType... types) {
        Metadata metadata = mock(Metadata.class);
        when(metadata.recordTypes("Thing__c")).thenReturn(List.of(types));
        return new RecordTypeCheck(metadata);
    }

    @Test
    void aNewRecordGetsTheDefaultTypeWhenNoneIsNamed() {
        Findings findings = new Findings();

        Optional<RecordType> resolved = check(STANDARD, NARROW).resolve(THING, null, null, findings);

        assertThat(resolved).contains(STANDARD);
        assertThat(findings.isEmpty()).isTrue();
    }

    @Test
    void aNewRecordHasNoTypeWhenThereIsNoDefaultOrNoType() {
        assertThat(check(NARROW).resolve(THING, null, null, new Findings())).isEmpty();
        assertThat(check().resolve(THING, null, null, new Findings())).isEmpty();
    }

    @Test
    void aTypeThatIsNamedMustExistAndBeInUse() {
        Findings unknown = new Findings();
        Findings off = new Findings();
        Findings shape = new Findings();
        RecordTypeCheck check = check(STANDARD, OFF);

        assertThat(check.resolve(THING, "Nope__c", null, unknown)).isEmpty();
        check.resolve(THING, "Off__c", null, off);
        check.resolve(THING, "no way", null, shape);

        assertThat(unknown.at("recordType")).containsExactly("This object has no such record type.");
        assertThat(off.at("recordType")).containsExactly("This record type is not in use.");
        assertThat(shape.at("recordType")).containsExactly("Give the API name of a record type of the object.");
    }

    @Test
    void aRecordKeepsItsTypeEvenWhenTheTypeIsSwitchedOffButNoOtherRecordCanBeGivenIt() {
        RecordTypeCheck check = check(STANDARD, OFF);
        Findings kept = new Findings();
        Findings named = new Findings();

        assertThat(check.resolve(THING, null, "Off__c", kept)).contains(OFF);
        assertThat(check.resolve(THING, "Off__c", "Off__c", named)).contains(OFF);
        assertThat(kept.isEmpty() && named.isEmpty()).isTrue();
    }

    @Test
    void anUpdateWithoutATypeKeepsTheTypeAndDoesNotTakeTheDefault() {
        assertThat(check(STANDARD, NARROW).resolve(THING, null, "Narrow__c", new Findings())).contains(NARROW);
        assertThat(check(STANDARD).resolve(THING, null, "Gone__c", new Findings())).isEmpty();
    }

    @Test
    void aTypeOffersOnlyItsFieldsAndARecordWithoutATypeIsOfferedAll() {
        assertThat(RecordTypeCheck.offers(NARROW, THING.field("a__c").orElseThrow())).isTrue();
        assertThat(RecordTypeCheck.offers(NARROW, THING.field("b__c").orElseThrow())).isFalse();
        assertThat(RecordTypeCheck.offers(STANDARD, THING.field("b__c").orElseThrow())).isTrue();
        assertThat(RecordTypeCheck.offers(null, THING.field("b__c").orElseThrow())).isTrue();
    }

    @Test
    void aWriteMayNameOnlyTheFieldsTheTypeOffers() {
        Findings findings = new Findings();

        check(NARROW).checkOffered(NARROW, THING, List.of("a__c", "b__c", "c__c"), findings);

        assertThat(findings.places()).containsExactlyInAnyOrder("b__c", "c__c");
        Findings open = new Findings();
        check(STANDARD).checkOffered(STANDARD, THING, List.of("a__c", "b__c"), open);
        check(STANDARD).checkOffered(null, THING, List.of("a__c", "b__c"), open);
        assertThat(open.isEmpty()).isTrue();
    }

    @Test
    void aPicklistValueMustBeInTheTypesSubsetUnlessTheRecordHoldsItAlready() {
        Findings refused = new Findings();
        Findings ok = new Findings();
        Findings held = new Findings();
        Findings list = new Findings();

        RecordTypeCheck.checkPicklists(NARROW, Map.of("a__c", "z"), Map.of(), refused);
        RecordTypeCheck.checkPicklists(NARROW, Map.of("a__c", "x"), Map.of(), ok);
        RecordTypeCheck.checkPicklists(NARROW, Map.of("a__c", "z"), Map.of("a__c", "z"), held);
        RecordTypeCheck.checkPicklists(NARROW, Map.of("a__c", List.of("x", "z")), Map.of(), list);

        assertThat(refused.at("a__c")).containsExactly("This value is not allowed for the record type.");
        assertThat(ok.isEmpty()).isTrue();
        assertThat(held.isEmpty()).isTrue();
        assertThat(list.places()).containsExactly("a__c");
        RecordTypeCheck.checkPicklists(null, Map.of("a__c", "z"), Map.of(), ok);
        RecordTypeCheck.checkPicklists(STANDARD, Map.of("a__c", "z"), Map.of(), ok);
        assertThat(ok.isEmpty()).isTrue();
    }
}
