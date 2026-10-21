package app.platform.data.internal;

import app.platform.data.StorageStrategy;
import app.platform.metadata.FieldDefinition;
import app.platform.metadata.Metadata;
import app.platform.metadata.ObjectUsage;
import app.platform.metadata.SlotClass;
import app.platformapi.ApiException;
import app.platformapi.ErrorCode;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.stereotype.Component;

/**
 * The answer to the question the metadata module has asked since Sprint 11: does the object still have records, does a
 * field still hold values (ADR-0062, ADR-0067, ADR-0090). Until this class existed nothing answered, so nothing was
 * ever kept from being removed.
 *
 * <p><strong>The recycle bin counts.</strong> A record in the recycle bin is still a record: it can be restored, and a
 * restored record of a removed object, or one holding a value in a removed field, would be a record the metadata can no
 * longer explain. An object or a field becomes removable when its last record is removed for good, by a person or by
 * the clean-up of the recycle bin. The question is answered for the tenant context and in the transaction of the
 * caller, so a removal and the check that allows it see the same rows.
 *
 * <p>A field "holds a value" when a record has a key of that name: the store never keeps an empty value (a cleared
 * field loses its key).
 */
@Component
class RecordObjectUsage implements ObjectUsage {

    private final StorageStrategy storage;
    private final Metadata metadata;

    RecordObjectUsage(StorageStrategy storage, Metadata metadata) {
        this.storage = storage;
        this.metadata = metadata;
    }

    @Override
    public boolean hasRecords(String objectApiName) {
        return storage.hasRecords(objectApiName);
    }

    @Override
    public boolean hasValues(String objectApiName, String fieldApiName) {
        return storage.hasValues(objectApiName, fieldApiName);
    }

    @Override
    public long recordsOutsidePairs(String objectApiName, String dependentField, String controllingField,
            Map<String, List<String>> allowed) {
        if (!storage.hasValues(objectApiName, dependentField)) {
            return 0;
        }
        try {
            return storage.recordsOutsidePairs(objectApiName, dependentField, controllingField, allowed);
        } catch (QueryTimeoutException tooSlow) {
            throw new ApiException(ErrorCode.CONFLICT, "The records could not be checked in the time allowed. Try "
                    + "again when the organization is less busy.");
        }
    }

    /**
     * Reads the stored values (the field may have no complete copy yet), comparing them the way the slot of the field's
     * kind does. An object nobody holds records of has none; a field of a kind that cannot be unique has none either.
     */
    @Override
    public long duplicates(String objectApiName, String fieldApiName) {
        FieldDefinition field = metadata.object(objectApiName).flatMap(object -> object.field(fieldApiName))
                .orElse(null);
        Optional<SlotClass> kind = field == null ? Optional.empty() : SlotClass.of(field.type());
        if (kind.isEmpty() || !storage.hasValues(objectApiName, fieldApiName)) {
            return 0;
        }
        String slotColumn = SlotColumn.of(kind.get()).get(0).column();
        try {
            return storage.duplicateRecords(objectApiName, fieldApiName, slotColumn);
        } catch (QueryTimeoutException tooSlow) {
            throw new ApiException(ErrorCode.CONFLICT, "The records could not be checked for duplicates in the time "
                    + "allowed. Try again when the organization is less busy.");
        }
    }
}
