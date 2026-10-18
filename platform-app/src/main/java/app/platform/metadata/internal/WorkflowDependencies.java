package app.platform.metadata.internal;

import app.platform.expression.Compiled;
import app.platform.expression.ExpressionException;
import app.platform.expression.ExpressionException.Kind;
import app.platform.expression.Purpose;
import app.platform.metadata.FieldDefinition;
import app.platform.metadata.FieldType;
import app.platform.metadata.ObjectDefinition;
import app.platform.metadata.RecordSelectionChecker;
import app.platform.metadata.WorkflowDefinition;
import app.platformapi.QueryFilterNode;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * What workflows need (ADR-0136). Every need becomes an edge of the dependency graph, so removing a field or an object
 * that a workflow names is refused before publication with the workflow named, live and in a change set, and a rollback
 * restores definitions in an order that satisfies them. A condition that does not pass the expression language, a date
 * field that is not a date and a selection that does not fit the query model are violations that name the place.
 *
 * <p>Edges are made for every workflow, active or not: a switched-off workflow is still a definition that names the
 * field.
 */
@Component
class WorkflowDependencies implements DependencyGraph.Contributor {

    private final ExpressionSupport support;
    private final ObjectProvider<RecordSelectionChecker> selections;

    WorkflowDependencies(ExpressionSupport support, ObjectProvider<RecordSelectionChecker> selections) {
        this.support = support;
        this.selections = selections;
    }

    @Override
    public void contribute(MetadataSnapshot snapshot, DependencyGraph.Sink sink) {
        for (WorkflowDefinition workflow : snapshot.allWorkflows()) {
            String objectName = workflow.objectApiName();
            DependencyGraph.Node self = DependencyGraph.Node.workflow(objectName, workflow.apiName());
            sink.edge(self, "belongs to", DependencyGraph.Node.object(objectName));
            snapshot.object(objectName).ifPresent(object -> {
                trigger(object, workflow, self, sink);
                condition(object, workflow, self, sink);
                selection(object, workflow, self, sink);
                actions(snapshot, object, workflow, self, sink);
            });
        }
    }

    private static void trigger(ObjectDefinition object, WorkflowDefinition workflow, DependencyGraph.Node self,
            DependencyGraph.Sink sink) {
        WorkflowDefinition.Trigger trigger = workflow.trigger();
        for (String field : trigger.fields()) {
            sink.edge(self, "starts when it changes", DependencyGraph.Node.field(object.apiName(), field));
        }
        if (trigger.kind() == WorkflowDefinition.Kind.APPROVAL_OUTCOME && trigger.process() != null) {
            sink.edge(self, "starts when it ends", DependencyGraph.Node.approvalProcess(object.apiName(),
                    trigger.process()));
        }
        if (trigger.kind() == WorkflowDefinition.Kind.DAYS_AFTER_DATE && trigger.field() != null) {
            sink.edge(self, "counts days from", DependencyGraph.Node.field(object.apiName(), trigger.field()));
            FieldDefinition field = object.field(trigger.field()).orElse(null);
            if (field != null && field.type() != FieldType.DATE && field.type() != FieldType.DATETIME) {
                sink.violation(self, DependencyGraph.Node.field(object.apiName(), trigger.field()), "Workflow "
                        + workflow.apiName() + " of " + object.apiName() + " counts days from "
                        + object.apiName() + "." + trigger.field() + ", which is not a date or a date and time.");
            }
        }
    }

    private void condition(ObjectDefinition object, WorkflowDefinition workflow, DependencyGraph.Node self,
            DependencyGraph.Sink sink) {
        if (workflow.condition() == null) {
            return;
        }
        try {
            Compiled compiled = support.engine().compile(workflow.condition(), ExpressionSupport.schemaOf(object),
                    Purpose.WORKFLOW_CONDITION);
            compiled.fields().forEach(name -> sink.edge(self, "uses",
                    DependencyGraph.Node.field(object.apiName(), name)));
        } catch (ExpressionException problem) {
            if (problem.kind() == Kind.UNKNOWN_FIELD) {
                usedFields(workflow).forEach(name -> sink.edge(self, "uses",
                        DependencyGraph.Node.field(object.apiName(), name)));
            } else {
                sink.violation(self, DependencyGraph.Node.object(object.apiName()), "Workflow " + workflow.apiName()
                        + " of " + object.apiName() + " has a condition that is not valid. " + problem.getMessage());
            }
        }
    }

    /**
     * Every field an action names is an edge (so removing it is refused naming the workflow), and what can be said
     * about the actions with the objects known is a violation naming the place (ADR-0138).
     */
    private static void actions(MetadataSnapshot snapshot, ObjectDefinition object, WorkflowDefinition workflow,
            DependencyGraph.Node self, DependencyGraph.Sink sink) {
        List<WorkflowDefinition.Action> actions = workflow.actions();
        for (int i = 0; i < actions.size(); i++) {
            WorkflowActionChecks.check(object, snapshot::object, actions.get(i), "actions[" + i + "]",
                    new WorkflowActionChecks.Report() {
                        @Override
                        public void needsField(String needed, String field, String relation) {
                            sink.edge(self, relation, DependencyGraph.Node.field(needed, field));
                        }

                        @Override
                        public void needsObject(String needed, String relation) {
                            sink.edge(self, relation, DependencyGraph.Node.object(needed));
                        }

                        @Override
                        public void needsApprovalProcess(String needed, String process, String relation) {
                            sink.edge(self, relation, DependencyGraph.Node.approvalProcess(needed, process));
                        }

                        @Override
                        public void problem(String place, String message) {
                            sink.violation(self, DependencyGraph.Node.object(object.apiName()), "Workflow "
                                    + workflow.apiName() + " of " + object.apiName() + " has an action that is not "
                                    + "valid (" + place + "). " + message);
                        }

                        @Override
                        public void missing(String place, String message) {
                            // The edge to the missing field or object is the violation, in the words of every other
                            // missing dependency.
                        }
                    });
        }
    }

    private List<String> usedFields(WorkflowDefinition workflow) {
        try {
            return support.engine().parse(workflow.condition()).fields();
        } catch (ExpressionException problem) {
            return List.of();
        }
    }

    private void selection(ObjectDefinition object, WorkflowDefinition workflow, DependencyGraph.Node self,
            DependencyGraph.Sink sink) {
        QueryFilterNode selection = workflow.trigger().selection();
        if (selection == null) {
            return;
        }
        Set<String> fields = new LinkedHashSet<>();
        collect(selection, fields);
        fields.forEach(name -> sink.edge(self, "selects records by",
                DependencyGraph.Node.field(object.apiName(), name)));
        RecordSelectionChecker checker = selections.getIfAvailable();
        if (checker == null) {
            return;
        }
        // A field that does not exist is the edge's violation, in the words of every other missing dependency.
        boolean allFieldsExist = fields.stream().allMatch(name -> object.field(name).isPresent());
        if (!allFieldsExist) {
            return;
        }
        for (RecordSelectionChecker.Problem problem : checker.check(object.apiName(), selection)) {
            sink.violation(self, DependencyGraph.Node.object(object.apiName()), "Workflow " + workflow.apiName()
                    + " of " + object.apiName() + " has a selection that is not valid (" + problem.place() + "). "
                    + problem.message());
        }
    }

    private static void collect(QueryFilterNode node, Set<String> fields) {
        if (node.field() != null && !node.field().isBlank()) {
            fields.add(node.field());
        }
        if (node.items() != null) {
            node.items().forEach(item -> collect(item, fields));
        }
    }
}
