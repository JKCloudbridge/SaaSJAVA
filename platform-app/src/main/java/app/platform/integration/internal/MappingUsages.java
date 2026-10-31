package app.platform.integration.internal;

import app.platform.integration.internal.MappingStore.LiveDefinition;
import app.platform.metadata.Metadata;
import app.platform.metadata.ObjectDefinition;
import app.platform.sharedkernel.mapping.MappingUsage;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * The answer of the mappings to the metadata module (Sprint 28, ADR-0217): whether a live mapping still uses an object
 * or a field, so that neither is removed behind its back. Only a live version counts. The fields a mapping uses are
 * those it maps and those its outgoing expressions read; both are found by compiling the live definitions against the
 * object as it is now, which is exactly the check publication made.
 */
@Component
class MappingUsages implements MappingUsage {

    private final MappingStore store;
    private final MappingCompiler compiler;
    private final Metadata metadata;

    MappingUsages(MappingStore store, MappingCompiler compiler, Metadata metadata) {
        this.store = store;
        this.compiler = compiler;
        this.metadata = metadata;
    }

    @Override
    public boolean usesObject(String objectApiName) {
        return store.countUsingObject(objectApiName) > 0;
    }

    @Override
    public boolean usesField(String objectApiName, String fieldApiName) {
        List<LiveDefinition> live = store.liveDefinitions().stream()
                .filter(definition -> definition.object().equals(objectApiName)).toList();
        if (live.isEmpty()) {
            return false;
        }
        ObjectDefinition object = metadata.object(objectApiName).orElse(null);
        for (LiveDefinition definition : live) {
            if (definition.definition().fields().stream().anyMatch(field -> field.platform().equals(fieldApiName))) {
                return true;
            }
            if (object != null && readsInExpression(definition, object, fieldApiName)) {
                return true;
            }
        }
        return false;
    }

    private boolean readsInExpression(LiveDefinition definition, ObjectDefinition object, String field) {
        MappingCompiler.Result compiled = compiler.compile("probe", definition.definition(), object);
        return compiled.ok() && compiled.mapping().fields().stream()
                .anyMatch(item -> item.out() != null && item.out().fields().contains(field));
    }
}
