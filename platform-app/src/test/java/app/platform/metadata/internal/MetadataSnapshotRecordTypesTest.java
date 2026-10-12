package app.platform.metadata.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.metadata.DefinitionKind;
import app.platform.metadata.ObjectDefinition;
import app.platform.metadata.RecordType;
import app.platform.metadata.RecordTypeAssignment;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Which record types a profile may use and which one it gets by default (Sprint 15A, ADR-0098), without a database: a
 * type with no assignment is open to every profile, a type with at least one is open only to those named, the profile's
 * own default comes first, and the dependency graph keeps a type that has assignments from being removed.
 */
class MetadataSnapshotRecordTypesTest {

    private static final String OBJECT = "Deal__c";
    private static final UUID SALES = UUID.randomUUID();
    private static final UUID SUPPORT = UUID.randomUUID();
    private static final UUID STRANGER = UUID.randomUUID();

    private static RecordType type(String name, boolean active, boolean isDefault) {
        return new RecordType(OBJECT, name, name, "", active, isDefault, "", true, List.of(), Map.of(), 0);
    }

    private static final RecordType NEW_BUSINESS = type("NewBusiness__c", true, true);
    private static final RecordType RENEWAL = type("Renewal__c", true, false);
    private static final RecordType PARTNER = type("Partner__c", true, false);
    private static final RecordType CLOSED = type("Closed__c", false, false);

    private static RecordTypeAssignment assign(RecordType type, UUID profile, boolean isDefault) {
        return new RecordTypeAssignment(OBJECT, type.apiName(), profile, isDefault, 0);
    }

    private static MetadataSnapshot snapshot(List<RecordType> types, List<RecordTypeAssignment> assignments) {
        ObjectDefinition deal = new ObjectDefinition(OBJECT, "Deal", "Deals", "", DefinitionKind.CUSTOM, null, true, 0,
                List.of());
        Map<String, List<RecordTypeAssignment>> grouped = new LinkedHashMap<>();
        assignments.forEach(one -> grouped.computeIfAbsent(one.objectApiName(), name -> new ArrayList<>()).add(one));
        return new MetadataSnapshot(List.of(deal), Map.of(OBJECT, types), new MetadataSnapshot.Definitions(Map.of(),
                Map.of(), Map.of(), List.of(), Map.of(), grouped));
    }

    private static List<String> names(List<RecordType> types) {
        return types.stream().map(RecordType::apiName).toList();
    }

    @Test
    void aTypeWithNoAssignmentIsOpenToEveryProfileAndToPeopleWithNone() {
        MetadataSnapshot snapshot = snapshot(List.of(NEW_BUSINESS, RENEWAL), List.of());

        assertThat(names(snapshot.recordTypesFor(OBJECT, SALES))).containsExactly("NewBusiness__c", "Renewal__c");
        assertThat(names(snapshot.recordTypesFor(OBJECT, null))).containsExactly("NewBusiness__c", "Renewal__c");
    }

    @Test
    void aTypeWithAnAssignmentIsOpenOnlyToTheProfilesNamed() {
        MetadataSnapshot snapshot = snapshot(List.of(NEW_BUSINESS, RENEWAL, PARTNER),
                List.of(assign(RENEWAL, SALES, false), assign(PARTNER, SUPPORT, false), assign(PARTNER, SALES, false)));

        assertThat(names(snapshot.recordTypesFor(OBJECT, SALES))).containsExactly("NewBusiness__c", "Renewal__c",
                "Partner__c");
        assertThat(names(snapshot.recordTypesFor(OBJECT, SUPPORT))).containsExactly("NewBusiness__c", "Partner__c");
        assertThat(names(snapshot.recordTypesFor(OBJECT, STRANGER))).as("an unassigned type is open, the rest are not")
                .containsExactly("NewBusiness__c");
        assertThat(names(snapshot.recordTypesFor(OBJECT, null))).containsExactly("NewBusiness__c");
    }

    @Test
    void anAssignmentOfAProfileThatNoLongerExistsStillRestrictsTheType() {
        MetadataSnapshot snapshot = snapshot(List.of(RENEWAL), List.of(assign(RENEWAL, UUID.randomUUID(), false)));

        assertThat(snapshot.recordTypesFor(OBJECT, SALES)).as("fails closed: nobody holds the deleted profile")
                .isEmpty();
    }

    @Test
    void theProfilesOwnDefaultComesFirstThenTheObjectsDefaultIfTheProfileMayUseIt() {
        MetadataSnapshot snapshot = snapshot(List.of(NEW_BUSINESS, RENEWAL, PARTNER),
                List.of(assign(RENEWAL, SALES, true), assign(PARTNER, SUPPORT, false)));

        assertThat(snapshot.defaultRecordTypeFor(OBJECT, SALES)).contains(RENEWAL);
        assertThat(snapshot.defaultRecordTypeFor(OBJECT, SUPPORT)).as("the object's default, which is open")
                .contains(NEW_BUSINESS);
        assertThat(snapshot.defaultRecordTypeFor(OBJECT, null)).contains(NEW_BUSINESS);
    }

    @Test
    void theObjectsDefaultIsNotGivenToAProfileThatMayNotUseIt() {
        MetadataSnapshot snapshot = snapshot(List.of(NEW_BUSINESS, RENEWAL),
                List.of(assign(NEW_BUSINESS, SALES, false), assign(RENEWAL, SUPPORT, false)));

        assertThat(snapshot.defaultRecordTypeFor(OBJECT, SUPPORT)).as("the default type is closed to this profile")
                .isEmpty();
        assertThat(snapshot.defaultRecordTypeFor(OBJECT, SALES)).contains(NEW_BUSINESS);
    }

    @Test
    void aDefaultThatIsNotInUseIsNotGiven() {
        MetadataSnapshot snapshot = snapshot(List.of(CLOSED, RENEWAL),
                List.of(assign(CLOSED, SALES, true), assign(RENEWAL, SALES, false)));

        assertThat(snapshot.defaultRecordTypeFor(OBJECT, SALES)).isEmpty();
    }

    @Test
    void aRecordTypeThatStillHasAssignmentsCannotBeRemovedAndTheRefusalNamesThem() {
        RecordTypeAssignment assignment = assign(RENEWAL, SALES, false);
        MetadataSnapshot after = snapshot(List.of(), List.of(assignment));
        List<DependencyGraph.Contributor> contributors = List.of(new DefinitionDependencies.TypeAssignments());

        List<DependencyGraph.Violation> violations = DependencyGraph.of(after, contributors).violations();

        assertThat(violations).hasSize(1);
        assertThat(violations.get(0).message()).contains("the record type assignment Renewal__c~" + SALES)
                .contains("record type Renewal__c").doesNotContain("Closed");
    }

    @Test
    void anObjectThatStillHasAssignmentsCannotBeRemovedEither() {
        RecordTypeAssignment assignment = assign(RENEWAL, SALES, false);
        MetadataSnapshot after = new MetadataSnapshot(List.of(), Map.of(), new MetadataSnapshot.Definitions(
                Map.of(), Map.of(), Map.of(), List.of(), Map.of(), Map.of(OBJECT, List.of(assignment))));
        List<DependencyGraph.Contributor> contributors = List.of(new DefinitionDependencies.TypeAssignments());

        List<DependencyGraph.Violation> violations = DependencyGraph.of(after, contributors).violations();

        assertThat(violations).isNotEmpty();
        assertThat(violations.get(0).message()).contains("object " + OBJECT);
    }

    @Test
    void theKeyOfAnAssignmentIsTheRecordTypeATildeAndTheProfile() {
        assertThat(assign(RENEWAL, SALES, false).key()).isEqualTo("Renewal__c~" + SALES);
        assertThat(RecordTypeAssignmentService.parse("Renewal__c~" + SALES))
                .isEqualTo(new RecordTypeAssignmentService.Key("Renewal__c", SALES));
    }
}
