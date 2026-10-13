package app.platform.metadata.internal;

import app.platform.security.SharingRules;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * What the criteria-based sharing rules of the security module need (Sprint 17, ADR-0116): every field their condition
 * names. Each becomes an edge of the dependency graph, so removing a field that a rule tests is refused before
 * publication with the rule named, live and in a change set. A rule of an object that is itself removed is not an edge:
 * the rules go with their object (the security module ends them when the object is removed).
 *
 * <p>The rules are read from the current rows of the organization in the caller's transaction, which is the same one
 * that applies and checks a change set, so the check sees the rules as they are now.
 */
@Component
class SharingDependencies implements DependencyGraph.Contributor {

    private final SharingRules rules;

    SharingDependencies(SharingRules rules) {
        this.rules = rules;
    }

    @Override
    public void contribute(MetadataSnapshot snapshot, DependencyGraph.Sink sink) {
        for (Map.Entry<String, Map<String, Set<String>>> object : rules.criterionFields().entrySet()) {
            if (snapshot.object(object.getKey()).isEmpty()) {
                continue;
            }
            for (Map.Entry<String, Set<String>> rule : object.getValue().entrySet()) {
                DependencyGraph.Node self = DependencyGraph.Node.sharingRule(object.getKey(), rule.getKey());
                for (String field : rule.getValue()) {
                    sink.edge(self, "tests", DependencyGraph.Node.field(object.getKey(), field));
                }
            }
        }
    }
}
