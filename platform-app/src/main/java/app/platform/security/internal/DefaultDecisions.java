package app.platform.security.internal;

import app.platform.security.Admission;
import app.platform.security.DataAccess;
import app.platform.security.Decision;
import app.platform.security.Decision.Reason;
import app.platform.security.Decisions;
import app.platform.security.FieldAction;
import app.platform.security.ObjectAccess;
import app.platform.security.ObjectAction;
import app.platform.security.ObjectCatalog;
import app.platform.security.Permissions;
import app.platform.security.RecordAction;
import app.platform.security.RecordMatcher;
import app.platform.security.RecordRef;
import app.platform.security.RecordSecurity;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * The decision API (ADR-0050): combines the member's effective permissions on data ({@link Permissions#data}) with the
 * object catalogue. It adds no cache of its own: the answer of {@link Permissions#data} may come from the security
 * cache (ADR-0053). Since Sprint 17 a record, when given, is judged by record-level security (ADR-0115 to ADR-0118):
 * the member's admission for the action is evaluated against that one record by the data module (the same statement
 * a list runs). A member who does not exist, a member without the licence of their profile, an unknown object and an
 * unknown field all end in the same "no", so the answer reveals nothing to a caller who may not know.
 */
@Service
class DefaultDecisions implements Decisions {

    private final Permissions permissions;
    private final ObjectCatalog catalogue;
    private final AccessWork work;
    private final RecordSecurity records;
    private final ObjectProvider<RecordMatcher> matcher;

    DefaultDecisions(Permissions permissions, ObjectCatalog catalogue, AccessWork work, RecordSecurity records,
            ObjectProvider<RecordMatcher> matcher) {
        this.permissions = permissions;
        this.catalogue = catalogue;
        this.work = work;
        this.records = records;
        this.matcher = matcher;
    }

    @Override
    public Decision can(UUID membershipId, String object, ObjectAction action, RecordRef record) {
        if (membershipId == null || object == null || action == null) {
            return Decision.deny(Reason.OBJECT_NOT_ALLOWED);
        }
        return work.run(() -> {
            Optional<ObjectCatalog.ObjectInfo> info = catalogue.object(object);
            if (info.isEmpty()) {
                return Decision.deny(Reason.UNKNOWN_OBJECT);
            }
            if (action == ObjectAction.DELETE && info.get().retainRecords()) {
                // The records of this object are kept: nobody may delete one, whatever they hold (ADR-0181).
                return Decision.deny(Reason.OBJECT_NOT_ALLOWED);
            }
            if (!permissions.data(membershipId).allows(object, action)) {
                return Decision.deny(Reason.OBJECT_NOT_ALLOWED);
            }
            return recordAllowed(membershipId, object, RecordAction.of(action), record);
        });
    }

    @Override
    public Decision can(UUID membershipId, String object, String field, FieldAction action, RecordRef record) {
        if (membershipId == null || object == null || field == null || action == null) {
            return Decision.deny(Reason.OBJECT_NOT_ALLOWED);
        }
        return work.run(() -> {
            Optional<ObjectCatalog.ObjectInfo> info = catalogue.object(object);
            if (info.isEmpty()) {
                return Decision.deny(Reason.UNKNOWN_OBJECT);
            }
            if (info.get().field(field).isEmpty()) {
                return Decision.deny(Reason.UNKNOWN_FIELD);
            }
            DataAccess data = permissions.data(membershipId);
            boolean objectAllows = action == FieldAction.READ ? data.allows(object, ObjectAction.READ)
                    : data.allows(object, ObjectAction.CREATE) || data.allows(object, ObjectAction.UPDATE);
            if (!objectAllows) {
                return Decision.deny(Reason.OBJECT_NOT_ALLOWED);
            }
            if (!data.allowsField(object + "." + field, action)) {
                return Decision.deny(Reason.FIELD_NOT_ALLOWED);
            }
            return recordAllowed(membershipId, object,
                    action == FieldAction.READ ? RecordAction.READ : RecordAction.EDIT, record);
        });
    }

    @Override
    public ObjectAccess accessTo(UUID membershipId, String object) {
        if (membershipId == null || object == null) {
            return ObjectAccess.none(object);
        }
        return work.run(() -> catalogue.object(object)
                .map(info -> accessOf(permissions.data(membershipId), info))
                .orElseGet(() -> ObjectAccess.none(object)));
    }

    @Override
    public Map<String, ObjectAccess> accessToAll(UUID membershipId) {
        if (membershipId == null) {
            return Map.of();
        }
        return work.run(() -> {
            DataAccess data = permissions.data(membershipId);
            Map<String, ObjectAccess> result = new LinkedHashMap<>();
            for (ObjectCatalog.ObjectInfo info : catalogue.objects()) {
                ObjectAccess access = accessOf(data, info);
                if (!access.actions().isEmpty()) {
                    result.put(info.key(), access);
                }
            }
            return result;
        });
    }

    /**
     * Whether the record is among the records the member may reach for the action. Without a record, or for an action
     * that has no record-level meaning (create), the object-level answer stands. A condition that needs the record is
     * evaluated by the data module; if it is not there to ask, the answer is "no".
     */
    private Decision recordAllowed(UUID membershipId, String object, RecordAction action, RecordRef record) {
        if (record == null || action == null) {
            return Decision.allow();
        }
        Admission admission = records.accessTo(membershipId, object).forAction(action);
        return switch (admission) {
            case Admission.Everything everything -> Decision.allow();
            case Admission.Nothing nothing -> Decision.deny(Reason.RECORD_NOT_ALLOWED);
            case Admission.AnyOf anyOf -> evaluated(object, record, admission);
            case Admission.OwnedBy ownedBy -> evaluated(object, record, admission);
            case Admission.SharedWith sharedWith -> evaluated(object, record, admission);
            case Admission.Meets meets -> evaluated(object, record, admission);
        };
    }

    private Decision evaluated(String object, RecordRef record, Admission admission) {
        RecordMatcher found = matcher.getIfAvailable();
        return found != null && found.admits(object, record.id(), admission) ? Decision.allow()
                : Decision.deny(Reason.RECORD_NOT_ALLOWED);
    }

    /** What the data allows on one object that exists: its actions and the fields that follow. */
    private static ObjectAccess accessOf(DataAccess data, ObjectCatalog.ObjectInfo info) {
        Set<ObjectAction> actions = EnumSet.noneOf(ObjectAction.class);
        for (ObjectAction action : ObjectAction.values()) {
            if (data.allows(info.key(), action) && !(action == ObjectAction.DELETE && info.retainRecords())) {
                actions.add(action);
            }
        }
        Set<String> readable = new TreeSet<>();
        Set<String> editable = new TreeSet<>();
        boolean canRead = actions.contains(ObjectAction.READ);
        boolean canWrite = actions.contains(ObjectAction.CREATE) || actions.contains(ObjectAction.UPDATE);
        for (ObjectCatalog.FieldInfo field : info.fields()) {
            String key = info.key() + "." + field.key();
            if (canRead && data.allowsField(key, FieldAction.READ)) {
                readable.add(field.key());
            }
            if (canWrite && data.allowsField(key, FieldAction.EDIT)) {
                editable.add(field.key());
            }
        }
        return new ObjectAccess(info.key(), actions, readable, editable);
    }
}
