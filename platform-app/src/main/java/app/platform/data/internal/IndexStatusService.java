package app.platform.data.internal;

import app.platform.metadata.Metadata;
import app.platform.metadata.ObjectDefinition;
import app.platform.metadata.SlotClass;
import app.platform.security.Ability;
import app.platform.security.Permissions;
import app.platform.tenant.TenantContext;
import app.platform.tenant.TenantContexts;
import app.platformapi.ApiException;
import app.platformapi.ErrorCode;
import app.platformapi.FieldIndexStatusView;
import app.platformapi.ObjectIndexStatusView;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Where the indexed fields of an object stand, for the administrators who flag them (Sprint 16, ADR-0101): flagged and
 * waiting, being filled (with how many records are done), complete, or being cleared. Needs the ability to view
 * metadata, like the object manager it belongs next to. Reads the state of the index policy; never a record.
 */
@Service
@Transactional(readOnly = true)
class IndexStatusService {

    private static final String NO_OBJECT = "This object does not exist.";

    private final Metadata metadata;
    private final IndexPolicy policy;
    private final Permissions permissions;
    private final TenantContexts contexts;

    IndexStatusService(Metadata metadata, IndexPolicy policy, Permissions permissions, TenantContexts contexts) {
        this.metadata = metadata;
        this.policy = policy;
        this.permissions = permissions;
        this.contexts = contexts;
    }

    /**
     * The status of an object's indexed fields.
     *
     * @throws ApiException {@code NOT_FOUND} on the platform host or for an object that is not held here,
     *         {@code FORBIDDEN} without the ability to view metadata
     */
    ObjectIndexStatusView statusOf(String objectApiName) {
        TenantContext context = contexts.current().filter(current -> current.membershipId() != null)
                .orElseThrow(() -> ApiException.notFound(NO_OBJECT));
        if (!permissions.has(context.membershipId(), Ability.METADATA_VIEW)) {
            throw new ApiException(ErrorCode.FORBIDDEN);
        }
        ObjectDefinition object = metadata.object(objectApiName).filter(found -> found.managedBy() == null)
                .orElseThrow(() -> ApiException.notFound(NO_OBJECT));
        List<FieldIndexStatusView> fields = policy.statusOf(object).stream()
                .map(status -> new FieldIndexStatusView(status.field(), status.state(), status.recordsDone(),
                        status.flagged())).toList();
        Map<String, Integer> slots = new LinkedHashMap<>();
        for (SlotClass kind : SlotClass.values()) {
            slots.put(kind.words(), kind.capacity());
        }
        return new ObjectIndexStatusView(object.apiName(), fields, slots);
    }
}
