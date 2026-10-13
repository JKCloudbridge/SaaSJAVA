package app.platform.data;

import java.util.Map;

/**
 * The typed copies of a record's indexed fields that are written in the same statement as the record itself (Sprint
 * 16, ADR-0102), by slot column name ({@code s_text_1} ...), and which uniqueness constraints the record takes part in
 * (Sprint 16A, ADR-0111). A column that is not in the map is written as empty: a record never keeps a copy the write
 * did not compute.
 *
 * <p>The values are all text: the stored form of the field value (a number as its decimal text, a day, a time with a
 * zone, an identifier, true or false). The database converts them to the column's type, the same way it converts the
 * value when it fills a slot for existing records, so a write and a backfill can never disagree. Nothing in here is
 * ever logged: it is a copy of what a person typed.
 *
 * @param columns the copies by column name, as text
 * @param uniqueMask the constraints the record takes part in, one bit each: the database refuses a second live
 *        record of the object that holds the same value in a constraint whose bit it carries too (V043)
 * @param uniquePlaces where each constraint of the mask is answered if the database refuses the write: the API name
 *        of the field (the second master of a junction pair), by the number of the bit
 */
public record SlotCopy(Map<String, String> columns, int uniqueMask, Map<Integer, String> uniquePlaces) {

    private static final SlotCopy NONE = new SlotCopy(Map.of(), 0, Map.of());

    /** Copies what is given. */
    public SlotCopy {
        columns = Map.copyOf(columns);
        uniquePlaces = Map.copyOf(uniquePlaces);
    }

    /** Copies without any uniqueness constraint. */
    public SlotCopy(Map<String, String> columns) {
        this(columns, 0, Map.of());
    }

    /** No copies: nothing is indexed. */
    public static SlotCopy none() {
        return NONE;
    }

    /** Prints the column names only. */
    @Override
    public String toString() {
        return "SlotCopy" + columns.keySet() + " mask " + uniqueMask;
    }
}
