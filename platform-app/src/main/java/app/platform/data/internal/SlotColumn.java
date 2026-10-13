package app.platform.data.internal;

import app.platform.metadata.SlotClass;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * The twenty-two slot columns of {@code object_record} (migration V041), in the order a field takes them: the lowest
 * free slot of its kind first. The only place that lists the column names; every statement that mentions a slot gets
 * its column from here, never from metadata or a request.
 */
enum SlotColumn {

    TEXT_1("s_text_1", SlotClass.TEXT), TEXT_2("s_text_2", SlotClass.TEXT), TEXT_3("s_text_3", SlotClass.TEXT),
    TEXT_4("s_text_4", SlotClass.TEXT), TEXT_5("s_text_5", SlotClass.TEXT), TEXT_6("s_text_6", SlotClass.TEXT),
    TEXT_7("s_text_7", SlotClass.TEXT), TEXT_8("s_text_8", SlotClass.TEXT),
    NUM_1("s_num_1", SlotClass.NUMBER), NUM_2("s_num_2", SlotClass.NUMBER), NUM_3("s_num_3", SlotClass.NUMBER),
    NUM_4("s_num_4", SlotClass.NUMBER),
    DATE_1("s_date_1", SlotClass.DATE), DATE_2("s_date_2", SlotClass.DATE),
    TS_1("s_ts_1", SlotClass.TIMESTAMP), TS_2("s_ts_2", SlotClass.TIMESTAMP),
    REF_1("s_ref_1", SlotClass.REFERENCE), REF_2("s_ref_2", SlotClass.REFERENCE),
    REF_3("s_ref_3", SlotClass.REFERENCE), REF_4("s_ref_4", SlotClass.REFERENCE),
    BOOL_1("s_bool_1", SlotClass.BOOLEAN), BOOL_2("s_bool_2", SlotClass.BOOLEAN);

    private final String column;
    private final SlotClass slotClass;

    SlotColumn(String column, SlotClass slotClass) {
        this.column = column;
        this.slotClass = slotClass;
    }

    /** The name of the column. */
    String column() {
        return column;
    }

    /** The kind of value the column holds. */
    SlotClass slotClass() {
        return slotClass;
    }

    /** The slots of a kind, lowest first. */
    static List<SlotColumn> of(SlotClass kind) {
        return Arrays.stream(values()).filter(slot -> slot.slotClass == kind).toList();
    }

    /** The slot with this column name, if it is one. */
    static Optional<SlotColumn> byColumn(String column) {
        return Arrays.stream(values()).filter(slot -> slot.column.equals(column)).findFirst();
    }

    /**
     * The SQL that turns the text of a parameter into the value the column holds. Numbers go through {@code numeric}
     * so that the write path, the backfill and a query all convert the same way (ADR-0102).
     */
    String fromParameter(String parameter) {
        return switch (slotClass) {
            case TEXT -> "cast(:" + parameter + " as text)";
            case NUMBER -> "cast(cast(:" + parameter + " as numeric) as double precision)";
            case DATE -> "cast(:" + parameter + " as date)";
            case TIMESTAMP -> "cast(:" + parameter + " as timestamptz)";
            case REFERENCE -> "cast(:" + parameter + " as uuid)";
            case BOOLEAN -> "cast(:" + parameter + " as boolean)";
        };
    }

    /**
     * The SQL that computes the column's value from the payload's text {@code payload ->> :field}: null when the
     * field has no value or the stored value is not of the column's kind (a record written before the types were
     * checked), never an error.
     */
    String fromPayload(String fieldParameter) {
        String text = "(payload ->> cast(:" + fieldParameter + " as text))";
        return switch (slotClass) {
            case TEXT -> text;
            case NUMBER -> "case when pg_input_is_valid(" + text + ", 'numeric') then cast(cast(" + text
                    + " as numeric) as double precision) end";
            case DATE -> "case when pg_input_is_valid(" + text + ", 'date') then cast(" + text + " as date) end";
            case TIMESTAMP -> "case when pg_input_is_valid(" + text + ", 'timestamptz') then cast(" + text
                    + " as timestamptz) end";
            case REFERENCE -> "case when pg_input_is_valid(" + text + ", 'uuid') then cast(" + text + " as uuid) end";
            case BOOLEAN -> "case when pg_input_is_valid(" + text + ", 'boolean') then cast(" + text
                    + " as boolean) end";
        };
    }
}
