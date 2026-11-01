package app.platform.data.internal;

import app.platform.data.ObjectDescriptions;
import app.platform.metadata.Metadata;
import app.platform.metadata.ObjectDefinition;
import app.platform.security.Decisions;
import app.platform.security.ObjectAccess;
import app.platform.security.ObjectAction;
import app.platform.tenant.TenantContext;
import app.platform.tenant.TenantContexts;
import app.platformapi.ApiException;
import app.platformapi.ObjectFormView;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The description of objects and fields for the public API (Sprint 30, ADR-0245). The list is the objects of the
 * organization that the record store holds and that the member may read, judged by the decision API like every other
 * read; one object is described by {@link ObjectForms}, the code that tells a screen what it may draw, so a field the
 * member may not read is absent here as it is on every read and the comparisons each field takes are the ones the
 * query engine will accept (never a copy of that list).
 *
 * <p>Nothing here reads a record, so nothing a person typed is logged or audited.
 */
@Service
@Transactional(readOnly = true)
class DefaultObjectDescriptions implements ObjectDescriptions {

    private final Metadata metadata;
    private final Decisions decisions;
    private final TenantContexts contexts;
    private final ObjectForms forms;

    DefaultObjectDescriptions(Metadata metadata, Decisions decisions, TenantContexts contexts, ObjectForms forms) {
        this.metadata = metadata;
        this.decisions = decisions;
        this.contexts = contexts;
        this.forms = forms;
    }

    @Override
    public List<Described> readable() {
        TenantContext context = contexts.current().filter(current -> current.membershipId() != null)
                .orElseThrow(() -> ApiException.notFound("This object does not exist."));
        Map<String, ObjectAccess> access = decisions.accessToAll(context.membershipId());
        return metadata.objects().stream()
                .filter(object -> object.managedBy() == null)
                .filter(object -> access.containsKey(object.apiName())
                        && access.get(object.apiName()).can(ObjectAction.READ))
                .map(object -> described(object, access.get(object.apiName())))
                .sorted((left, right) -> left.apiName().compareTo(right.apiName()))
                .toList();
    }

    @Override
    public ObjectFormView describe(String objectApiName) {
        return forms.describe(objectApiName, null, null);
    }

    private static Described described(ObjectDefinition object, ObjectAccess access) {
        return new Described(object.apiName(), object.label(), object.pluralLabel(), object.kind().name(),
                access.can(ObjectAction.CREATE), access.can(ObjectAction.UPDATE), access.can(ObjectAction.DELETE));
    }
}
