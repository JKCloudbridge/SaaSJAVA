package app.platform.data.internal;

import app.platform.data.DataRecord;
import app.platform.metadata.DefinitionKind;
import app.platform.metadata.FieldDefinition;
import app.platform.metadata.ObjectDefinition;
import app.platform.security.Decisions;
import app.platform.security.ObjectAccess;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Field-level security of the record store (ADR-0092): what the member may change when a record is written and what
 * they may see when a record is read. The answer is the one {@link Decisions} gives (a union of the profile, the access
 * policies and the individual grants; edit implies read), asked once per request in its bulk form, never once per
 * field.
 *
 * <p><strong>A field the member may not read does not exist for them.</strong> A write that names such a field gets the
 * answer for a field that is not there, and a read leaves the field out (no placeholder: the key is absent, as for a
 * field with no value). A write that names a field the member may read but not edit is refused with that reason,
 * because they can already see the field exists. The whole write is refused, not just the field: a record is never
 * saved with part of what was asked.
 */
@Component
class FieldSecurity {

    static final String NOT_A_FIELD = "This is not a field of the object.";
    static final String NOT_EDITABLE = "You may not change this field.";
    static final String NOT_WRITABLE = "This field cannot be written.";

    private final Decisions decisions;

    FieldSecurity(Decisions decisions) {
        this.decisions = decisions;
    }

    /** What the member may do with the object and its fields, in one answer. */
    ObjectAccess accessOf(UUID membershipId, String objectApiName) {
        return decisions.accessTo(membershipId, objectApiName);
    }

    /**
     * The field-permission step of a write: every field named (a value or a clear) must be one the member may edit and
     * one that people can write.
     *
     * @param named the fields the write names
     * @return the fields that passed, in the order given
     */
    List<String> checkWrite(ObjectDefinition object, ObjectAccess access, Collection<String> named,
            Findings findings) {
        List<String> passed = new ArrayList<>();
        for (String key : named) {
            FieldDefinition field = object.field(key).orElse(null);
            if (field == null || !access.editableFields().contains(key)) {
                findings.add(key, field != null && access.readableFields().contains(key) ? NOT_EDITABLE : NOT_A_FIELD);
            } else if (field.kind() == DefinitionKind.SYSTEM || field.type().calculated() || field.retired()) {
                findings.add(key, NOT_WRITABLE);
            } else {
                passed.add(key);
            }
        }
        return passed;
    }

    /**
     * The place a problem about a field is reported at. A field the member may not read is not named (the answer must
     * not reveal that it exists), so the problem is about the record as a whole.
     */
    static String place(ObjectAccess access, String fieldApiName) {
        return access.readableFields().contains(fieldApiName) ? fieldApiName : RecordValues.PROBLEMS_KEY;
    }

    /** The record as the member may see it: the fields they may not read are left out. */
    static DataRecord visible(DataRecord record, ObjectAccess access) {
        Map<String, Object> kept = new LinkedHashMap<>();
        record.fields().forEach((name, value) -> {
            if (access.readableFields().contains(name)) {
                kept.put(name, value);
            }
        });
        return record.withFields(kept);
    }

    /** The records as the member may see them. */
    static List<DataRecord> visible(List<DataRecord> records, ObjectAccess access) {
        return records.stream().map(record -> visible(record, access)).toList();
    }
}
