package app.platform.metadata.internal;

import app.platform.expression.Compiled;
import app.platform.expression.ExpressionException;
import app.platform.expression.ExpressionException.Kind;
import app.platform.expression.Purpose;
import app.platform.metadata.ApprovalProcessDefinition;
import app.platform.metadata.ObjectDefinition;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * What approval processes need (ADR-0145, ADR-0146, ADR-0149). Every need becomes an edge of the dependency graph, so
 * removing a field or an object that a process names is refused before publication with the process named, live and in
 * a
 * change set, and a rollback restores definitions in an order that satisfies them. Entry criteria that do not pass the
 * expression language, a user field that is not a link to a user and an outcome literal that does not fit its field are
 * violations that name the place.
 *
 * <p>Edges are made for every process, active or not: a switched-off process is still a definition that names the
 * field.
 */
@Component
class ApprovalProcessDependencies implements DependencyGraph.Contributor {

    private final ExpressionSupport support;

    ApprovalProcessDependencies(ExpressionSupport support) {
        this.support = support;
    }

    @Override
    public void contribute(MetadataSnapshot snapshot, DependencyGraph.Sink sink) {
        for (ApprovalProcessDefinition process : snapshot.allApprovalProcesses()) {
            String objectName = process.objectApiName();
            DependencyGraph.Node self = DependencyGraph.Node.approvalProcess(objectName, process.apiName());
            sink.edge(self, "belongs to", DependencyGraph.Node.object(objectName));
            snapshot.object(objectName).ifPresent(object -> {
                criteria(object, process, self, sink);
                stepsAndOutcomes(snapshot, object, process, self, sink);
            });
        }
    }

    private void criteria(ObjectDefinition object, ApprovalProcessDefinition process, DependencyGraph.Node self,
            DependencyGraph.Sink sink) {
        if (process.entryCriteria() == null) {
            return;
        }
        try {
            Compiled compiled = support.engine().compile(process.entryCriteria(), ExpressionSupport.schemaOf(object),
                    Purpose.APPROVAL_ENTRY);
            compiled.fields().forEach(name -> sink.edge(self, "uses",
                    DependencyGraph.Node.field(object.apiName(), name)));
        } catch (ExpressionException problem) {
            if (problem.kind() == Kind.UNKNOWN_FIELD) {
                usedFields(process).forEach(name -> sink.edge(self, "uses",
                        DependencyGraph.Node.field(object.apiName(), name)));
            } else {
                sink.violation(self, DependencyGraph.Node.object(object.apiName()), "Approval process "
                        + process.apiName() + " of " + object.apiName() + " has entry criteria that are not valid. "
                        + problem.getMessage());
            }
        }
    }

    private static void stepsAndOutcomes(MetadataSnapshot snapshot, ObjectDefinition object,
            ApprovalProcessDefinition process, DependencyGraph.Node self, DependencyGraph.Sink sink) {
        ApprovalProcessChecks.check(object, snapshot::object, process.steps(), process.outcomes(), process.fallback(),
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
                    public void problem(String place, String message) {
                        sink.violation(self, DependencyGraph.Node.object(object.apiName()), "Approval process "
                                + process.apiName() + " of " + object.apiName() + " is not valid (" + place + "). "
                                + message);
                    }

                    @Override
                    public void missing(String place, String message) {
                        // The edge to the missing field is the violation, in the words of every other missing
                        // dependency.
                    }
                });
    }

    private List<String> usedFields(ApprovalProcessDefinition process) {
        try {
            return support.engine().parse(process.entryCriteria()).fields();
        } catch (ExpressionException problem) {
            return List.of();
        }
    }
}
