package app.platform.architecture.fixtures;

import app.platform.data.BackgroundWrites;
import java.util.List;
import java.util.UUID;

/**
 * A class of the kind the architecture rules exist to keep out (FA4A, ADR-0187): outside the workflow and approval
 * modules, it takes the platform's own door to the records and makes the source that passes the lock of a request. It
 * is never used. The architecture tests import it on purpose, to prove that their rules fail for it; the rules that
 * guard the build do not import test classes.
 */
public final class ClusterLikeWriter {

    private final BackgroundWrites writes;

    public ClusterLikeWriter(BackgroundWrites writes) {
        this.writes = writes;
    }

    /** Writes a record as the platform, passing the lock of the request it names. */
    public void write(String object, UUID record, UUID request) {
        writes.update(BackgroundWrites.Source.approvalHook("Process__c", request, null), object, record, List.of());
    }
}
