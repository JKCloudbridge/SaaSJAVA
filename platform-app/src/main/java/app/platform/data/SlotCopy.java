package app.platform.data;

import java.util.Map;

/**
 * The typed copies of a record's indexed fields that are written in the same statement as the record itself (Sprint
 * 16, ADR-0102), by slot column name ({@code s_text_1} ...). A column that is not in the map is written as empty: a
 * record never keeps a copy the write did not compute.
 *
 * <p>The values are all text: the stored form of the field value (a number as its decimal text, a day, a time with a
 * zone, an identifier, true or false). The database converts them to the column's type, the same way it converts the
 * value when it fills a slot for existing records, so a write and a backfill can never disagree. Nothing in here is
 * ever logged: it is a copy of what a person typed.
 *
 * @param columns the copies by column name, as text
 */
public record SlotCopy(Map<String, String> columns) {

    private static final SlotCopy NONE = new SlotCopy(Map.of());

    /** Copies what is given. */
    public SlotCopy {
        columns = Map.copyOf(columns);
    }

    /** No copies: nothing is indexed. */
    public static SlotCopy none() {
        return NONE;
    }

    /** Prints the column names only. */
    @Override
    public String toString() {
        return "SlotCopy" + columns.keySet();
    }
}
