package app.platform.data.internal;

import app.platform.data.StorageStrategy;
import app.platform.metadata.ObjectUsage;
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

    RecordObjectUsage(StorageStrategy storage) {
        this.storage = storage;
    }

    @Override
    public boolean hasRecords(String objectApiName) {
        return storage.hasRecords(objectApiName);
    }

    @Override
    public boolean hasValues(String objectApiName, String fieldApiName) {
        return storage.hasValues(objectApiName, fieldApiName);
    }
}
