package app.platform.data.internal;

import app.platform.metadata.ObjectDefinition;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/**
 * The seam for the typed copies of indexed fields ("slots", ADR-0084, ADR-0091). The write pipeline calls
 * {@link #copyIndexed} once per create and per update, with the values the record will have, after every check has
 * passed and before the record is stored. Sprint 15 assigns no slot, so the answer is always empty and the storage
 * statements do not mention the slot columns.
 *
 * <p>Sprint 16 plugs in here: it answers, for a field flagged "indexed", which slot column holds a copy and what to
 * copy, and then adds the eleven slot parameters to the two statements that write a record ({@code INSERT} and
 * {@code UPDATE} in {@code JsonbRecordStorage}) so that the copy is written in the same statement as the payload. A
 * test proves the seam is called, so the plug cannot be forgotten.
 */
@Component
class RecordSlots {

    private final AtomicLong calls = new AtomicLong();

    /**
     * The slot columns to write with the record, by column name; empty until the index policy of Sprint 16 exists.
     *
     * @param object the object of the record
     * @param values the values the record will have after the write, by field API name (never logged)
     */
    Map<String, Object> copyIndexed(ObjectDefinition object, Map<String, Object> values) {
        calls.incrementAndGet();
        return Map.of();
    }

    /** How many times the seam was called (tests). */
    long calls() {
        return calls.get();
    }
}
