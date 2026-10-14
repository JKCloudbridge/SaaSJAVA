package app.platform.data.internal;

import app.platform.metadata.Metadata;
import app.platform.metadata.ObjectDefinition;
import app.platform.security.Decisions;
import app.platform.security.ObjectAction;
import app.platform.tenant.TenantContext;
import app.platform.tenant.TenantContexts;
import app.platformapi.ApiException;
import app.platformapi.ErrorCode;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The one place every read and every write of a record passes before anything is touched (ADR-0089). Since Sprint 15
 * the write pipeline, which holds field security, runs after it (ADR-0091); since Sprint 17A record-level security
 * sits between the two for a change of an existing record ({@link RecordPermission}, ADR-0125). Nothing in the
 * record store reads or writes a record without a {@link Passed} from this class.
 *
 * <p>What it decides, in this order:
 * <ol> <li>Who asks: the member behind the request, from the tenant context (the host and the token, never the
 * request). Where no member is involved (the platform host) the answer is "not found".</li> <li>Whether the object
 * exists in the organization and is one the generic store holds: a standard object that a platform module owns (a user,
 * a profile, an access policy ...) keeps its rows in that module and is not served here.</li> <li>Whether the member
 * may read the object. A member who may not, and an object that does not exist or is not held here, get <strong>the
 * same answer</strong>, so nobody learns what exists.</li> <li>Whether the member may do the action. A member who may
 * read but not do this gets "forbidden": they know the object exists, because they can read it.</li>
 * </ol>
 */
@Component
class RecordGate {

    private static final String NO_OBJECT = "This object does not exist.";

    /**
     * What a call may go on with.
     *
     * @param object the object, from the metadata catalogue (the only source of an object name that reaches storage)
     * @param userId the user who acts
     * @param membershipId the membership of that user in the organization
     */
    record Passed(ObjectDefinition object, UUID userId, UUID membershipId) {
    }

    private final Metadata metadata;
    private final Decisions decisions;
    private final TenantContexts contexts;

    RecordGate(Metadata metadata, Decisions decisions, TenantContexts contexts) {
        this.metadata = metadata;
        this.decisions = decisions;
        this.contexts = contexts;
    }

    /**
     * Lets a call through or refuses it.
     *
     * @throws ApiException {@code NOT_FOUND} (the platform host, an unknown object, an object a platform module owns,
     *        an object the member may not read), {@code FORBIDDEN} (a member who may read but not do the action)
     */
    Passed pass(String objectApiName, ObjectAction action) {
        TenantContext context = contexts.current().filter(current -> current.membershipId() != null)
                .orElseThrow(() -> ApiException.notFound(NO_OBJECT));
        ObjectDefinition object = metadata.object(objectApiName).filter(found -> found.managedBy() == null)
                .orElseThrow(() -> ApiException.notFound(NO_OBJECT));
        if (!decisions.can(context.membershipId(), object.apiName(), ObjectAction.READ).allowed()) {
            throw ApiException.notFound(NO_OBJECT);
        }
        if (action != ObjectAction.READ && !decisions.can(context.membershipId(), object.apiName(), action).allowed()) {
            throw new ApiException(ErrorCode.FORBIDDEN);
        }
        return new Passed(object, context.userId(), context.membershipId());
    }
}
