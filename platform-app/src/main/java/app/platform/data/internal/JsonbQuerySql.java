package app.platform.data.internal;

import app.platform.data.QueryPlan;
import app.platform.data.QueryPlan.After;
import app.platform.data.QueryPlan.Column;
import app.platform.data.QueryPlan.Compare;
import app.platform.data.QueryPlan.Group;
import app.platform.data.QueryPlan.NoRecords;
import app.platform.data.QueryPlan.Node;
import app.platform.data.QueryPlan.OwnedByAny;
import app.platform.data.QueryPlan.Payload;
import app.platform.data.QueryPlan.SharedWithAny;
import app.platform.data.QueryPlan.Slot;
import app.platform.data.QueryPlan.SortKey;
import app.platform.data.QueryPlan.Source;
import app.platform.data.QueryPlan.Test;
import app.platform.data.QueryPlan.ValueKind;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Turns a {@link QueryPlan} into the pieces of a parameterized statement (Sprint 16, ADR-0100). It builds text from
 * fixed fragments only: the kinds of comparison, the slot columns (taken from {@link SlotColumn} by the storage),
 * the system columns and the casts are constants; <strong>every name of a field and every value is a parameter</strong>
 * ({@code cast(:f0 as text)}, {@code cast(:p3 as numeric)}), so nothing a person typed can become SQL. The storage
 * adds the table, the organization and the object around these pieces; this class never writes the name of the table.
 *
 * <p>The rules that keep an indexed path and an unindexed path answering alike (the differential test holds them to
 * it): a value that is not of the kind of its field is no value (it reads as empty, never as an error); a number is
 * compared in the slot (double precision) only as a wider net and always again, exactly, in the values of the record;
 * a text is compared exactly; records without a value come last, in both directions.
 */
final class JsonbQuerySql {

    /**
     * One step of reading a page: records that meet {@code where}, in {@code order}. A page is read in one or two
     * steps: first the records that have a value in the first key, then those that have none (the index holds only
     * records with a value).
     */
    record Phase(String where, String order) {
    }

    /**
     * The pieces of a query.
     *
     * @param filter the condition of the filter, or {@code true}
     * @param sortSelect the extra columns that carry the sort values of each row (for the cursor), with a leading comma
     *        or empty
     * @param phases the steps in the order they are read
     * @param params every parameter by name
     */
    record Built(String filter, String sortSelect, List<Phase> phases, Map<String, Object> params) {
    }

    private final Map<String, Object> params = new LinkedHashMap<>();
    private final Map<String, String> fieldParams = new LinkedHashMap<>();
    private int counter;

    private JsonbQuerySql() {
    }

    /**
     * One condition on its own, for the statements that ask about a single record or a set of identifiers (the single
     * read, the lookups, the matcher): the text of the condition and its parameters. The statement around it binds
     * {@code tenant} and {@code object}, which the share condition refers to.
     *
     * @param sql the condition
     * @param params every parameter by name
     */
    record Condition(String sql, Map<String, Object> params) {
    }

    /** The condition of a node alone. */
    static Condition condition(Node node) {
        JsonbQuerySql sql = new JsonbQuerySql();
        String text = sql.node(node);
        return new Condition(text, sql.params);
    }

    /** Builds the pieces for a plan. */
    static Built build(QueryPlan plan) {
        JsonbQuerySql sql = new JsonbQuerySql();
        String filter = plan.where() == null ? "true" : sql.node(plan.where());
        return new Built(filter, sql.sortSelect(plan.order()), sql.phases(plan.order(), plan.after()), sql.params);
    }

    // ---- the filter ----

    private String node(Node node) {
        return switch (node) {
            case Group group -> group.items().stream().map(this::node)
                    .collect(Collectors.joining(group.all() ? " and " : " or ", "(", ")"));
            case Test test -> test(test);
            case OwnedByAny owned -> "owner_id = any(" + identifiers(owned.owners()) + ")";
            // The identifiers of the shared records are read once per statement and compared with the index on
            // the identifier, so this condition can be combined with the owner condition as two index scans.
            case SharedWithAny shared -> "id = any(array(select s.record_id from record_share s "
                    + "where s.tenant_id = :tenant and s.object_api_name = :object and s.deleted_at is null "
                    + "and s.grantee_id = any(" + identifiers(shared.grantees()) + ") and s.access_level >= :"
                    + bindValue(shared.level()) + "))";
            case NoRecords none -> "false";
        };
    }

    private String test(Test test) {
        Source source = test.source();
        if (source instanceof Slot slot && slot.kind() == ValueKind.NUMBER) {
            return numberInSlot(slot, test);
        }
        String expression = expression(source);
        List<String> values = test.values().stream().map(value -> cast(source, bind(value))).toList();
        return switch (test.compare()) {
            case EQ -> expression + " = " + values.get(0);
            case NE -> expression + " <> " + values.get(0);
            case GT -> expression + " > " + values.get(0);
            case GTE -> expression + " >= " + values.get(0);
            case LT -> expression + " < " + values.get(0);
            case LTE -> expression + " <= " + values.get(0);
            case BETWEEN -> expression + " between " + values.get(0) + " and " + values.get(1);
            case IN -> expression + " in (" + String.join(", ", values) + ")";
            case IS_EMPTY -> expression + " is null";
            case IS_NOT_EMPTY -> expression + " is not null";
            case STARTS_WITH -> "starts_with(" + expression + ", " + values.get(0) + ")";
            case CONTAINS -> "position(" + values.get(0) + " in " + expression + ") > 0";
        };
    }

    /**
     * A number held in a slot: the slot (double precision) narrows with the index, using the wider comparison that no
     * rounding can make miss a record (conversion to double precision never reverses the order of two numbers), and
     * the exact value in the record has the last word.
     */
    private String numberInSlot(Slot slot, Test test) {
        String column = slot.column();
        // The slot alone answers these, so no parameter for the payload is registered (nothing would use it).
        if (test.compare() == Compare.IS_EMPTY) {
            return column + " is null";
        }
        if (test.compare() == Compare.IS_NOT_EMPTY) {
            return column + " is not null";
        }
        String exact = expression(new Payload(slot.field(), ValueKind.NUMBER));
        List<String> names = test.values().stream().map(this::bind).toList();
        List<String> doubles = names.stream().map(name -> "cast(cast(:" + name + " as numeric) as double precision)")
                .toList();
        List<String> numerics = names.stream().map(name -> "cast(:" + name + " as numeric)").toList();
        return switch (test.compare()) {
            case EQ -> "(" + column + " = " + doubles.get(0) + " and " + exact + " = " + numerics.get(0) + ")";
            case NE -> exact + " <> " + numerics.get(0);
            // Rounding to double precision never reverses the order of two numbers, so a copy strictly beyond the
            // double of a bound is certainly beyond the bound itself; only a copy equal to it needs the exact value.
            case GT -> "(" + column + " >= " + doubles.get(0) + " and (" + column + " > " + doubles.get(0) + " or "
                    + exact + " > " + numerics.get(0) + "))";
            case GTE -> "(" + column + " >= " + doubles.get(0) + " and (" + column + " > " + doubles.get(0) + " or "
                    + exact + " >= " + numerics.get(0) + "))";
            case LT -> "(" + column + " <= " + doubles.get(0) + " and (" + column + " < " + doubles.get(0) + " or "
                    + exact + " < " + numerics.get(0) + "))";
            case LTE -> "(" + column + " <= " + doubles.get(0) + " and (" + column + " < " + doubles.get(0) + " or "
                    + exact + " <= " + numerics.get(0) + "))";
            case BETWEEN -> "(" + column + " between " + doubles.get(0) + " and " + doubles.get(1) + " and ("
                    + column + " > " + doubles.get(0) + " and " + column + " < " + doubles.get(1) + " or " + exact
                    + " between " + numerics.get(0) + " and " + numerics.get(1) + "))";
            case IN -> "(" + column + " in (" + String.join(", ", doubles) + ") and " + exact + " in ("
                    + String.join(", ", numerics) + "))";
            case IS_EMPTY -> column + " is null";
            case IS_NOT_EMPTY -> column + " is not null";
            case STARTS_WITH, CONTAINS -> throw new IllegalStateException("a number is not searched as text");
        };
    }

    // ---- sources and values ----

    /** The SQL value of a source: a slot column, a system column, or the value computed from the record's values. */
    private String expression(Source source) {
        return switch (source) {
            case Slot slot -> slot.column();
            case Column column -> switch (column.column()) {
                case ID -> "id";
                case OWNER -> "owner_id";
                case CREATED_AT -> "created_at";
                case CREATED_BY -> "created_by";
                case UPDATED_AT -> "updated_at";
                case UPDATED_BY -> "updated_by";
            };
            case Payload payload -> fromPayload(payload.field(), payload.kind());
        };
    }

    /** A field's value out of the record's values: null when there is none or it is not of the field's kind. */
    private String fromPayload(String field, ValueKind kind) {
        String name = fieldParams.computeIfAbsent(field, key -> {
            String parameter = "f" + fieldParams.size();
            params.put(parameter, key);
            return parameter;
        });
        String text = "(payload ->> cast(:" + name + " as text))";
        return switch (kind) {
            case TEXT -> text;
            case NUMBER -> guarded(text, "numeric", "numeric");
            case DATE -> guarded(text, "date", "date");
            case TIMESTAMP -> guarded(text, "timestamptz", "timestamptz");
            case UUID -> guarded(text, "uuid", "uuid");
            case BOOLEAN -> guarded(text, "boolean", "boolean");
        };
    }

    private static String guarded(String text, String type, String cast) {
        return "case when pg_input_is_valid(" + text + ", '" + type + "') then cast(" + text + " as " + cast + ") end";
    }

    /** A value parameter converted to the type the source holds. */
    private static String cast(Source source, String parameter) {
        return switch (source.kind()) {
            case TEXT -> "cast(:" + parameter + " as text)";
            case NUMBER -> source instanceof Slot
                    ? "cast(cast(:" + parameter + " as numeric) as double precision)"
                    : "cast(:" + parameter + " as numeric)";
            case DATE -> "cast(:" + parameter + " as date)";
            case TIMESTAMP -> "cast(:" + parameter + " as timestamptz)";
            case UUID -> "cast(:" + parameter + " as uuid)";
            case BOOLEAN -> "cast(:" + parameter + " as boolean)";
        };
    }

    private String bind(String value) {
        return bindValue(value);
    }

    private String bindValue(Object value) {
        String name = "p" + counter++;
        params.put(name, value);
        return name;
    }

    /**
     * A set of identifiers as one parameter: the text of the identifiers, converted to an array in the statement. A
     * placeholder for each would stop at the protocol's limit of parameters and give every size its own plan.
     */
    private String identifiers(java.util.List<UUID> ids) {
        String joined = ids.stream().map(UUID::toString).collect(Collectors.joining(","));
        return "cast(string_to_array(:" + bindValue(joined) + ", ',') as uuid[])";
    }

    // ---- order and keyset ----

    private String sortSelect(List<SortKey> keys) {
        StringBuilder select = new StringBuilder();
        for (int index = 0; index < keys.size(); index++) {
            select.append(", cast(").append(expression(keys.get(index).source())).append(" as text) as sort_")
                    .append(index);
        }
        return select.toString();
    }

    private List<Phase> phases(List<SortKey> keys, After after) {
        if (keys.isEmpty()) {
            if (after == null) {
                return List.of(new Phase("true", "id"));
            }
            params.put("aid", after.id());
            return List.of(new Phase("id > :aid", "id"));
        }
        boolean descending = keys.get(keys.size() - 1).descending();
        String direction = descending ? " desc" : "";
        List<String> expressions = keys.stream().map(key -> expression(key.source())).toList();
        if (after != null) {
            params.put("aid", after.id());
            for (int index = 0; index < keys.size(); index++) {
                String value = after.values().get(index);
                if (value != null) {
                    String name = "a" + index;
                    params.put(name, value);
                }
            }
        }
        List<Phase> phases = new ArrayList<>();
        boolean inValues = after == null || after.values().get(0) != null;
        if (inValues) {
            String where = expressions.get(0) + " is not null";
            if (after != null) {
                where += " and (" + afterFirst(keys, expressions, after, descending) + ")";
            }
            phases.add(new Phase(where, order(keys, expressions, 0, direction, false)));
        }
        String where = expressions.get(0) + " is null";
        if (after != null && after.values().get(0) == null) {
            where += " and (" + rest(keys, expressions, after, 1, descending) + ")";
        }
        phases.add(new Phase(where, order(keys, expressions, 1, direction, true)));
        return phases;
    }

    /** The order from key {@code from}: records without a value come last, then the identifier. */
    private static String order(List<SortKey> keys, List<String> expressions, int from, String idDirection,
            boolean nullsLastOnFirst) {
        List<String> parts = new ArrayList<>();
        for (int index = from; index < keys.size(); index++) {
            boolean first = index == from;
            parts.add(expressions.get(index) + (keys.get(index).descending() ? " desc" : "")
                    + (first && !nullsLastOnFirst && from == 0 ? "" : " nulls last"));
        }
        parts.add("id" + idDirection);
        return String.join(", ", parts);
    }

    /** After the cursor, among records that have a value in the first key. */
    private String afterFirst(List<SortKey> keys, List<String> expressions, After after, boolean idDescending) {
        String first = expressions.get(0);
        String op = keys.get(0).descending() ? "<" : ">";
        String value = cast(keys.get(0).source(), "a0");
        if (keys.size() == 1) {
            // One key: a comparison of the pair (value, identifier) is the same condition, and unlike the long form
            // the database can use it to start the index scan at the cursor instead of reading up to it.
            return "(" + first + ", id) " + op + " (" + value + ", :aid)";
        }
        return first + " " + op + " " + value + " or (" + first + " = " + value + " and "
                + rest(keys, expressions, after, 1, idDescending) + ")";
    }

    /** After the cursor from key {@code index} on: the later keys, then the identifier. */
    private String rest(List<SortKey> keys, List<String> expressions, After after, int index,
            boolean idDescending) {
        if (index == keys.size()) {
            return "id " + (idDescending ? "<" : ">") + " :aid";
        }
        String expression = expressions.get(index);
        String value = after.values().get(index);
        String later;
        String same;
        if (value == null) {
            later = "false";
            same = expression + " is null";
        } else {
            String cast = cast(keys.get(index).source(), "a" + index);
            later = "(" + expression + (keys.get(index).descending() ? " < " : " > ") + cast + " or " + expression
                    + " is null)";
            same = expression + " = " + cast;
        }
        return "(" + later + " or (" + same + " and " + rest(keys, expressions, after, index + 1, idDescending) + "))";
    }
}
