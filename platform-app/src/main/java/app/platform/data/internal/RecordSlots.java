package app.platform.data.internal;

import app.platform.data.SlotCopy;
import app.platform.metadata.FieldDefinition;
import app.platform.metadata.ObjectDefinition;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/**
 * The typed copies of indexed fields ("slots", ADR-0084, ADR-0091, ADR-0102). The write pipeline calls
 * {@link #copyIndexed} once per create and per update, with the values the record will have, after every check has
 * passed and before the record is stored; a restore calls it with the values the record holds. The answer travels to
 * the storage and is written in the same statement as the record.
 *
 * <p>A field gets a copy while its slot is being filled <em>and</em> once it is complete: a record written while the
 * records that already existed are being filled must be right, or the fill would never be able to call itself done. A
 * slot that is being emptied (the field is no longer indexed) is not copied: the write leaves it empty.
 *
 * <p>The call also takes, for the rest of the transaction, the shared lock of the object that makes a change of the
 * assignments wait for the writes in flight (ADR-0101). A test proves the seam is called, so the plug cannot be
 * forgotten.
 */
@Component
class RecordSlots {

    private final FieldSlotStore store;
    private final AtomicLong calls = new AtomicLong();

    RecordSlots(FieldSlotStore store) {
        this.store = store;
    }

    /**
     * The copies to write with the record.
     *
     * @param object the object of the record
     * @param values the values the record will have after the write, by field API name (never logged)
     */
    SlotCopy copyIndexed(ObjectDefinition object, Map<String, Object> values) {
        calls.incrementAndGet();
        List<FieldSlotStore.Row> active = store.activeForWrite(object.apiName());
        if (active.isEmpty()) {
            return SlotCopy.none();
        }
        Map<String, String> copies = new HashMap<>();
        for (FieldSlotStore.Row row : active) {
            FieldDefinition field = object.field(row.fieldApiName()).orElse(null);
            String text = field == null ? null : textOf(values.get(row.fieldApiName()));
            if (text != null) {
                copies.put(row.slot().column(), text);
            }
        }
        return new SlotCopy(copies);
    }

    /**
     * The stored value as the text the database converts to the column's type; null when the field has no value. The
     * text is what {@code payload ->> field} gives for the same value, so a write and a backfill agree.
     */
    static String textOf(Object value) {
        return switch (value) {
            case null -> null;
            case String text -> text;
            case BigDecimal number -> number.toString();
            case Number number -> new BigDecimal(number.toString()).toString();
            case Boolean flag -> flag.toString();
            default -> null;
        };
    }

    /** How many times the seam was called (tests). */
    long calls() {
        return calls.get();
    }
}
