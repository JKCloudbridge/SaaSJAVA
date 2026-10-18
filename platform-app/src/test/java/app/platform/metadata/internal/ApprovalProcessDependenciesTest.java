package app.platform.metadata.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.expression.ExpressionEngine;
import app.platform.expression.Limits;
import app.platform.metadata.ApprovalProcessDefinition;
import app.platform.metadata.ApprovalProcessDefinition.Approver;
import app.platform.metadata.ApprovalProcessDefinition.ApproverKind;
import app.platform.metadata.ApprovalProcessDefinition.Assignment;
import app.platform.metadata.ApprovalProcessDefinition.Completion;
import app.platform.metadata.ApprovalProcessDefinition.Outcomes;
import app.platform.metadata.ApprovalProcessDefinition.Step;
import app.platform.metadata.DefinitionKind;
import app.platform.metadata.FieldConfiguration;
import app.platform.metadata.FieldConfiguration.NoConfiguration;
import app.platform.metadata.FieldConfiguration.ReferenceConfiguration;
import app.platform.metadata.FieldConfiguration.TextConfiguration;
import app.platform.metadata.FieldDefinition;
import app.platform.metadata.FieldType;
import app.platform.metadata.ObjectDefinition;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * What approval processes need, as edges and violations of the dependency graph (ADR-0145, ADR-0146, ADR-0149), without
 * a
 * database: a field the criteria, a user rule or an outcome names cannot lose its place, a user field must point to a
 * user,
 * a literal must fit the field it sets, and what does not pass is refused naming the process and the place.
 */
class ApprovalProcessDependenciesTest {

    private static final ExpressionSupport SUPPORT = new ExpressionSupport(new ExpressionEngine(Limits.standard()),
            new MetadataProperties(
                    new MetadataProperties.Limits(200, 500, 1000, 50, 200, 50, 50, 20, 20, 200, 50, 5, 500, 4, 200, 50,
                            200, 1_000_000, 200, 50, 200, 10, 25, 1000, 10, 200, 10, 10, 20),
                    new MetadataProperties.Cache(true, 1000)));

    private static FieldDefinition field(String name, FieldType type) {
        FieldConfiguration configuration = type == FieldType.TEXT || type == FieldType.LONG_TEXT
                ? new TextConfiguration(255) : new NoConfiguration();
        return new FieldDefinition("Expense__c", name, name, "", DefinitionKind.CUSTOM, type, false, false, null,
                configuration, false, 0);
    }

    private static FieldDefinition lookup(String name, String target) {
        return new FieldDefinition("Expense__c", name, name, "", DefinitionKind.CUSTOM, FieldType.LOOKUP, false, false,
                null, new ReferenceConfiguration(target), false, 0);
    }

    private static ObjectDefinition expense() {
        return new ObjectDefinition("Expense__c", "Expense", "Expenses", "", DefinitionKind.CUSTOM, null, true, 0,
                List.of(field("amount__c", FieldType.NUMBER), field("status__c", FieldType.TEXT),
                        field("approved__c", FieldType.BOOLEAN), lookup("manager__c", "User"),
                        lookup("vendor__c", "Vendor__c")));
    }

    private static ApprovalProcessDefinition process(String criteria, Step step, Outcomes outcomes) {
        return new ApprovalProcessDefinition(UUID.randomUUID(), UUID.randomUUID(), "Expense__c", "Large__c", "Large",
                "",
                true, 1, criteria, false, List.of(step), outcomes, null, 0);
    }

    private static Step step(Approver... approvers) {
        return new Step("Manager", "Manager", Completion.ANY, List.of(approvers));
    }

    private static List<String> violations(ApprovalProcessDefinition... processes) {
        MetadataSnapshot snapshot = new MetadataSnapshot(List.of(expense()), Map.of(),
                new MetadataSnapshot.Definitions(Map.of(), Map.of(), Map.of(), List.of(), Map.of(), Map.of(), List.of(),
                        List.of(), Map.of(), Map.of(), Map.of("Expense__c", List.of(processes))));
        return DependencyGraph.of(snapshot, List.of(new ApprovalProcessDependencies(SUPPORT))).violations().stream()
                .map(DependencyGraph.Violation::message).toList();
    }

    private static List<DependencyGraph.Edge> edges(ApprovalProcessDefinition process) {
        MetadataSnapshot snapshot = new MetadataSnapshot(List.of(expense()), Map.of(),
                new MetadataSnapshot.Definitions(Map.of(), Map.of(), Map.of(), List.of(), Map.of(), Map.of(), List.of(),
                        List.of(), Map.of(), Map.of(), Map.of("Expense__c", List.of(process))));
        return DependencyGraph.of(snapshot, List.of(new ApprovalProcessDependencies(SUPPORT))).edges();
    }

    @Test
    void aGoodProcessHasNoViolationAndNamesItsFieldsAsEdges() {
        ApprovalProcessDefinition good = process("amount__c > 10",
                step(new Approver(ApproverKind.RECORD_USER, "manager__c")),
                new Outcomes(List.of(new Assignment("status__c", "Approved")), List.of(), List.of()));

        assertThat(violations(good)).isEmpty();
        assertThat(edges(good)).extracting(edge -> edge.needs().describe()).contains("field Expense__c.amount__c",
                "field Expense__c.manager__c", "field Expense__c.status__c");
    }

    @Test
    void aFieldThatDoesNotExistIsAViolationNamingTheProcess() {
        ApprovalProcessDefinition bad = process("nothing__c > 10",
                step(new Approver(ApproverKind.RECORD_USER, "missing__c")),
                new Outcomes(List.of(new Assignment("gone__c", "x")), List.of(), List.of()));

        assertThat(violations(bad)).anyMatch(text -> text.contains("approval process Large__c")
                && text.contains("Expense__c.nothing__c") && text.contains("does not exist"));
        assertThat(violations(bad)).anyMatch(text -> text.contains("Expense__c.missing__c"));
        assertThat(violations(bad)).anyMatch(text -> text.contains("Expense__c.gone__c"));
    }

    @Test
    void aUserRuleNeedsALinkToAUser() {
        ApprovalProcessDefinition notAUser = process(null, step(new Approver(ApproverKind.RECORD_USER, "vendor__c")),
                Outcomes.none());
        ApprovalProcessDefinition text = process(null, step(new Approver(ApproverKind.RECORD_USER, "status__c")),
                Outcomes.none());

        assertThat(violations(notAUser)).anyMatch(message -> message.contains("steps[0].approvers[0].value")
                && message.contains("points to a user"));
        assertThat(violations(text)).anyMatch(message -> message.contains("steps[0].approvers[0].value"));
    }

    @Test
    void criteriaThatDoNotGiveTrueOrFalseOrDoNotParseAreViolations() {
        assertThat(violations(process("amount__c + 1", step(new Approver(ApproverKind.OWNER, null)), Outcomes.none())))
                .anyMatch(text -> text.contains("entry criteria that are not valid"));
    }

    @Test
    void anOutcomeLiteralMustFitTheFieldItSets() {
        ApprovalProcessDefinition misfit = process(null, step(new Approver(ApproverKind.OWNER, null)),
                new Outcomes(List.of(new Assignment("approved__c", "perhaps")), List.of(), List.of()));

        assertThat(violations(misfit)).anyMatch(text -> text.contains("outcomes.approved[0].value"));
    }

    @Test
    void theMessagesNameNoLiteral() {
        ApprovalProcessDefinition misfit = process(null, step(new Approver(ApproverKind.OWNER, null)),
                new Outcomes(List.of(new Assignment("approved__c", "SECRET-LITERAL")), List.of(), List.of()));

        assertThat(violations(misfit)).noneMatch(text -> text.contains("SECRET-LITERAL"));
    }
}
