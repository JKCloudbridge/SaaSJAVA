package app.platform.metadata.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.metadata.DefinitionKind;
import app.platform.metadata.FieldConfiguration;
import app.platform.metadata.FieldConfiguration.NoConfiguration;
import app.platform.metadata.FieldConfiguration.PicklistConfiguration;
import app.platform.metadata.FieldConfiguration.ReferenceConfiguration;
import app.platform.metadata.FieldConfiguration.TextConfiguration;
import app.platform.metadata.FieldDefinition;
import app.platform.metadata.FieldType;
import app.platform.metadata.ObjectDefinition;
import app.platform.metadata.PicklistValue;
import app.platform.metadata.RecordType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** What depends on what, and the precise words when something that is needed is gone (ADR-0065). */
class DependencyGraphTest {

    private static final List<DependencyGraph.Contributor> CONTRIBUTORS = List.of(new Dependencies.References(),
            new Dependencies.RecordTypes());

    private static FieldDefinition field(String object, String name, FieldType type, boolean required,
            FieldConfiguration configuration) {
        return new FieldDefinition(object, name, name, "", DefinitionKind.CUSTOM, type, required, false, null,
                configuration, false, 0);
    }

    private static FieldDefinition system(String object) {
        return new FieldDefinition(object, "id", "id", "", DefinitionKind.SYSTEM, FieldType.TEXT, true, true, null,
                new TextConfiguration(36), false, 0);
    }

    private static ObjectDefinition object(String name, FieldDefinition... fields) {
        List<FieldDefinition> all = new ArrayList<>();
        all.add(system(name));
        all.addAll(List.of(fields));
        return new ObjectDefinition(name, name, name + "s", "", DefinitionKind.CUSTOM, null, true, 0, all);
    }

    private static RecordType recordType(String object, String name, boolean allFields, List<String> fields,
            Map<String, List<String>> picklists) {
        return new RecordType(object, name, name, "", true, false, "", allFields, fields, picklists, 0);
    }

    private static List<DependencyGraph.Violation> violations(List<ObjectDefinition> objects,
            List<RecordType> recordTypes) {
        Map<String, List<RecordType>> byObject = new java.util.LinkedHashMap<>();
        recordTypes.forEach(type -> byObject.computeIfAbsent(type.objectApiName(), key -> new ArrayList<>())
                .add(type));
        return DependencyGraph.of(new MetadataSnapshot(objects, byObject), CONTRIBUTORS).violations();
    }

    private static final FieldDefinition DEPARTMENT = field("Employee__c", "department__c", FieldType.LOOKUP, false,
            new ReferenceConfiguration("Department__c"));

    @Test
    void aLookupToAnExistingObjectIsFine() {
        assertThat(violations(List.of(object("Employee__c", DEPARTMENT), object("Department__c")), List.of()))
                .isEmpty();
    }

    @Test
    void aLookupToAnObjectThatIsGoneIsNamedWithItsDependent() {
        List<DependencyGraph.Violation> found = violations(List.of(object("Employee__c", DEPARTMENT)), List.of());

        assertThat(found).hasSize(1);
        assertThat(found.get(0).message()).isEqualTo("field Employee__c.department__c points to object "
                + "Department__c, which does not exist after this change.");
        assertThat(found.get(0).dependent().describe()).isEqualTo("field Employee__c.department__c");
        assertThat(found.get(0).needs().object()).isEqualTo("Department__c");
    }

    @Test
    void aRecordTypeOfferingARemovedFieldIsNamed() {
        ObjectDefinition employee = object("Employee__c",
                field("Employee__c", "code__c", FieldType.TEXT, false, new TextConfiguration(10)));
        RecordType remote = recordType("Employee__c", "Remote__c", false, List.of("code__c", "department__c"),
                Map.of());

        List<DependencyGraph.Violation> found = violations(List.of(employee), List.of(remote));

        assertThat(found).hasSize(1);
        assertThat(found.get(0).message()).isEqualTo("record type Remote__c of Employee__c offers field "
                + "Employee__c.department__c, which does not exist after this change.");
    }

    @Test
    void aRecordTypeThatOffersEveryFieldDependsOnNoneInParticular() {
        ObjectDefinition employee = object("Employee__c");
        RecordType everything = recordType("Employee__c", "All__c", true, List.of(), Map.of());

        assertThat(violations(List.of(employee), List.of(everything))).isEmpty();
    }

    @Test
    void aRestrictedPicklistNeedsTheFieldAndEveryValueItAllows() {
        FieldDefinition stage = field("Deal__c", "stage__c", FieldType.PICKLIST, false, new PicklistConfiguration(
                List.of(new PicklistValue("Open", "Open", true), new PicklistValue("Won", "Won", true))));
        ObjectDefinition deal = object("Deal__c", stage);
        RecordType fine = recordType("Deal__c", "Fine__c", false, List.of("stage__c"),
                Map.of("stage__c", List.of("Open")));
        RecordType broken = recordType("Deal__c", "Broken__c", false, List.of("stage__c"),
                Map.of("stage__c", List.of("Lost-secret")));

        List<DependencyGraph.Violation> found = violations(List.of(deal), List.of(fine, broken));

        assertThat(found).hasSize(1);
        assertThat(found.get(0).message()).contains("record type Broken__c of Deal__c allows a value of the "
                + "picklist Deal__c.stage__c").doesNotContain("Lost-secret");
    }

    @Test
    void aRestrictedRecordTypeMustOfferEveryRequiredFieldAndEveryMasterDetail() {
        ObjectDefinition line = object("Line__c",
                field("Line__c", "code__c", FieldType.TEXT, true, new TextConfiguration(10)),
                field("Line__c", "invoice__c", FieldType.MASTER_DETAIL, true,
                        ReferenceConfiguration.defaultsFor(FieldType.MASTER_DETAIL, "Invoice__c")),
                field("Line__c", "note__c", FieldType.TEXT, false, new TextConfiguration(10)));
        RecordType narrow = recordType("Line__c", "Narrow__c", false, List.of("note__c"), Map.of());

        List<String> messages = violations(List.of(line, object("Invoice__c")), List.of(narrow)).stream()
                .map(DependencyGraph.Violation::message).toList();

        assertThat(messages).containsExactly(
                "Record type Narrow__c of Line__c does not offer the required field Line__c.code__c: add the field "
                        + "to the record type.",
                "Record type Narrow__c of Line__c does not offer the required field Line__c.invoice__c: add the "
                        + "field to the record type.");
    }

    @Test
    void whatDependsOnAFieldCanBeAsked() {
        ObjectDefinition employee = object("Employee__c",
                field("Employee__c", "code__c", FieldType.TEXT, false, new TextConfiguration(10)));
        RecordType one = recordType("Employee__c", "One__c", false, List.of("code__c"), Map.of());
        RecordType two = recordType("Employee__c", "Two__c", false, List.of("code__c"), Map.of());
        DependencyGraph graph = DependencyGraph.of(new MetadataSnapshot(List.of(employee),
                Map.of("Employee__c", List.of(one, two))), CONTRIBUTORS);

        List<DependencyGraph.Edge> dependents = graph.dependentsOf(DependencyGraph.Node.field("Employee__c",
                "code__c"));

        assertThat(dependents).extracting(edge -> edge.dependent().item()).containsExactly("One__c", "Two__c");
    }

    @Test
    void aLaterSprintAddsItsOwnKindOfDependentWithoutTouchingTheGraph() {
        DependencyGraph.Contributor layouts = (snapshot, sink) -> sink.edge(
                DependencyGraph.Node.recordType("Employee__c", "Layout__c"), "shows",
                DependencyGraph.Node.field("Employee__c", "gone__c"));
        ObjectDefinition employee = object("Employee__c");

        List<DependencyGraph.Violation> found = DependencyGraph.of(new MetadataSnapshot(List.of(employee), Map.of()),
                List.of(layouts)).violations();

        assertThat(found).hasSize(1);
        assertThat(found.get(0).message()).contains("shows field Employee__c.gone__c");
    }

    @Test
    void anObjectWithNothingDependingOnItHasNoViolations() {
        assertThat(violations(List.of(object("Lonely__c", field("Lonely__c", "f__c", FieldType.BOOLEAN, false,
                new NoConfiguration()))), List.of())).isEmpty();
    }
}
