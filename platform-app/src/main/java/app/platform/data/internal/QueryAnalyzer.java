package app.platform.data.internal;

import app.platform.data.QueryFilter;
import app.platform.data.QueryPlan.Compare;
import app.platform.data.QueryPlan.SystemColumn;
import app.platform.data.QueryPlan.ValueKind;
import app.platform.data.QueryResolve;
import app.platform.data.QuerySort;
import app.platform.data.RecordQuery;
import app.platform.metadata.DefinitionKind;
import app.platform.metadata.FieldConfiguration.ReferenceConfiguration;
import app.platform.metadata.FieldDefinition;
import app.platform.metadata.FieldType;
import app.platform.metadata.ObjectDefinition;
import app.platform.security.ObjectAccess;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The first three stages of a query (Sprint 16, ADR-0100): the <strong>shape</strong> of the request (sizes, depth,
 * the right number of values), the <strong>fields</strong> it names (against the metadata and against what the member
 * may read, in one step), and the <strong>operators and values</strong> (by the kind of each field). The first stage
 * that finds problems answers alone, with all of its problems and their places, and nothing is read.
 *
 * <p><strong>A field the member may not read is a field that does not exist.</strong> The names are resolved against
 * the metadata and the member's field permissions together, before anything that depends on the type of the field is
 * looked at, so no answer (an error about an operator, a type, a value) can tell the two apart: the member would
 * otherwise learn the value of a field they may not read by filtering on it. A message says what is expected and never
 * repeats a value or a name the member typed.
 *
 * <p>Everything a person types is data here: names are looked up in the metadata, values are converted to the stored
 * text of their kind, and nothing is ever spliced into a statement.
 */
@Component
class QueryAnalyzer {

    /** The stages of a query, in the order they run and answer. Tests hold the service to this list. */
    static final List<String> STAGES = List.of("shape", "fields", "operators", "record security", "plan", "sql",
            "execute");

    static final String NOT_A_FIELD = "This is not a field of the object.";
    static final String NOT_A_LINK = "This is not a link to another object.";
    static final String CANNOT_FILTER = "This field cannot be used in a filter or an order.";
    static final String FORMULA = "A formula result is stored when a record is saved, so it is not current for "
            + "records nobody has saved since the formula changed. It cannot be used in a filter or an order yet.";

    private static final int MAX_TEXT = 255;
    private static final int MAX_NUMBER_DIGITS = 38;
    private static final Map<String, SystemColumn> SYSTEM = Map.of("id", SystemColumn.ID, "ownerId",
            SystemColumn.OWNER, "createdAt", SystemColumn.CREATED_AT, "createdById", SystemColumn.CREATED_BY,
            "updatedAt", SystemColumn.UPDATED_AT, "updatedById", SystemColumn.UPDATED_BY);

    private static final Set<Compare> TEXT_OPS = EnumSet.allOf(Compare.class);
    private static final Set<Compare> ORDERED_OPS = EnumSet.of(Compare.EQ, Compare.NE, Compare.GT, Compare.GTE,
            Compare.LT, Compare.LTE, Compare.BETWEEN, Compare.IN, Compare.IS_EMPTY, Compare.IS_NOT_EMPTY);
    private static final Set<Compare> BOOLEAN_OPS = EnumSet.of(Compare.EQ, Compare.NE, Compare.IS_EMPTY,
            Compare.IS_NOT_EMPTY);
    private static final Set<Compare> LINK_OPS = EnumSet.of(Compare.EQ, Compare.NE, Compare.IN, Compare.IS_EMPTY,
            Compare.IS_NOT_EMPTY);

    /**
     * A field as a query reads it.
     *
     * @param name its API name
     * @param field its definition
     * @param kind the kind of value, or null when the field cannot be used in a filter or an order
     * @param system the column of the record it is read from, or null when it is read from the record's values
     */
    record Ref(String name, FieldDefinition field, ValueKind kind, SystemColumn system) {
    }

    /** A checked node of the filter. */
    sealed interface Node permits Group, Cond {
    }

    /**
     * A group of conditions.
     *
     * @param all true for AND
     * @param items its members
     */
    record Group(boolean all, List<Node> items) implements Node {
    }

    /**
     * One condition.
     *
     * @param ref the field
     * @param compare how it is compared
     * @param values the values as the text of their kind (see {@code SlotCopy})
     */
    record Cond(Ref ref, Compare compare, List<String> values) implements Node {
    }

    /**
     * One key of the order.
     *
     * @param ref the field
     * @param descending whether the largest comes first
     */
    record Key(Ref ref, boolean descending) {
    }

    /**
     * A lookup to follow.
     *
     * @param ref the link field
     * @param target the object it points at
     * @param fields the fields of the linked object asked for
     */
    record Follow(Ref ref, String target, List<String> fields) {
    }

    /**
     * A query that passed the first three stages.
     *
     * @param object the object
     * @param where the filter, or null
     * @param keys the order
     * @param projection the fields to bring, or null for every field the member may read
     * @param follows the lookups to follow
     * @param limit the page size
     * @param cursor the cursor given, or null
     * @param signature what identifies this query to its cursors (the filter and the order, not the page size)
     */
    record Analyzed(ObjectDefinition object, Node where, List<Key> keys, Set<String> projection,
            List<Follow> follows, int limit, String cursor, String signature) {
    }

    private final RecordProperties.Query limits;

    QueryAnalyzer(RecordProperties properties) {
        this.limits = properties.query();
    }

    /** Runs the first three stages. */
    Analyzed analyze(ObjectDefinition object, ObjectAccess access, RecordQuery query) {
        shape(query);
        Map<String, Ref> refs = fields(object, access, query);
        return operators(object, refs, query);
    }

    // ---- stage 1: shape ----

    private void shape(RecordQuery query) {
        Findings findings = new Findings();
        int[] nodes = {0};
        if (query.filter() != null) {
            shapeNode(query.filter(), "filter", 1, nodes, findings);
        }
        if (query.sort().size() > 2) {
            findings.add("sort", "Order by at most two fields.");
        }
        if (query.fields() != null && query.fields().size() > limits.maxFields()) {
            findings.add("fields", "Ask for at most " + limits.maxFields() + " fields.");
        }
        if (query.resolve().size() > limits.maxFields()) {
            findings.add("resolve", "Follow at most " + limits.maxFields() + " links.");
        }
        for (int index = 0; index < query.resolve().size(); index++) {
            QueryResolve resolve = query.resolve().get(index);
            if (resolve.fields().size() > limits.maxFields()) {
                findings.add("resolve[" + index + "].fields", "Ask for at most " + limits.maxFields() + " fields.");
            }
        }
        for (int index = 0; index < query.sort().size(); index++) {
            if (blank(query.sort().get(index).field())) {
                findings.add("sort[" + index + "].field", "Name a field.");
            }
        }
        if (query.fields() != null) {
            for (int index = 0; index < query.fields().size(); index++) {
                if (blank(query.fields().get(index))) {
                    findings.add("fields[" + index + "]", "Name a field.");
                }
            }
        }
        for (int index = 0; index < query.resolve().size(); index++) {
            if (blank(query.resolve().get(index).field())) {
                findings.add("resolve[" + index + "].field", "Name a link field.");
            }
        }
        if (query.limit() < 0 || query.limit() > limits.maxPageSize()) {
            findings.add("limit", "Ask for a page of 1 to " + limits.maxPageSize() + " records.");
        }
        findings.throwIfAny();
    }

    private static boolean blank(String name) {
        return name == null || name.isBlank();
    }

    private void shapeNode(QueryFilter node, String place, int depth, int[] nodes, Findings findings) {
        nodes[0]++;
        if (nodes[0] > limits.maxFilterNodes()) {
            if (nodes[0] == limits.maxFilterNodes() + 1) {
                findings.add("filter", "A filter may have at most " + limits.maxFilterNodes() + " conditions "
                        + "and groups.");
            }
            return;
        }
        if (depth > limits.maxFilterDepth()) {
            findings.add(place, "A filter may be at most " + limits.maxFilterDepth() + " levels deep.");
            return;
        }
        if (node.op() == null) {
            findings.add(place, "Choose how to compare.");
            return;
        }
        if (node.op().isGroup()) {
            if (node.items().isEmpty()) {
                findings.add(place, "A group needs at least one condition.");
            }
            if (node.field() != null || !node.values().isEmpty()) {
                findings.add(place, "A group has conditions, not a field or values.");
            }
            for (int index = 0; index < node.items().size(); index++) {
                shapeNode(node.items().get(index), place + ".items[" + index + "]", depth + 1, nodes, findings);
            }
            return;
        }
        if (!node.items().isEmpty()) {
            findings.add(place, "A condition has a field and values, not other conditions.");
        }
        if (node.field() == null || node.field().isBlank()) {
            findings.add(place + ".field", "Name a field.");
        }
        int count = node.values().size();
        boolean ok = switch (node.op()) {
            case IS_EMPTY, IS_NOT_EMPTY -> count == 0;
            case BETWEEN -> count == 2;
            case IN -> count >= 1 && count <= limits.maxInValues();
            default -> count == 1;
        };
        if (!ok) {
            findings.add(place + ".values", switch (node.op()) {
                case IS_EMPTY, IS_NOT_EMPTY -> "This comparison takes no value.";
                case BETWEEN -> "Give the two ends.";
                case IN -> "Give 1 to " + limits.maxInValues() + " values.";
                default -> "Give one value.";
            });
        }
    }

    // ---- stage 2: the fields, against the metadata and the member's field permissions together ----

    private Map<String, Ref> fields(ObjectDefinition object, ObjectAccess access, RecordQuery query) {
        Findings findings = new Findings();
        Map<String, Ref> refs = new LinkedHashMap<>();
        if (query.filter() != null) {
            fieldsOfNode(object, access, query.filter(), "filter", refs, findings);
        }
        for (int index = 0; index < query.sort().size(); index++) {
            resolve(object, access, query.sort().get(index).field(), "sort[" + index + "].field", refs, findings);
        }
        if (query.fields() != null) {
            for (int index = 0; index < query.fields().size(); index++) {
                resolve(object, access, query.fields().get(index), "fields[" + index + "]", refs, findings);
            }
        }
        for (int index = 0; index < query.resolve().size(); index++) {
            resolve(object, access, query.resolve().get(index).field(), "resolve[" + index + "].field", refs,
                    findings);
        }
        findings.throwIfAny();
        return refs;
    }

    private void fieldsOfNode(ObjectDefinition object, ObjectAccess access, QueryFilter node, String place,
            Map<String, Ref> refs, Findings findings) {
        if (node.op().isGroup()) {
            for (int index = 0; index < node.items().size(); index++) {
                fieldsOfNode(object, access, node.items().get(index), place + ".items[" + index + "]", refs,
                        findings);
            }
        } else {
            resolve(object, access, node.field(), place + ".field", refs, findings);
        }
    }

    /** A name is a field for this member only if the object has it and the member may read it. */
    private void resolve(ObjectDefinition object, ObjectAccess access, String name, String place,
            Map<String, Ref> refs, Findings findings) {
        if (name == null || refs.containsKey(name)) {
            return;
        }
        FieldDefinition field = object.field(name).filter(found -> access.readableFields().contains(name))
                .orElse(null);
        if (field == null) {
            findings.add(place, NOT_A_FIELD);
            return;
        }
        SystemColumn system = field.kind() == DefinitionKind.SYSTEM ? SYSTEM.get(name) : null;
        refs.put(name, new Ref(name, field, kindOf(field, system), system));
    }

    /** The kind of value a field is compared as; null when it cannot be compared (a formula, long text ...). */
    private static ValueKind kindOf(FieldDefinition field, SystemColumn system) {
        if (system != null) {
            return switch (system) {
                case CREATED_AT, UPDATED_AT -> ValueKind.TIMESTAMP;
                case ID, OWNER, CREATED_BY, UPDATED_BY -> ValueKind.UUID;
            };
        }
        return switch (field.type()) {
            case TEXT, EMAIL, PHONE, URL, PICKLIST, AUTO_NUMBER -> ValueKind.TEXT;
            case NUMBER, DECIMAL, CURRENCY, PERCENT -> ValueKind.NUMBER;
            case DATE -> ValueKind.DATE;
            case DATETIME -> ValueKind.TIMESTAMP;
            case BOOLEAN -> ValueKind.BOOLEAN;
            case LOOKUP, MASTER_DETAIL -> ValueKind.UUID;
            case LONG_TEXT, MULTI_PICKLIST, TIME, FORMULA -> null;
        };
    }

    // ---- stage 3: operators and values, by the kind of each field ----

    private Analyzed operators(ObjectDefinition object, Map<String, Ref> refs, RecordQuery query) {
        Findings findings = new Findings();
        Node where = query.filter() == null ? null : node(query.filter(), "filter", refs, findings);
        List<Key> keys = new ArrayList<>();
        Set<String> sorted = new HashSet<>();
        for (int index = 0; index < query.sort().size(); index++) {
            QuerySort sort = query.sort().get(index);
            Ref ref = refs.get(sort.field());
            String place = "sort[" + index + "].field";
            if (!sorted.add(sort.field())) {
                findings.add(place, "Each field can be used once in the order.");
            } else if (ref.kind() == null) {
                findings.add(place, ref.field().type() == FieldType.FORMULA ? FORMULA : CANNOT_FILTER);
            } else if (ref.kind() == ValueKind.UUID && ref.system() != SystemColumn.ID) {
                findings.add(place, "A link cannot be used in an order.");
            } else {
                keys.add(new Key(ref, sort.descending()));
            }
        }
        List<Follow> follows = new ArrayList<>();
        for (int index = 0; index < query.resolve().size(); index++) {
            QueryResolve resolve = query.resolve().get(index);
            Ref ref = refs.get(resolve.field());
            if (ref.field().configuration() instanceof ReferenceConfiguration reference) {
                follows.add(new Follow(ref, reference.targetObject(), resolve.fields()));
            } else {
                findings.add("resolve[" + index + "].field", NOT_A_LINK);
            }
        }
        findings.throwIfAny();
        Set<String> projection = query.fields() == null ? null : new HashSet<>(query.fields());
        int limit = query.limit() == 0 ? limits.defaultPageSize() : query.limit();
        return new Analyzed(object, where, List.copyOf(keys), projection, List.copyOf(follows), limit,
                query.cursor(), signature(object, where, keys));
    }

    private Node node(QueryFilter filter, String place, Map<String, Ref> refs, Findings findings) {
        if (filter.op().isGroup()) {
            List<Node> items = new ArrayList<>();
            for (int index = 0; index < filter.items().size(); index++) {
                items.add(node(filter.items().get(index), place + ".items[" + index + "]", refs, findings));
            }
            return new Group(filter.op() == QueryFilter.Op.AND, items);
        }
        Ref ref = refs.get(filter.field());
        Compare compare = Compare.valueOf(filter.op().name());
        if (ref.kind() == null) {
            findings.add(place + ".field", ref.field().type() == FieldType.FORMULA ? FORMULA : CANNOT_FILTER);
            return new Cond(ref, compare, List.of());
        }
        if (!allowed(ref.kind(), ref.system()).contains(compare)) {
            findings.add(place + ".op", "This comparison does not fit a field of this kind.");
            return new Cond(ref, compare, List.of());
        }
        List<String> values = new ArrayList<>();
        for (int index = 0; index < filter.values().size(); index++) {
            String text = coerce(ref.kind(), filter.values().get(index));
            if (text == null) {
                findings.add(place + ".values[" + index + "]", expected(ref.kind()));
            } else {
                values.add(text);
            }
        }
        if ((compare == Compare.STARTS_WITH || compare == Compare.CONTAINS) && values.size() == 1
                && values.get(0).isEmpty()) {
            findings.add(place + ".values[0]", "Give some text to look for.");
        }
        return new Cond(ref, compare, List.copyOf(values));
    }

    private static Set<Compare> allowed(ValueKind kind, SystemColumn system) {
        return switch (kind) {
            case TEXT -> TEXT_OPS;
            case NUMBER, DATE, TIMESTAMP -> ORDERED_OPS;
            case BOOLEAN -> BOOLEAN_OPS;
            case UUID -> system == SystemColumn.ID ? ORDERED_OPS : LINK_OPS;
        };
    }

    private static String expected(ValueKind kind) {
        return switch (kind) {
            case TEXT -> "Enter text of at most " + MAX_TEXT + " characters.";
            case NUMBER -> "Enter a number.";
            case DATE -> "Enter a day as year-month-day.";
            case TIMESTAMP -> "Enter a day and time such as 2026-10-12T09:30:00Z.";
            case UUID -> "Enter an identifier.";
            case BOOLEAN -> "Enter true or false.";
        };
    }

    /** The stored text of a value of the kind; null when the value is not of the kind. */
    static String coerce(ValueKind kind, Object raw) {
        try {
            return switch (kind) {
                case TEXT -> raw instanceof String text && text.length() <= MAX_TEXT && !text.isEmpty() ? text : null;
                case NUMBER -> number(raw);
                case DATE -> raw instanceof String text ? LocalDate.parse(text).toString() : null;
                case TIMESTAMP -> raw instanceof String text ? instant(text).toString() : null;
                case UUID -> raw instanceof String text && text.length() == 36 ? UUID.fromString(text).toString()
                        : null;
                case BOOLEAN -> raw instanceof Boolean flag ? flag.toString() : null;
            };
        } catch (DateTimeParseException | IllegalArgumentException e) {
            return null;
        }
    }

    private static String number(Object raw) {
        if (!(raw instanceof Number) && !(raw instanceof String)) {
            return null;
        }
        BigDecimal value = new BigDecimal(raw.toString().trim());
        if (value.precision() > MAX_NUMBER_DIGITS || Math.abs(value.scale()) > 2 * MAX_NUMBER_DIGITS) {
            return null;
        }
        return value.toPlainString();
    }

    private static Instant instant(String text) {
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException e) {
            return OffsetDateTime.parse(text).toInstant();
        }
    }

    // ---- what identifies a query to its cursors ----

    private static String signature(ObjectDefinition object, Node where, List<Key> keys) {
        StringBuilder canonical = new StringBuilder(object.apiName()).append('|');
        append(canonical, where);
        canonical.append('|');
        keys.forEach(key -> canonical.append(key.ref().name()).append(key.descending() ? "-" : "+").append(','));
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is part of every Java release", e);
        }
    }

    private static void append(StringBuilder canonical, Node node) {
        switch (node) {
            case null -> canonical.append('-');
            case Group group -> {
                canonical.append(group.all() ? "&(" : "|(");
                group.items().forEach(item -> append(canonical, item));
                canonical.append(')');
            }
            case Cond cond -> canonical.append(cond.ref().name()).append(':').append(cond.compare()).append(':')
                    .append(String.join("\u0001", cond.values())).append(';');
        }
    }

}
