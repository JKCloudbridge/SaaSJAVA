package app.platform.data.internal;

import app.platform.metadata.DefinitionKind;
import app.platform.metadata.FieldDefinition;
import app.platform.metadata.FieldType;
import app.platform.metadata.ObjectDefinition;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Which field names a record for people (Sprint 19, ADR-0129): the text of a link to a record, of a row that stands for
 * it, of a heading. The platform has no "name" field the organization must define, so the choice is made here, once,
 * and both the form descriptor and the lookup labels use it, so a record is called the same thing everywhere:
 *
 * <ol>
 * <li>the first searchable text field the member may read (an administrator who flagged a field as searchable chose
 * what people look records up by);</li>
 * <li>otherwise the first required text field;</li>
 * <li>otherwise the first text field;</li>
 * <li>otherwise the auto-number every object has.</li>
 * </ol>
 *
 * Only a field the member may read can serve: for another member the same object may be named by another field.
 */
final class TitleFields {

    private static final Set<FieldType> TEXT_LIKE = Set.of(FieldType.TEXT, FieldType.EMAIL, FieldType.PHONE);
    private static final String SEQUENCE = "sequence";

    private TitleFields() {
    }

    /**
     * The field that names a record of the object for a member who may read the given fields.
     *
     * @param readable the API names of the fields the member may read
     */
    static Optional<FieldDefinition> of(ObjectDefinition object, Set<String> readable) {
        Optional<FieldDefinition> searchable = first(object, readable, field -> field.searchable());
        if (searchable.isPresent()) {
            return searchable;
        }
        Optional<FieldDefinition> required = first(object, readable, FieldDefinition::required);
        if (required.isPresent()) {
            return required;
        }
        Optional<FieldDefinition> text = first(object, readable, field -> true);
        if (text.isPresent()) {
            return text;
        }
        return object.field(SEQUENCE).filter(field -> readable.contains(field.apiName()));
    }

    private static Optional<FieldDefinition> first(ObjectDefinition object, Set<String> readable,
            Predicate<FieldDefinition> extra) {
        return object.fields().stream()
                .filter(field -> field.kind() != DefinitionKind.SYSTEM && !field.retired())
                .filter(field -> TEXT_LIKE.contains(field.type()) && readable.contains(field.apiName()))
                .filter(extra).findFirst();
    }
}
