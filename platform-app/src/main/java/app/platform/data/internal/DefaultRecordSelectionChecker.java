package app.platform.data.internal;

import app.platform.data.QueryFilter;
import app.platform.data.RecordQuery;
import app.platform.metadata.Metadata;
import app.platform.metadata.ObjectDefinition;
import app.platform.metadata.RecordSelectionChecker;
import app.platformapi.ApiException;
import app.platformapi.QueryFilterNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Checks the selection of a scheduled workflow (Sprint 22, ADR-0136) with the stages of a query: the shape, the fields
 * and the operators and values. Unlike a criteria-based sharing rule, the selection is not part of every list of every
 * member, so it need not be answerable from indexes: the scheduler reads in bounded pages as the platform. The words
 * are the query model's own, at the place of the selection; nothing a person typed is repeated.
 */
@Component
class DefaultRecordSelectionChecker implements RecordSelectionChecker {

    private final Metadata metadata;
    private final QueryAnalyzer analyzer;

    DefaultRecordSelectionChecker(Metadata metadata, QueryAnalyzer analyzer) {
        this.metadata = metadata;
        this.analyzer = analyzer;
    }

    @Override
    public List<Problem> check(String objectApiName, QueryFilterNode selection) {
        ObjectDefinition object = metadata.object(objectApiName).filter(found -> found.managedBy() == null)
                .orElse(null);
        if (object == null) {
            return List.of(new Problem("selection", "The records of this object are not kept here."));
        }
        List<Problem> problems = new ArrayList<>();
        try {
            QueryFilter filter = FilterNodes.toFilter(selection, "selection");
            analyzer.analyze(object, AdmissionPlans.readableEverything(object),
                    new RecordQuery(filter, List.of(), null, List.of(), 0, null));
        } catch (ApiException e) {
            for (Map.Entry<String, List<String>> entry : e.fields().entrySet()) {
                entry.getValue().forEach(message -> problems.add(new Problem(entry.getKey(), message)));
            }
            if (problems.isEmpty()) {
                problems.add(new Problem("selection", "The selection is not valid."));
            }
        }
        return problems;
    }
}
