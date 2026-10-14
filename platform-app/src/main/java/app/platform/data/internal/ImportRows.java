package app.platform.data.internal;

import app.platform.data.QueriedRecord;
import app.platform.data.QueryFilter;
import app.platform.data.RecordQuery;
import app.platform.data.Records;
import app.platform.metadata.DefinitionKind;
import app.platform.metadata.FieldConfiguration.ReferenceConfiguration;
import app.platform.metadata.FieldDefinition;
import app.platform.metadata.FieldType;
import app.platform.metadata.Metadata;
import app.platform.metadata.ObjectDefinition;
import app.platform.security.ObjectAccess;
import app.platformapi.ApiException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * One row of an import file becomes one write (Sprint 17, ADR-0123): the columns are mapped to fields, the cells are
 * read as values ({@link CsvValues}), and the record is created or, for an upsert, found by the value of its external
 * identifier (one indexed read) and updated. The write is {@link Records#create} or {@link Records#update}, the code
 * every other write runs, as the member who started the import: the gate, field security, the pipeline, the unique
 * check, the typed copies, the audit record and the event all apply, and Sprint 17A's record permission applies without
 * a change here. Each row runs in a savepoint, so a row that fails leaves nothing behind.
 *
 * <p>A cell that is empty leaves the field as it is: on a new record it has no value, on an existing one the value
 * stays. There is no way to clear a field with an import.
 *
 * <p>The reasons a row fails are the messages of the pipeline (they say what is expected and never repeat a value) or
 * the words of this class; they are bounded in length.
 */
@Component
class ImportRows {

    private static final int REASON_LENGTH = 500;

    /** How a column maps to a field. */
    record Column(int index, FieldDefinition field, String lookupObject, String lookupBy) {
    }

    /**
     * An import ready to run: the object, the columns in file order, the external identifier of an upsert.
     *
     * @param object the object
     * @param upsert whether a row finds its record by the external identifier
     * @param externalId the external identifier field of an upsert, or null
     * @param columns the mapped columns
     * @param width how many columns the header has
     * @param lookups the identifiers found so far for links written as the external identifier of the other record
     */
    record Plan(ObjectDefinition object, boolean upsert, FieldDefinition externalId, List<Column> columns, int width,
            Map<String, String> lookups) {

        /** Copies the list. */
        Plan {
            columns = List.copyOf(columns);
        }
    }

    /** What happened to a row. */
    enum Kind { CREATED, UPDATED, FAILED }

    /**
     * The outcome of a row.
     *
     * @param kind what happened
     * @param reason why it failed, or null
     */
    record Outcome(Kind kind, String reason) {

        /** Prints the kind only. */
        @Override
        public String toString() {
            return "Outcome[" + kind + "]";
        }
    }

    /** Everything wrong with a mapping or a header, at once, in plain words. */
    static final class Problems extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final transient List<String> found;

        Problems(List<String> found) {
            super("The import cannot be read as described.", null, false, false);
            this.found = List.copyOf(found);
        }

        List<String> found() {
            return found;
        }
    }

    private final Metadata metadata;
    private final Records records;
    private final TransactionTemplate savepoint;

    ImportRows(Metadata metadata, Records records, PlatformTransactionManager transactions) {
        this.metadata = metadata;
        this.records = records;
        this.savepoint = new TransactionTemplate(transactions);
        this.savepoint.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
    }

    /**
     * Checks a mapping against the metadata and, when the header is given, against the file.
     *
     * @param mapping header name to {@code field} or {@code field:externalIdFieldOfTheLinkedObject}; empty means the
     *        header names are the API names of the fields
     * @param header the names in the first row of the file, or null when only the mapping is being checked (before
     *        the file arrives, so an empty mapping is not yet judged: it is a promise kept open until the header is
     *        known, when this is called again)
     * @throws Problems every problem found
     */
    Plan plan(ObjectDefinition object, ObjectAccess access, String operation, String externalIdField,
            Map<String, String> mapping, List<String> header) {
        // Checked at start time, before the file (and so the header) is known: an empty mapping is not yet wrong,
        // it is a promise to use the header's own names once they arrive. Only once the header is known (here, or
        // again when the file is read) can "nothing is mapped" or "the external identifier is not among the mapped
        // columns" be judged.
        boolean deferred = header == null && mapping.isEmpty();
        List<String> problems = new ArrayList<>();
        Map<String, String> effective = mapping;
        if (header != null && mapping.isEmpty()) {
            effective = new LinkedHashMap<>();
            for (String name : header) {
                effective.put(name, name);
            }
        }
        List<Column> columns = new ArrayList<>();
        Set<String> mappedFields = new LinkedHashSet<>();
        for (Map.Entry<String, String> entry : effective.entrySet()) {
            Column column = column(object, access, entry.getKey(), entry.getValue(), header, problems);
            if (column != null) {
                if (!mappedFields.add(column.field().apiName())) {
                    problems.add("Two columns are mapped to the same field.");
                }
                columns.add(column);
            }
        }
        if (header != null) {
            Set<String> unknown = new LinkedHashSet<>(header);
            unknown.removeAll(effective.keySet());
            if (!unknown.isEmpty()) {
                problems.add("The file has columns that are not mapped: " + names(unknown) + ".");
            }
        }
        FieldDefinition external = null;
        if ("UPSERT".equals(operation)) {
            external = object.field(externalIdField).filter(FieldDefinition::externalId).orElse(null);
            if (external == null) {
                problems.add("An upsert needs a field flagged as an external identifier.");
            } else if (!deferred && !mappedFields.contains(external.apiName())) {
                problems.add("The external identifier of an upsert must be one of the mapped columns.");
            }
        }
        if (!deferred && columns.isEmpty() && problems.isEmpty()) {
            problems.add("Nothing is mapped.");
        }
        if (!problems.isEmpty()) {
            throw new Problems(problems);
        }
        return new Plan(object, "UPSERT".equals(operation), external, columns, header == null ? 0 : header.size(),
                new HashMap<>());
    }

    private Column column(ObjectDefinition object, ObjectAccess access, String name, String spec,
            List<String> header, List<String> problems) {
        int index = header == null ? -1 : header.indexOf(name);
        if (header != null && index < 0) {
            problems.add("The mapping names a column the file does not have: " + shown(name) + ".");
            return null;
        }
        String fieldName = spec;
        String lookupBy = null;
        int colon = spec.indexOf(':');
        if (colon > 0) {
            fieldName = spec.substring(0, colon);
            lookupBy = spec.substring(colon + 1);
        }
        FieldDefinition field = object.field(fieldName).orElse(null);
        if (field == null || field.kind() == DefinitionKind.SYSTEM || field.retired()
                || field.type() == FieldType.FORMULA || field.type() == FieldType.AUTO_NUMBER
                || !access.editableFields().contains(fieldName)) {
            problems.add("The column " + shown(name) + " is mapped to a field that does not exist or cannot be "
                    + "written by an import.");
            return null;
        }
        String lookupObject = null;
        if (lookupBy != null) {
            if (field.type() != FieldType.LOOKUP && field.type() != FieldType.MASTER_DETAIL) {
                problems.add("The column " + shown(name) + " names an external identifier but its field is not a "
                        + "link.");
                return null;
            }
            lookupObject = ((ReferenceConfiguration) field.configuration()).targetObject();
            String by = lookupBy;
            boolean flagged = metadata.object(lookupObject).flatMap(target -> target.field(by))
                    .map(FieldDefinition::externalId).orElse(false);
            if (!flagged) {
                problems.add("The column " + shown(name) + " names a field of the linked object that is not an "
                        + "external identifier.");
                return null;
            }
        }
        return new Column(index, field, lookupObject, lookupBy);
    }

    /**
     * Runs one row. Must be called inside the transaction of the batch, as the member the import acts for.
     *
     * @param cells the values of the row
     */
    Outcome process(Plan plan, List<String> cells) {
        if (cells.size() != plan.width()) {
            return failed("The row has " + cells.size() + " values and the header has " + plan.width() + ".");
        }
        // The whole row, reading the cells and writing them, runs in one savepoint: resolving a link by the other
        // object's external identifier reads through the same gated, transactional path as any other read, and a
        // read that is refused must not poison the batch's own transaction for the rows after it, only this row's
        // savepoint.
        try {
            return savepoint.execute(status -> {
                Map<String, Object> values = new LinkedHashMap<>();
                List<String> problems = new ArrayList<>();
                for (Column column : plan.columns()) {
                    String cell = cells.get(column.index());
                    if (cell.isBlank()) {
                        continue;
                    }
                    try {
                        values.put(column.field().apiName(), CsvValues.convert(column.field(), cell,
                                column.lookupBy() == null ? null : text -> resolve(plan, column, text)));
                    } catch (CsvValues.Problem e) {
                        problems.add(column.field().apiName() + ": " + e.getMessage());
                    }
                }
                if (!problems.isEmpty()) {
                    throw new CsvValues.Problem(String.join(" ", problems));
                }
                return write(plan, values);
            });
        } catch (CsvValues.Problem e) {
            return failed(e.getMessage());
        } catch (ApiException e) {
            if (e.code().httpStatus() >= 500 || e.code().httpStatus() == 429) {
                throw e;
            }
            return failed(reason(e));
        }
    }

    private Outcome write(Plan plan, Map<String, Object> values) {
        String object = plan.object().apiName();
        if (!plan.upsert()) {
            records.create(object, null, values);
            return new Outcome(Kind.CREATED, null);
        }
        String key = plan.externalId().apiName();
        Object identifier = values.get(key);
        if (identifier == null) {
            throw ApiException.validation(key, "The external identifier is empty, so the record cannot be found.");
        }
        QueriedRecord existing = find(object, key, identifier);
        if (existing == null) {
            records.create(object, null, values);
            return new Outcome(Kind.CREATED, null);
        }
        Map<String, Object> changes = new LinkedHashMap<>(values);
        changes.remove(key);
        if (!changes.isEmpty()) {
            records.update(object, existing.record().id(), existing.record().version(), null, changes, List.of());
        }
        return new Outcome(Kind.UPDATED, null);
    }

    /** The first record whose field has the value, as the member may read it, or null. */
    private QueriedRecord find(String object, String field, Object value) {
        RecordQuery query = new RecordQuery(new QueryFilter(QueryFilter.Op.EQ, List.of(), field, List.of(value)),
                List.of(), List.of(field), List.of(), 1, null);
        List<QueriedRecord> found = records.query(object, query).records();
        return found.isEmpty() ? null : found.get(0);
    }

    /** The identifier of the record of the linked object that has the external identifier. */
    private String resolve(Plan plan, Column column, String value) {
        String key = column.lookupObject() + "|" + column.lookupBy() + "|" + value;
        String known = plan.lookups().get(key);
        if (known != null) {
            return known;
        }
        QueriedRecord found;
        try {
            found = find(column.lookupObject(), column.lookupBy(), value);
        } catch (ApiException e) {
            throw new CsvValues.Problem("The linked records cannot be searched by that field.");
        }
        if (found == null) {
            throw new CsvValues.Problem("No record has that external identifier.");
        }
        String id = found.record().id().toString();
        plan.lookups().put(key, id);
        return id;
    }

    private static Outcome failed(String reason) {
        return new Outcome(Kind.FAILED, reason.length() <= REASON_LENGTH ? reason : reason.substring(0, REASON_LENGTH));
    }

    /** The messages of a refusal with the places they are about. */
    private static String reason(ApiException e) {
        if (e.fields().isEmpty()) {
            return e.getMessage();
        }
        List<String> parts = new ArrayList<>();
        e.fields().forEach((place, messages) -> messages.forEach(message -> parts.add(place + ": " + message)));
        return String.join(" ", parts);
    }

    private static String names(Set<String> names) {
        List<String> shown = names.stream().limit(10).map(ImportRows::shown).toList();
        return String.join(", ", shown) + (names.size() > 10 ? " and " + (names.size() - 10) + " more" : "");
    }

    /** A column name from the file, bounded: it is typed text of the organization, shown only to its owner. */
    private static String shown(String name) {
        String clean = name.replaceAll("\\p{Cntrl}", " ");
        return "'" + (clean.length() <= 60 ? clean : clean.substring(0, 60)) + "'";
    }
}
