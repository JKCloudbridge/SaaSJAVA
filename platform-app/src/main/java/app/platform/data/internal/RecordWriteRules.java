package app.platform.data.internal;

import app.platform.data.DataRecord;
import app.platform.data.RecordWriteRule;
import app.platform.metadata.ObjectDefinition;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/** Lazy discovery avoids a dependency cycle between a domain rule and the public record API it may read. */
@Component
final class RecordWriteRules {
    private final ObjectProvider<RecordWriteRule> rules;

    RecordWriteRules(ObjectProvider<RecordWriteRule> rules) {
        this.rules = rules;
    }

    RecordWriteRule.Changes prepare(ObjectDefinition object, DataRecord current, Map<String, Object> merged,
            Set<String> editable, boolean platform) {
        var set = new java.util.LinkedHashMap<String, Object>();
        var clear = new java.util.ArrayList<String>();
        rules.orderedStream().filter(rule -> rule.serves(object.apiName())).forEach(rule -> {
            RecordWriteRule.Changes changes = rule.prepare(object.apiName(), current, Map.copyOf(merged), editable,
                    platform);
            Set<String> named = new java.util.HashSet<>(changes.set().keySet());
            named.addAll(changes.clear());
            for (String field : named) {
                if (!rule.writes().contains(field)
                        || !object.field(field).map(value -> value.platformWritten()).orElse(false)) {
                    throw new IllegalStateException("A write rule may contribute only its platform-written fields");
                }
            }
            set.putAll(changes.set());
            clear.addAll(changes.clear());
        });
        return new RecordWriteRule.Changes(set, clear);
    }

    void stored(DataRecord before, DataRecord after) {
        rules.orderedStream().filter(rule -> rule.serves(after.objectApiName()))
                .forEach(rule -> rule.stored(before, after));
    }

    void checkLocked(ObjectDefinition object, DataRecord current, Map<String, Object> set,
            java.util.List<String> clear) {
        if (current == null) {
            return;
        }
        rules.orderedStream().filter(rule -> rule.serves(object.apiName())).forEach(rule -> {
            boolean changed = rule.lockedFields(current).stream().anyMatch(field ->
                    set.containsKey(field) && !WritePipeline.sameValue(current.fields().get(field), set.get(field))
                            || clear.contains(field) && current.fields().containsKey(field));
            if (changed) {
                throw app.platformapi.ApiException.validation("fields", rule.lockedWords());
            }
        });
    }

    Set<String> locked(DataRecord current) {
        if (current == null) {
            return Set.of();
        }
        return rules.orderedStream().filter(rule -> rule.serves(current.objectApiName()))
                .flatMap(rule -> rule.lockedFields(current).stream()).collect(java.util.stream.Collectors.toSet());
    }

    String uniqueWords(String object, String field, String fallback) {
        return rules.orderedStream().filter(rule -> rule.serves(object))
                .map(rule -> rule.uniqueWords(field)).filter(java.util.Objects::nonNull).findFirst().orElse(fallback);
    }
}
