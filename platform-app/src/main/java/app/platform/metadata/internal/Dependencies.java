package app.platform.metadata.internal;

import app.platform.metadata.DefinitionKind;
import app.platform.metadata.FieldConfiguration.ReferenceConfiguration;
import app.platform.metadata.FieldDefinition;
import app.platform.metadata.FieldType;
import app.platform.metadata.ObjectDefinition;
import app.platform.metadata.RecordType;
import java.util.HashSet;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * The dependents that exist in Sprint 11 (ADR-0065): a lookup or master-detail field depends on the object it points
 * to, and a record type depends on its object, on every field it offers and on every picklist value it allows. Layouts
 * and rules add their own {@link DependencyGraph.Contributor} in later sprints.
 */
final class Dependencies {

    private Dependencies() {
    }

    /** Fields that point at objects. */
    @Component
    static class References implements DependencyGraph.Contributor {

        @Override
        public void contribute(MetadataSnapshot snapshot, DependencyGraph.Sink sink) {
            for (ObjectDefinition object : snapshot.objects()) {
                for (FieldDefinition field : object.fields()) {
                    if (field.configuration() instanceof ReferenceConfiguration reference) {
                        sink.edge(DependencyGraph.Node.field(object.apiName(), field.apiName()), "points to",
                                DependencyGraph.Node.object(reference.targetObject()));
                    }
                }
            }
        }
    }

    /** Record types: the object, the fields and the picklist values they name. */
    @Component
    static class RecordTypes implements DependencyGraph.Contributor {

        @Override
        public void contribute(MetadataSnapshot snapshot, DependencyGraph.Sink sink) {
            for (RecordType type : snapshot.allRecordTypes()) {
                DependencyGraph.Node self = DependencyGraph.Node.recordType(type.objectApiName(), type.apiName());
                sink.edge(self, "belongs to", DependencyGraph.Node.object(type.objectApiName()));
                for (String field : type.availableFields()) {
                    sink.edge(self, "offers", DependencyGraph.Node.field(type.objectApiName(), field));
                }
                type.picklistValues().forEach((field, values) -> {
                    sink.edge(self, "restricts the picklist", DependencyGraph.Node.field(type.objectApiName(), field));
                    values.forEach(value -> sink.edge(self, "allows",
                            DependencyGraph.Node.picklistValue(type.objectApiName(), field, value)));
                });
                snapshot.object(type.objectApiName()).ifPresent(object -> mustOfferRequired(type, object, sink));
            }
        }

        /**
         * A record needs a value in every required field and a master for every master-detail field, so a record
         * type that leaves such a field out could never have a valid record.
         */
        private static void mustOfferRequired(RecordType type, ObjectDefinition object, DependencyGraph.Sink sink) {
            if (type.allFields()) {
                return;
            }
            Set<String> offered = new HashSet<>(type.availableFields());
            for (FieldDefinition field : object.fields()) {
                boolean needed = field.kind() != DefinitionKind.SYSTEM
                        && (field.required() || field.type() == FieldType.MASTER_DETAIL) && !field.retired();
                if (needed && !offered.contains(field.apiName())) {
                    sink.violation(DependencyGraph.Node.recordType(type.objectApiName(), type.apiName()),
                            DependencyGraph.Node.field(object.apiName(), field.apiName()),
                            "Record type " + type.apiName() + " of " + object.apiName() + " does not offer the "
                                    + "required field " + object.apiName() + "." + field.apiName()
                                    + ": add the field to the record type.");
                }
            }
        }
    }
}
