package app.platform.security.internal;

import app.platform.security.DataAccess;
import app.platform.security.Decision;
import app.platform.security.Decision.Reason;
import app.platform.security.Decisions;
import app.platform.security.FieldAction;
import app.platform.security.ObjectAccess;
import app.platform.security.ObjectAction;
import app.platform.security.ObjectCatalog;
import app.platform.security.Permissions;
import app.platform.security.RecordRef;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * The decision API (ADR-0050): combines the member's effective permissions on data ({@link Permissions#data}) with the
 * object catalogue. It adds no cache of its own: the answer of {@link Permissions#data} may come from the security
 * cache (ADR-0053). The record is accepted and not used until Sprint 17. A member who does not exist, a member without
 * the licence of their profile, an unknown object and an unknown field all end in the same "no", so the answer reveals
 * nothing to a caller who may not know.
 */
@Service
class DefaultDecisions implements Decisions {

    private final Permissions permissions;
    private final ObjectCatalog catalogue;
    private final AccessWork work;

    DefaultDecisions(Permissions permissions, ObjectCatalog catalogue, AccessWork work) {
        this.permissions = permissions;
        this.catalogue = catalogue;
        this.work = work;
    }

    @Override
    public Decision can(UUID membershipId, String object, ObjectAction action, RecordRef record) {
        if (membershipId == null || object == null || action == null) {
            return Decision.deny(Reason.OBJECT_NOT_ALLOWED);
        }
        return work.run(() -> {
            if (catalogue.object(object).isEmpty()) {
                return Decision.deny(Reason.UNKNOWN_OBJECT);
            }
            return permissions.data(membershipId).allows(object, action) ? Decision.allow()
                    : Decision.deny(Reason.OBJECT_NOT_ALLOWED);
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
            return data.allowsField(object + "." + field, action) ? Decision.allow()
                    : Decision.deny(Reason.FIELD_NOT_ALLOWED);
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

    /** What the data allows on one object that exists: its actions and the fields that follow. */
    private static ObjectAccess accessOf(DataAccess data, ObjectCatalog.ObjectInfo info) {
        Set<ObjectAction> actions = EnumSet.noneOf(ObjectAction.class);
        for (ObjectAction action : ObjectAction.values()) {
            if (data.allows(info.key(), action)) {
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
