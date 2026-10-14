package app.platform.data.internal;

import app.platform.data.DataRecord;
import app.platform.metadata.FieldDefinition;
import app.platform.metadata.ObjectDefinition;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Keeps the words of a record's searchable fields in step with its values (Sprint 17, ADR-0124): called by every write
 * of a record in the transaction of the write (the same discipline as the typed copies and the pointers of links), for
 * the searchable fields the write named, or for all of them on a new record. A write that names no searchable field
 * costs nothing. The fill of a newly searchable field for the records that already exist uses the same function, so
 * the words of a record are the same whichever wrote them.
 *
 * <p>Only text-like values have words; a value that is not text (a field that changed type is not possible, but a
 * record written before the pipeline may hold one) has none.
 */
@Component
class SearchIndexer {

    private final SearchStore store;

    SearchIndexer(SearchStore store) {
        this.store = store;
    }

    /**
     * Writes the words of the record's searchable fields.
     *
     * @param touched the API names of the fields the write named, or null for all of them
     */
    void index(ObjectDefinition object, DataRecord record, Collection<String> touched, UUID actor) {
        for (FieldDefinition field : searchable(object)) {
            if (touched == null || touched.contains(field.apiName())) {
                indexField(object.apiName(), record, field.apiName(), actor);
            }
        }
    }

    /** Writes the words of one field of the record. */
    void indexField(String objectApiName, DataRecord record, String fieldApiName, UUID actor) {
        Object value = record.fields().get(fieldApiName);
        Set<String> words = value instanceof String text ? SearchTokens.wordsOf(text) : Set.of();
        store.replace(objectApiName, record.id(), fieldApiName, words, actor);
    }

    /** The searchable fields of the object, in the order of their names. */
    static List<FieldDefinition> searchable(ObjectDefinition object) {
        return object.fields().stream().filter(field -> field.searchable() && !field.retired())
                .sorted((left, right) -> left.apiName().compareTo(right.apiName())).toList();
    }
}
