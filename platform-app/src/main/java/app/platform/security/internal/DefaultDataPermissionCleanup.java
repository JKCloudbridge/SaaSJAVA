package app.platform.security.internal;

import app.platform.security.DataPermissionCleanup;
import app.platform.sharedkernel.ActorId;
import org.springframework.stereotype.Component;

/**
 * Ends the permission lines of a removed object or field (ADR-0062) in the transaction of the removal. The count is
 * written to the audit trail when there was anything to end; the key is an API name: a name, not typed text.
 */
@Component
class DefaultDataPermissionCleanup implements DataPermissionCleanup {

    private final DataAccessStore store;
    private final AccessAudit audit;

    DefaultDataPermissionCleanup(DataAccessStore store, AccessAudit audit) {
        this.store = store;
        this.audit = audit;
    }

    @Override
    public int forgetObject(String objectApiName, ActorId actor) {
        int lines = store.deleteForObject(objectApiName, actor);
        if (lines > 0) {
            audit.dataPermissionsForgotten(actorOrNull(actor), objectApiName, null, lines);
        }
        return lines;
    }

    @Override
    public int forgetField(String objectApiName, String fieldApiName, ActorId actor) {
        int lines = store.deleteForField(objectApiName + "." + fieldApiName, actor);
        if (lines > 0) {
            audit.dataPermissionsForgotten(actorOrNull(actor), objectApiName, fieldApiName, lines);
        }
        return lines;
    }

    private static java.util.UUID actorOrNull(ActorId actor) {
        return actor.equals(ActorId.SYSTEM) ? null : actor.value();
    }
}
