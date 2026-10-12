package app.platform.data.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.data.DataRecord;
import app.platform.metadata.DefinitionKind;
import app.platform.metadata.FieldConfiguration;
import app.platform.metadata.FieldConfiguration.FormulaConfiguration;
import app.platform.metadata.FieldDefinition;
import app.platform.metadata.FieldType;
import app.platform.metadata.ObjectDefinition;
import app.platform.security.ObjectAccess;
import app.platform.security.ObjectAction;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Field security on writes and reads, without a database (Sprint 15, ADR-0092): a field the member may not read does
 * not exist for them (the same answer as for a field that is not there, and absent from what they read); a field they
 * may read but not edit is refused with that reason; a field nobody can write is refused whoever asks.
 */
class FieldSecurityTest {

    private static FieldDefinition field(String name, FieldType type, DefinitionKind kind, boolean retired) {
        FieldConfiguration configuration = type == FieldType.FORMULA ? new FormulaConfiguration("1", FieldType.NUMBER)
                : new FieldConfiguration.NoConfiguration();
        return new FieldDefinition("Thing__c", name, name, "", kind, type, false, false, null, configuration,
                retired, 0);
    }

    private static final ObjectDefinition THING = new ObjectDefinition("Thing__c", "Thing", "Things", "",
            DefinitionKind.CUSTOM, null, true, 0, List.of(
                    field("id", FieldType.TEXT, DefinitionKind.SYSTEM, false),
                    field("name__c", FieldType.TEXT, DefinitionKind.CUSTOM, false),
                    field("secret__c", FieldType.TEXT, DefinitionKind.CUSTOM, false),
                    field("total__c", FieldType.FORMULA, DefinitionKind.CUSTOM, false),
                    field("old__c", FieldType.TEXT, DefinitionKind.CUSTOM, true)));

    private final FieldSecurity security = new FieldSecurity(null);

    private static ObjectAccess access(Set<String> readable, Set<String> editable) {
        return new ObjectAccess("Thing__c", Set.of(ObjectAction.READ, ObjectAction.CREATE, ObjectAction.UPDATE),
                readable, editable);
    }

    private Findings check(ObjectAccess access, String... keys) {
        Findings findings = new Findings();
        security.checkWrite(THING, access, List.of(keys), findings);
        return findings;
    }

    @Test
    void aFieldTheMemberMayEditPasses() {
        ObjectAccess access = access(Set.of("name__c"), Set.of("name__c"));
        Findings findings = new Findings();

        List<String> passed = security.checkWrite(THING, access, List.of("name__c"), findings);

        assertThat(passed).containsExactly("name__c");
        assertThat(findings.isEmpty()).isTrue();
    }

    @Test
    void aFieldTheMemberMayReadButNotEditIsRefusedWithThatReason() {
        Findings findings = check(access(Set.of("name__c"), Set.of()), "name__c");

        assertThat(findings.at("name__c")).containsExactly(FieldSecurity.NOT_EDITABLE);
    }

    @Test
    void aFieldTheMemberMayNotReadGetsTheAnswerOfAFieldThatIsNotThere() {
        ObjectAccess access = access(Set.of("name__c"), Set.of("name__c"));

        Findings hidden = check(access, "secret__c");
        Findings unknown = check(access, "nope__c");

        assertThat(hidden.at("secret__c")).containsExactly(FieldSecurity.NOT_A_FIELD);
        assertThat(unknown.at("nope__c")).isEqualTo(hidden.at("secret__c"));
    }

    @Test
    void aFieldNobodyCanWriteIsRefusedForAnyoneWhoCanSeeItAndHiddenForAnyoneWhoCannot() {
        ObjectAccess everything = access(Set.of("id", "total__c", "old__c"), Set.of("id", "total__c", "old__c"));
        ObjectAccess readOnly = access(Set.of("total__c"), Set.of());
        ObjectAccess blind = access(Set.of(), Set.of());

        Findings editor = check(everything, "id", "total__c", "old__c");
        Findings reader = check(readOnly, "total__c");
        Findings stranger = check(blind, "id", "total__c", "old__c");

        assertThat(editor.places()).containsExactlyInAnyOrder("id", "total__c", "old__c");
        assertThat(editor.at("total__c")).containsExactly(FieldSecurity.NOT_WRITABLE);
        assertThat(reader.at("total__c")).as("it cannot be edited: that is the reason they are told")
                .containsExactly(FieldSecurity.NOT_EDITABLE);
        assertThat(stranger.places()).containsExactlyInAnyOrder("id", "total__c", "old__c");
        assertThat(stranger.at("total__c")).containsExactly(FieldSecurity.NOT_A_FIELD);
        assertThat(stranger.at("old__c")).containsExactly(FieldSecurity.NOT_A_FIELD);
    }

    @Test
    void everyProblemIsFoundNotJustTheFirst() {
        Findings findings = check(access(Set.of("name__c"), Set.of()), "name__c", "secret__c", "nope__c");

        assertThat(findings.places()).containsExactlyInAnyOrder("name__c", "secret__c", "nope__c");
    }

    @Test
    void aProblemAboutAFieldTheMemberCannotReadIsReportedForTheRecordAsAWhole() {
        ObjectAccess access = access(Set.of("name__c"), Set.of());

        assertThat(FieldSecurity.place(access, "name__c")).isEqualTo("name__c");
        assertThat(FieldSecurity.place(access, "secret__c")).isEqualTo("fields");
        assertThat(FieldSecurity.place(access, "nope__c")).isEqualTo("fields");
    }

    @Test
    void aReadLeavesOutWhatTheMemberMayNotReadWithoutAPlaceholder() {
        DataRecord record = record(Map.of("name__c", "a", "secret__c", "b"));
        ObjectAccess access = access(Set.of("name__c"), Set.of());

        DataRecord seen = FieldSecurity.visible(record, access);

        assertThat(seen.fields()).containsOnlyKeys("name__c");
        assertThat(seen.id()).isEqualTo(record.id());
        assertThat(seen.version()).isEqualTo(record.version());
        assertThat(FieldSecurity.visible(record, access(Set.of(), Set.of())).fields()).isEmpty();
        assertThat(FieldSecurity.visible(List.of(record, record), access)).allSatisfy(one ->
                assertThat(one.fields()).containsOnlyKeys("name__c"));
        assertThat(record.fields()).as("the stored record is untouched").containsOnlyKeys("name__c", "secret__c");
    }

    private static DataRecord record(Map<String, Object> fields) {
        UUID user = UUID.randomUUID();
        Instant now = Instant.parse("2030-01-31T09:30:00Z");
        return new DataRecord(UUID.randomUUID(), "Thing__c", null, user, fields, 3, now, user, now, user, null, null);
    }
}
