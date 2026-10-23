package app.platform.sheets.internal;

import app.platform.sheets.internal.FillValues.Kind;
import app.platform.sheets.internal.FillValues.Value;
import app.platform.tenant.TenantContexts;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The values of the reportable columns of fill-ins (FF2, ADR-0193): the only class that names the table. Every
 * statement is made of fixed fragments chosen by the kind of the column and the shape of the condition, with the
 * organization, the sheet, the column and every value as parameters; no text of a person is ever part of a statement.
 * The kind is written into the statement as a literal because the indexes are partial on it, and a parameter would keep
 * the planner from using them.
 *
 * <p>What a person may see is not decided here: a batch is only the fill-ins that hold a value, newest first.
 */
@Repository
class FillValueStore {

    /** How a condition compares, once its type is known. */
    enum Shape {
        EQUALS, ONE_OF, FROM, TO, BOTH
    }

    private static final String DELETE = "delete from sheet_fill_value where tenant_id = :tenant "
            + "and fill_object = :object and fill_id = :fill";

    // One round trip for all the rows of a save: the values travel as parallel arrays and are typed in the database.
    private static final String INSERT = "insert into sheet_fill_value (tenant_id, fill_object, fill_id, "
            + "owner_object, owner_id, version_no, row_id, column_id, kind, text_value, number_value, date_value, "
            + "boolean_value, created_by, updated_by) select :tenant, :object, :fill, :ownerObject, :owner, :no, "
            + "v.row_id, v.column_id, v.kind, v.text_value, cast(v.number_value as numeric), "
            + "cast(v.date_value as date), cast(v.boolean_value as boolean), :actor, :actor from "
            + "unnest(cast(:rows as text[]), cast(:columns as text[]), cast(:kinds as text[]), "
            + "cast(:texts as text[]), cast(:numbers as text[]), cast(:dates as text[]), cast(:flags as text[])) "
            + "as v(row_id, column_id, kind, text_value, number_value, date_value, boolean_value)";

    private static final String COUNT = "select count(*) from sheet_fill_value where tenant_id = :tenant "
            + "and fill_object = :object and fill_id = :fill";

    private final JdbcClient jdbc;
    private final TenantContexts contexts;

    FillValueStore(JdbcClient jdbc, TenantContexts contexts) {
        this.jdbc = jdbc;
        this.contexts = contexts;
    }

    /**
     * Replaces the values of one fill-in by these, in the caller's transaction (none removes them). The old copy is
     * deleted as a whole and the new one inserted, so the copy is never a mixture of two saves.
     */
    void replace(String fillObject, UUID fill, String ownerObject, UUID owner, int versionNo, List<Value> values,
            UUID actor) {
        jdbc.sql(DELETE).param("tenant", tenant()).param("object", fillObject).param("fill", fill).update();
        if (values.isEmpty()) {
            return;
        }
        List<String> rows = new ArrayList<>(values.size());
        List<String> columns = new ArrayList<>(values.size());
        List<String> kinds = new ArrayList<>(values.size());
        List<String> texts = new ArrayList<>(values.size());
        List<String> numbers = new ArrayList<>(values.size());
        List<String> dates = new ArrayList<>(values.size());
        List<String> flags = new ArrayList<>(values.size());
        for (Value value : values) {
            rows.add(value.rowId());
            columns.add(value.columnId());
            kinds.add(value.kind().name());
            texts.add(value.text());
            numbers.add(value.number() == null ? null : value.number().toPlainString());
            dates.add(value.date() == null ? null : value.date().toString());
            flags.add(value.flag() == null ? null : value.flag().toString());
        }
        jdbc.sql(INSERT).param("tenant", tenant()).param("object", fillObject).param("fill", fill)
                .param("ownerObject", ownerObject).param("owner", owner).param("no", versionNo)
                .param("rows", array(rows)).param("columns", array(columns)).param("kinds", array(kinds))
                .param("texts", array(texts)).param("numbers", array(numbers)).param("dates", array(dates))
                .param("flags", array(flags)).param("actor", actor).update();
    }

    /** How many values are kept for a fill-in (a test and the archive's check; reads no value). */
    long count(String fillObject, UUID fill) {
        return jdbc.sql(COUNT).param("tenant", tenant()).param("object", fillObject).param("fill", fill)
                .query(Long.class).single();
    }

    /**
     * The fill-ins of one sheet version that hold a value meeting the condition in the column, newest first, each once,
     * after the given one.
     *
     * @param shape how the condition compares
     * @param first the value of EQUALS, the lower bound of FROM or BOTH, or null
     * @param many the values of ONE_OF, or empty
     * @param last the upper bound of TO or BOTH, or null
     * @param after the last header of the previous batch, or null
     */
    List<UUID> find(String fillObject, String ownerObject, UUID owner, int versionNo, String columnId, Kind kind,
            Shape shape, String first, List<String> many, String last, UUID after, int limit) {
        JdbcClient.StatementSpec spec = jdbc.sql(sql(kind, shape, after != null)).param("tenant", tenant())
                .param("object", fillObject).param("ownerObject", ownerObject).param("owner", owner)
                .param("no", versionNo).param("column", columnId).param("limit", limit);
        switch (shape) {
            case EQUALS -> spec = spec.param("v", first);
            case ONE_OF -> spec = spec.param("vs", array(many));
            case FROM -> spec = spec.param("lo", first);
            case TO -> spec = spec.param("hi", last);
            case BOTH -> spec = spec.param("lo", first).param("hi", last);
            default -> throw new IllegalArgumentException("Unknown shape");
        }
        if (after != null) {
            spec = spec.param("after", after);
        }
        return spec.query(UUID.class).list();
    }

    // ---- the statements ----

    static String sql(Kind kind, Shape shape, boolean after) {
        return "select fill_id from sheet_fill_value where tenant_id = :tenant and fill_object = :object "
                + "and owner_object = :ownerObject and owner_id = :owner and version_no = :no "
                + "and column_id = :column and kind = '" + kind.name() + "' and " + predicate(kind, shape)
                + (after ? " and fill_id < :after" : "") + " group by fill_id order by fill_id desc limit :limit";
    }

    private static String predicate(Kind kind, Shape shape) {
        String column = column(kind);
        return switch (shape) {
            case EQUALS -> column + " = " + cast(kind, ":v");
            case ONE_OF -> column + " = any(" + castArray(kind, ":vs") + ")";
            case FROM -> column + " >= " + cast(kind, ":lo");
            case TO -> column + " <= " + cast(kind, ":hi");
            case BOTH -> column + " >= " + cast(kind, ":lo") + " and " + column + " <= " + cast(kind, ":hi");
            default -> throw new IllegalArgumentException("Unknown shape");
        };
    }

    private static String column(Kind kind) {
        return switch (kind) {
            case TEXT -> "text_value";
            case NUMBER -> "number_value";
            case DATE -> "date_value";
            case BOOLEAN -> "boolean_value";
            default -> throw new IllegalArgumentException("Unknown kind");
        };
    }

    private static String cast(Kind kind, String parameter) {
        return switch (kind) {
            case TEXT -> parameter;
            case NUMBER -> "cast(" + parameter + " as numeric)";
            case DATE -> "cast(" + parameter + " as date)";
            case BOOLEAN -> "cast(" + parameter + " as boolean)";
            default -> throw new IllegalArgumentException("Unknown kind");
        };
    }

    private static String castArray(Kind kind, String parameter) {
        return switch (kind) {
            case TEXT -> "cast(" + parameter + " as text[])";
            case NUMBER -> "cast(" + parameter + " as numeric[])";
            case DATE -> "cast(" + parameter + " as date[])";
            case BOOLEAN -> "cast(" + parameter + " as boolean[])";
            default -> throw new IllegalArgumentException("Unknown kind");
        };
    }

    /** A list of texts as the text of a text array: the elements are quoted and escaped, and null is NULL. */
    static String array(List<String> values) {
        StringBuilder text = new StringBuilder("{");
        boolean firstOne = true;
        for (String value : values) {
            if (!firstOne) {
                text.append(',');
            }
            firstOne = false;
            if (value == null) {
                text.append("NULL");
            } else {
                text.append('"').append(value.replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
            }
        }
        return text.append('}').toString();
    }

    private UUID tenant() {
        return contexts.require().tenantId().value();
    }
}
