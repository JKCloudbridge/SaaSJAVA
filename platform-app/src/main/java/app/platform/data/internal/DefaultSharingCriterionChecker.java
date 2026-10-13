package app.platform.data.internal;

import app.platform.data.QueryFilter;
import app.platform.data.RecordQuery;
import app.platform.metadata.Metadata;
import app.platform.metadata.ObjectDefinition;
import app.platform.security.SharingCriterionChecker;
import app.platformapi.ApiException;
import app.platformapi.QueryFilterNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Checks the condition of a criteria-based sharing rule (Sprint 17, ADR-0116) with the stages of a query: the shape,
 * the fields, the operators and values, and then the plan. The plan must be answerable from indexes: a rule's
 * condition becomes part of the statement of every list of every member it reaches, so a condition that cannot use an
 * index (a field not flagged as indexed, whose index is not complete yet, or a comparison that cannot use one) would
 * turn all of them into scans of the object. It is refused now, with the field to flag, and not discovered later as
 * slow lists (ADR-0119).
 *
 * <p>The words are the query model's own, at the place of the condition ({@code criterion...}); nothing a person typed
 * is repeated.
 */
@Component
class DefaultSharingCriterionChecker implements SharingCriterionChecker {

    private final Metadata metadata;
    private final QueryAnalyzer analyzer;
    private final QueryPlanner planner;
    private final IndexPolicy policy;

    DefaultSharingCriterionChecker(Metadata metadata, QueryAnalyzer analyzer, QueryPlanner planner,
            IndexPolicy policy) {
        this.metadata = metadata;
        this.analyzer = analyzer;
        this.planner = planner;
        this.policy = policy;
    }

    @Override
    public List<Problem> check(String objectApiName, QueryFilterNode criterion) {
        ObjectDefinition object = metadata.object(objectApiName).filter(found -> found.managedBy() == null)
                .orElse(null);
        if (object == null) {
            return List.of(new Problem("object", "The records of this object are not kept here."));
        }
        List<Problem> problems = new ArrayList<>();
        try {
            QueryFilter filter = FilterNodes.toFilter(criterion, "criterion");
            QueryAnalyzer.Analyzed analyzed = analyzer.analyze(object, AdmissionPlans.readableEverything(object),
                    new RecordQuery(filter, List.of(), null, List.of(), 0, null));
            QueryPlanner.Planned planned = planner.plan(analyzed, policy.completeSlots(object.apiName()), null, null,
                    1);
            if (planned.scan()) {
                for (QueryPlanner.Blame blame : planned.blame()) {
                    problems.add(new Problem("criterion", blame.inherent()
                            ? blame.field() + " is compared in a way that cannot use an index; use equals, a range "
                                    + "or in."
                            : blame.field() + " must be flagged as indexed, and its index complete, before a "
                                    + "sharing rule can use it."));
                }
                if (problems.isEmpty()) {
                    problems.add(new Problem("criterion", "The condition cannot be answered from indexes."));
                }
            }
        } catch (ApiException e) {
            for (Map.Entry<String, List<String>> entry : e.fields().entrySet()) {
                entry.getValue().forEach(message -> problems.add(new Problem(entry.getKey(), message)));
            }
            if (problems.isEmpty()) {
                problems.add(new Problem("criterion", "The condition is not valid."));
            }
        }
        return problems;
    }
}
