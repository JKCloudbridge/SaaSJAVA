package app.platform.data.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import app.platform.metadata.DefinitionKind;
import app.platform.metadata.FieldConfiguration;
import app.platform.metadata.FieldDefinition;
import app.platform.metadata.FieldType;
import app.platform.metadata.Metadata;
import app.platform.metadata.ObjectDefinition;
import app.platform.metadata.RecordType;
import app.platform.security.MemberAccess;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Record types on records, without a database (Sprint 15, ADR-0093; Sprint 15A, ADR-0098): which type a write leaves a
 * record with, which fields a type offers, which picklist values it allows, and what the member's profile adds. Which
 * types a profile may use is the metadata module's answer ({@code MetadataSnapshotRecordTypesTest}); here the answer
 * is given and the stage is judged on what it does with it.
 */
class RecordTypeCheckTest {

    private static final UUID MEMBER = UUID.randomUUID();
    private static final UUID PROFILE = UUID.randomUUID();

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
    private static final RecordType SECRET = type("Secret__c", true, false, true, List.of(), Map.of());

    private final MemberAccess members = mock(MemberAccess.class);
    private final Metadata metadata = mock(Metadata.class);
    private final RecordTypeCheck check = new RecordTypeCheck(metadata, members);

    /** The object has these types; the member's profile may use only {@code usable}, and gets {@code byDefault}. */
    private void given(List<RecordType> all, List<RecordType> usable, RecordType byDefault) {
        when(members.profileOf(MEMBER)).thenReturn(Optional.of(PROFILE));
        when(metadata.recordTypes("Thing__c")).thenReturn(all);
        when(metadata.recordTypesFor("Thing__c", PROFILE)).thenReturn(usable);
        when(metadata.defaultRecordTypeFor("Thing__c", PROFILE)).thenReturn(Optional.ofNullable(byDefault));
    }

    private Optional<RecordType> resolve(String requested, String held, Findings findings) {
        return check.resolve(THING, MEMBER, requested, held, findings);
    }

    @Test
    void aNewRecordGetsTheDefaultTheMetadataGivesTheProfile() {
        given(List.of(STANDARD, NARROW), List.of(STANDARD, NARROW), NARROW);
        Findings findings = new Findings();

        Optional<RecordType> resolved = resolve(null, null, findings);

        assertThat(resolved).contains(NARROW);
        assertThat(findings.isEmpty()).isTrue();
    }

    @Test
    void aNewRecordHasNoTypeWhenThereIsNoDefaultForTheProfile() {
        given(List.of(NARROW, SECRET), List.of(NARROW), null);

        assertThat(resolve(null, null, new Findings())).isEmpty();
    }

    @Test
    void anObjectWithNoTypesNeverAsksForTheProfile() {
        when(metadata.recordTypes("Thing__c")).thenReturn(List.of());

        assertThat(resolve(null, null, new Findings())).isEmpty();
        verify(members, never()).profileOf(any());
    }

    @Test
    void aTypeThatIsNamedMustExistAndBeInUseAndBeWellWritten() {
        given(List.of(STANDARD, OFF), List.of(STANDARD, OFF), STANDARD);
        Findings unknown = new Findings();
        Findings off = new Findings();
        Findings shape = new Findings();

        assertThat(resolve("Nope__c", null, unknown)).isEmpty();
        resolve("Off__c", null, off);
        resolve("no way", null, shape);

        assertThat(unknown.at("recordType")).containsExactly("This object has no such record type.");
        assertThat(off.at("recordType")).containsExactly("This record type is not in use.");
        assertThat(shape.at("recordType")).containsExactly("Give the API name of a record type of the object.");
    }

    @Test
    void aTypeTheProfileMayNotUseAnswersAsATypeThatDoesNotExist() {
        given(List.of(STANDARD, SECRET), List.of(STANDARD), STANDARD);
        Findings hidden = new Findings();
        Findings unknown = new Findings();

        assertThat(resolve("Secret__c", null, hidden)).isEmpty();
        resolve("Nope__c", null, unknown);

        assertThat(hidden.at("recordType")).containsExactly("This object has no such record type.");
        assertThat(hidden.at("recordType")).as("the same words, so nobody learns what exists")
                .isEqualTo(unknown.at("recordType"));
    }

    @Test
    void aRecordKeepsATypeThatWasSwitchedOffOrThatTheProfileHasLost() {
        given(List.of(STANDARD, OFF, SECRET), List.of(STANDARD, OFF), STANDARD);
        Findings kept = new Findings();
        Findings named = new Findings();
        Findings lost = new Findings();

        assertThat(resolve(null, "Off__c", kept)).contains(OFF);
        assertThat(resolve("Off__c", "Off__c", named)).contains(OFF);
        assertThat(resolve("Secret__c", "Secret__c", lost)).as("naming the type it has changes nothing")
                .contains(SECRET);

        assertThat(kept.isEmpty() && named.isEmpty() && lost.isEmpty()).isTrue();
    }

    @Test
    void aRecordCannotBeMovedToATypeTheProfileMayNotUse() {
        given(List.of(STANDARD, SECRET), List.of(STANDARD), STANDARD);
        Findings findings = new Findings();

        resolve("Secret__c", "Standard__c", findings);

        assertThat(findings.at("recordType")).containsExactly("This object has no such record type.");
    }

    @Test
    void anUpdateWithoutATypeKeepsTheTypeAndDoesNotTakeTheDefault() {
        given(List.of(STANDARD, NARROW), List.of(STANDARD, NARROW), STANDARD);

        assertThat(resolve(null, "Narrow__c", new Findings())).contains(NARROW);
        assertThat(resolve(null, "Gone__c", new Findings())).isEmpty();
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

        check.checkOffered(NARROW, THING, List.of("a__c", "b__c", "c__c"), findings);

        assertThat(findings.places()).containsExactlyInAnyOrder("b__c", "c__c");
        Findings open = new Findings();
        check.checkOffered(STANDARD, THING, List.of("a__c", "b__c"), open);
        check.checkOffered(null, THING, List.of("a__c", "b__c"), open);
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
