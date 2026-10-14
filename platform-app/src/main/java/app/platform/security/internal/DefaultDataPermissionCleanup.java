package app.platform.security.internal;

import app.platform.security.DataPermissionCleanup;
import app.platform.sharedkernel.ActorId;
import org.springframework.stereotype.Component;

/**
 * Ends the permission lines of a removed object or field (ADR-0062) in the transaction of the removal, and, since
 * Sprint 17, the sharing setup of a removed object: its organization-wide default and its sharing rules (ADR-0116). The
 * counts are written to the audit trail when there was anything to end; the key is an API name: a name, not typed
 * text.
 */
@Component
class DefaultDataPermissionCleanup implements DataPermissionCleanup {

    private final DataAccessStore store;
    private final SharingStore sharing;
    private final AccessAudit audit;

    DefaultDataPermissionCleanup(DataAccessStore store, SharingStore sharing, AccessAudit audit) {
        this.store = store;
        this.sharing = sharing;
        this.audit = audit;
    }

    @Override
    public int forgetObject(String objectApiName, ActorId actor) {
        int lines = store.deleteForObject(objectApiName, actor);
        if (lines > 0) {
            audit.dataPermissionsForgotten(actorOrNull(actor), objectApiName, null, lines);
        }
        int rules = sharing.deleteRulesOfObject(objectApiName, actor);
        int settings = sharing.deleteSettingsOfObject(objectApiName, actor);
        if (rules + settings > 0) {
            audit.sharingForgotten(actorOrNull(actor), objectApiName, settings, rules);
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
