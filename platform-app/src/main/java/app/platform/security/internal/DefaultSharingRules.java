package app.platform.security.internal;

import app.platform.security.SharingRules;
import app.platformapi.QueryFilterNode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.stereotype.Component;

/**
 * Tells the metadata lifecycle which fields the criteria-based sharing rules of an object name (Sprint 17,
 * ADR-0116), so that a field a rule depends on is not removed from under it. The conditions are read from the rows of
 * the caller's transaction and walked as the data they are; a stored condition that cannot be read names no field
 * (it gives no access either, ADR-0115).
 */
@Component
class DefaultSharingRules implements SharingRules {

    private final SharingStore store;
    private final CriterionCodec codec;

    DefaultSharingRules(SharingStore store, CriterionCodec codec) {
        this.store = store;
        this.codec = codec;
    }

    @Override
    public Map<String, Map<String, Set<String>>> criterionFields() {
        Map<String, Map<String, Set<String>>> byObject = new LinkedHashMap<>();
        for (SharingStore.RuleRow rule : store.allRules()) {
            if (!"CRITERIA".equals(rule.kind()) || rule.criterion() == null) {
                continue;
            }
            Set<String> fields = new TreeSet<>();
            try {
                collect(codec.read(rule.criterion()), fields);
            } catch (RuntimeException e) {
                continue;
            }
            byObject.computeIfAbsent(rule.object(), key -> new LinkedHashMap<>()).put(rule.name(), fields);
        }
        return byObject;
    }

    private static void collect(QueryFilterNode node, Set<String> fields) {
        if (node.field() != null) {
            fields.add(node.field());
        }
        if (node.items() != null) {
            node.items().forEach(item -> collect(item, fields));
        }
    }
}
