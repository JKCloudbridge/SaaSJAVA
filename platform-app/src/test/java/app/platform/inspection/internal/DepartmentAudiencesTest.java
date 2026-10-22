package app.platform.inspection.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import app.platform.data.BackgroundRecords;
import app.platform.data.DataRecord;
import app.platform.inspection.internal.DepartmentAudiences.Resolved;
import app.platform.inspection.internal.TemplateViews.AudienceOptionsView;
import app.platform.inspection.internal.TemplateViews.AudiencePick;
import app.platform.metadata.DefinitionKind;
import app.platform.metadata.FieldConfiguration.Dependency;
import app.platform.metadata.FieldConfiguration.PicklistConfiguration;
import app.platform.metadata.FieldDefinition;
import app.platform.metadata.FieldType;
import app.platform.metadata.Metadata;
import app.platform.metadata.ObjectDefinition;
import app.platform.metadata.PicklistValue;
import app.platform.security.GroupMembers;
import app.platformapi.ApiException;
import app.platformapi.ErrorCode;
import app.platformapi.QueryFilterNode;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * "Location, then Department" becomes the group of people it means (FA4, ADR-0190), with no database: none, one or
 * several mapping records for a pair, a group that is gone, a group with nobody in it, the same pair twice, all or
 * nothing for several pairs, and what the publish dialog is offered (the departments that go with each location, which
 * have a group). The words are fixed and name neither the location, the department nor the group.
 */
class DepartmentAudiencesTest {

    private static final UUID PLANT_GROUP = UUID.randomUUID();
    private static final UUID HEAD_GROUP = UUID.randomUUID();

    private BackgroundRecords records;
    private GroupMembers groups;
    private Metadata metadata;
    private DepartmentAudiences audiences;

    @BeforeEach
    void setUp() {
        records = mock(BackgroundRecords.class);
        groups = mock(GroupMembers.class);
        metadata = mock(Metadata.class);
        audiences = new DepartmentAudiences(records, groups, metadata);
        when(groups.exists(any())).thenReturn(true);
        when(groups.membersOf(any())).thenReturn(Set.of(UUID.randomUUID()));
    }

    private static DataRecord mapping(String location, String department, Object group) {
        return new DataRecord(UUID.randomUUID(), "DepartmentGroup", null, UUID.randomUUID(),
                group == null ? Map.of("location", location, "department", department)
                        : Map.of("location", location, "department", department, "groupId", group.toString()), 0,
                Instant.EPOCH, UUID.randomUUID(), Instant.EPOCH, UUID.randomUUID(), null, null);
    }

    /** The mapping records the scan finds for a pair (whatever the filter, by its values). */
    private void mappings(String location, String department, DataRecord... found) {
        when(records.scan(eq("DepartmentGroup"), any(QueryFilterNode.class), any(), anyInt())).thenAnswer(call -> {
            QueryFilterNode filter = call.getArgument(1);
            String wantedLocation = String.valueOf(filter.items().get(0).values().get(0));
            String wantedDepartment = String.valueOf(filter.items().get(1).values().get(0));
            return wantedLocation.equals(location) && wantedDepartment.equals(department) ? List.of(found)
                    : List.of();
        });
    }

    private static AudiencePick pick(String location, String department) {
        return new AudiencePick(location, department);
    }

    private static ApiException refusalOf(Runnable work) {
        try {
            work.run();
        } catch (ApiException refused) {
            return refused;
        }
        throw new AssertionError("The pair was not refused");
    }

    // ---- resolving a pair ----

    @Test
    void aPairWithOneRecordResolvesToItsGroup() {
        mappings("PLANT1", "PRD", mapping("PLANT1", "PRD", PLANT_GROUP));

        List<Resolved> resolved = audiences.resolve(List.of(pick("PLANT1", "PRD")));

        assertThat(resolved).hasSize(1);
        assertThat(resolved.get(0).groupId()).isEqualTo(PLANT_GROUP);
        assertThat(resolved.get(0).pick()).isEqualTo(pick("PLANT1", "PRD"));
    }

    @Test
    void aPairWithNoRecordIsRefusedWithTheWordsAndTheWholeRequestWithIt() {
        mappings("PLANT1", "PRD", mapping("PLANT1", "PRD", PLANT_GROUP));

        ApiException refused = refusalOf(() -> audiences.resolve(List.of(pick("PLANT1", "PRD"), pick("HEAD", "HR"))));

        assertThat(refused.code()).isEqualTo(ErrorCode.VALIDATION_ERROR);
        assertThat(refused.fields()).containsOnlyKeys("audiences[1]");
        assertThat(refused.fields().get("audiences[1]")).containsExactly("No group is set up for this department yet.");
        assertThat(refused.fields().toString()).doesNotContain("HEAD").doesNotContain("HR");
    }

    @Test
    void severalRecordsForOnePairAreRefusedInsteadOfChoosing() {
        mappings("PLANT1", "PRD", mapping("PLANT1", "PRD", PLANT_GROUP), mapping("PLANT1", "PRD", HEAD_GROUP));

        ApiException refused = refusalOf(() -> audiences.resolve(List.of(pick("PLANT1", "PRD"))));

        assertThat(refused.fields().get("audiences[0]")).containsExactly(DepartmentAudiences.MANY_GROUPS);
    }

    @Test
    void aRecordWithoutAGroupOrWithAGroupThatIsNotAnIdentifierIsTheSameAsNoGroup() {
        mappings("PLANT1", "PRD", mapping("PLANT1", "PRD", null));
        assertThat(refusalOf(() -> audiences.resolve(List.of(pick("PLANT1", "PRD")))).fields().get("audiences[0]"))
                .containsExactly(DepartmentAudiences.NO_GROUP);

        mappings("PLANT1", "PRD", mapping("PLANT1", "PRD", "not-a-uuid"));
        assertThat(refusalOf(() -> audiences.resolve(List.of(pick("PLANT1", "PRD")))).fields().get("audiences[0]"))
                .containsExactly(DepartmentAudiences.NO_GROUP);
    }

    @Test
    void aGroupThatWasRemovedAndAGroupWithNobodyInItAreRefusedInTheirOwnWords() {
        mappings("PLANT1", "PRD", mapping("PLANT1", "PRD", PLANT_GROUP));
        when(groups.exists(PLANT_GROUP)).thenReturn(false);
        assertThat(refusalOf(() -> audiences.resolve(List.of(pick("PLANT1", "PRD")))).fields().get("audiences[0]"))
                .containsExactly(DepartmentAudiences.GROUP_GONE);

        when(groups.exists(PLANT_GROUP)).thenReturn(true);
        when(groups.membersOf(PLANT_GROUP)).thenReturn(Set.of());
        assertThat(refusalOf(() -> audiences.resolve(List.of(pick("PLANT1", "PRD")))).fields().get("audiences[0]"))
                .containsExactly(DepartmentAudiences.GROUP_EMPTY);
    }

    @Test
    void theSamePairTwiceIsRefusedAndNothingIsResolvedFurther() {
        mappings("PLANT1", "PRD", mapping("PLANT1", "PRD", PLANT_GROUP));

        ApiException refused = refusalOf(() -> audiences.resolve(List.of(pick("PLANT1", "PRD"),
                pick("PLANT1", "PRD"))));

        assertThat(refused.fields().get("audiences[1]")).containsExactly("This department is listed twice.");
    }

    @Test
    void theWordsOfARefusalNeverNameAnythingTheOrganizationTyped() {
        for (String words : List.of(DepartmentAudiences.NO_GROUP, DepartmentAudiences.MANY_GROUPS,
                DepartmentAudiences.GROUP_GONE, DepartmentAudiences.GROUP_EMPTY)) {
            assertThat(words).doesNotContain("PLANT").doesNotContain("PRD").doesNotContain("%").doesNotContain("{");
            assertThat(words.length()).isLessThanOrEqualTo(120);
        }
    }

    // ---- what the dialog is offered ----

    private static FieldDefinition picklist(String name, List<PicklistValue> values, Dependency dependency) {
        return new FieldDefinition("DepartmentGroup", name, name, "", DefinitionKind.STANDARD, FieldType.PICKLIST, true,
                false, null, new PicklistConfiguration(values, dependency), false, 0);
    }

    private void objectWithLists() {
        FieldDefinition location = picklist("location", List.of(new PicklistValue("PLANT1", "Plant 1", true),
                new PicklistValue("OLD", "Old place", false), new PicklistValue("HEAD", "Head office", true)), null);
        FieldDefinition department = picklist("department", List.of(new PicklistValue("PRD", "Production", true),
                new PicklistValue("QLT", "Quality", true), new PicklistValue("HR", "People", true),
                new PicklistValue("GONE", "Gone", false)), new Dependency("location",
                        Map.of("PLANT1", List.of("PRD", "QLT", "GONE"), "HEAD", List.of("HR"))));
        when(metadata.object("DepartmentGroup")).thenReturn(Optional.of(new ObjectDefinition("DepartmentGroup", "D",
                "Ds", "", DefinitionKind.STANDARD, null, false, 0, List.of(location, department))));
    }

    @Test
    void theDialogIsOfferedTheLocationsWithTheDepartmentsThatGoWithEachAndWhichHaveAGroup() {
        objectWithLists();
        when(records.scan(eq("DepartmentGroup"), any(), any(), anyInt())).thenReturn(
                List.of(mapping("PLANT1", "PRD", PLANT_GROUP), mapping("HEAD", "HR", HEAD_GROUP)));

        AudienceOptionsView options = audiences.options();

        assertThat(options.locations()).extracting(TemplateViews.AudienceLocation::value)
                .containsExactly("PLANT1", "HEAD");
        TemplateViews.AudienceLocation plant = options.locations().get(0);
        assertThat(plant.label()).isEqualTo("Plant 1");
        assertThat(plant.departments()).extracting(TemplateViews.AudienceDepartment::value)
                .containsExactly("PRD", "QLT");
        assertThat(plant.departments()).extracting(TemplateViews.AudienceDepartment::configured)
                .containsExactly(true, false);
        assertThat(options.locations().get(1).departments()).extracting(TemplateViews.AudienceDepartment::label)
                .containsExactly("People");
    }

    @Test
    void theLabelsOfTheOrganizationsListsAreUsedAndACodeItNoLongerHasIsReadAsItself() {
        objectWithLists();

        assertThat(audiences.labels("location")).containsEntry("PLANT1", "Plant 1").containsEntry("OLD", "Old place");
        assertThat(audiences.labels("nothing")).isEmpty();
    }

    @Test
    void aDialogForAnOrganizationWithoutTheObjectIsNotFound() {
        when(metadata.object("DepartmentGroup")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> audiences.options()).isInstanceOfSatisfying(ApiException.class,
                refused -> assertThat(refused.code()).isEqualTo(ErrorCode.NOT_FOUND));
    }
}
