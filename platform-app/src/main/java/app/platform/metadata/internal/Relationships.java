package app.platform.metadata.internal;

import app.platform.metadata.DefinitionKind;
import app.platform.metadata.FieldConfiguration.ReferenceConfiguration;
import app.platform.metadata.FieldDefinition;
import app.platform.metadata.FieldType;
import app.platform.metadata.ObjectDefinition;
import app.platformapi.ApiException;
import app.platformapi.ObjectRelationshipsView;
import app.platformapi.RelationshipView;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * The relationships of an object in both directions (ADR-0063), read from the lookup and master-detail fields of the
 * published catalogue. A relationship has no table of its own: the field is the one source of truth, so the child
 * and the parent can never disagree about it.
 *
 * <ul>
 *   <li>A field on this object that points at another is a <b>parent</b>: many-to-one, or one-to-one when the field is
 *       unique.</li>
 *   <li>A field on another object that points at this one is a <b>child</b> list on this object: one-to-many, or
 *       one-to-one when the field is unique.</li>
 *   <li>An object with exactly two master-detail fields is a <b>junction</b>: the two masters are related many-to-many
 *       through it.</li>
 * </ul>
 *
 * The three system lookups every object has (owner, created by, changed by) are left out: they are not relationships
 * of the business model.
 */
@Component
class Relationships {

    private final MetadataCatalogue catalogue;

    Relationships(MetadataCatalogue catalogue) {
        this.catalogue = catalogue;
    }

    ObjectRelationshipsView of(String objectApiName) {
        MetadataSnapshot snapshot = catalogue.snapshot();
        ObjectDefinition self = snapshot.object(objectApiName)
                .orElseThrow(() -> ApiException.notFound("This object does not exist."));
        List<RelationshipView> parents = new ArrayList<>();
        List<RelationshipView> children = new ArrayList<>();
        List<RelationshipView> manyToMany = new ArrayList<>();
        for (FieldDefinition field : relationshipFields(self)) {
            ReferenceConfiguration reference = (ReferenceConfiguration) field.configuration();
            parents.add(view(field.unique() ? "ONE_TO_ONE" : "MANY_TO_ONE", self, field, reference,
                    reference.targetObject(), null));
        }
        for (ObjectDefinition other : snapshot.objects()) {
            List<FieldDefinition> fields = relationshipFields(other);
            for (FieldDefinition field : fields) {
                ReferenceConfiguration reference = (ReferenceConfiguration) field.configuration();
                if (reference.targetObject().equals(objectApiName)) {
                    children.add(view(field.unique() ? "ONE_TO_ONE" : "ONE_TO_MANY", other, field, reference,
                            other.apiName(), null));
                }
            }
            manyToMany.addAll(throughJunction(other, fields, objectApiName));
        }
        return new ObjectRelationshipsView(parents, children, manyToMany);
    }

    private static List<RelationshipView> throughJunction(ObjectDefinition junction, List<FieldDefinition> fields,
            String objectApiName) {
        List<FieldDefinition> masters = fields.stream().filter(field -> field.type() == FieldType.MASTER_DETAIL)
                .toList();
        List<RelationshipView> found = new ArrayList<>();
        if (masters.size() != 2) {
            return found;
        }
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 2; i++) {
            FieldDefinition mine = masters.get(i);
            ReferenceConfiguration reference = (ReferenceConfiguration) mine.configuration();
            if (!reference.targetObject().equals(objectApiName)) {
                continue;
            }
            String otherEnd = ((ReferenceConfiguration) masters.get(1 - i).configuration()).targetObject();
            if (seen.add(otherEnd)) {
                found.add(view("MANY_TO_MANY", junction, mine, reference, otherEnd, junction.apiName()));
            }
        }
        return found;
    }

    /** The lookup and master-detail fields of the object that the organization's model is made of. */
    private static List<FieldDefinition> relationshipFields(ObjectDefinition object) {
        return object.fields().stream()
                .filter(field -> field.kind() != DefinitionKind.SYSTEM)
                .filter(field -> field.configuration() instanceof ReferenceConfiguration)
                .toList();
    }

    private static RelationshipView view(String type, ObjectDefinition child, FieldDefinition field,
            ReferenceConfiguration reference, String otherObject, String via) {
        String listLabel = reference.listLabel().isEmpty() ? child.pluralLabel() : reference.listLabel();
        return new RelationshipView(type, field.type().name(), child.apiName(), field.apiName(), field.label(),
                reference.targetObject(), otherObject, via, reference.onDelete().name(), reference.reparentable(),
                field.required(), listLabel);
    }
}
