package app.platform.inspection.internal;

import app.platform.data.RecordTitles;
import app.platform.security.Ability;
import app.platform.security.Permissions;
import app.platform.tenant.TenantContext;
import app.platform.tenant.TenantContexts;
import app.platformapi.ApiException;
import app.platformapi.ErrorCode;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Who may do what with a template, asked in one place (FA4, ADR-0188). The cluster has no rule of its own about who
 * sees a record: a template is visible to a member exactly when the record store says so (object permission, the
 * private default, the shares), and an act on it needs the ability on top. "Not found" is the answer for a template
 * the member cannot see, an unknown identifier and a platform host alike; "forbidden" only for a member who sees the
 * template and lacks the ability, so nothing is learned about what exists.
 */
@Component
class TemplateAccess {

    private static final String NO_TEMPLATE = "This template does not exist.";

    /**
     * The member behind a request.
     *
     * @param userId the user
     * @param membershipId the membership of that user in the organization
     */
    record Caller(UUID userId, UUID membershipId) {
    }

    private final TenantContexts contexts;
    private final RecordTitles records;
    private final Permissions permissions;

    TemplateAccess(TenantContexts contexts, RecordTitles records, Permissions permissions) {
        this.contexts = contexts;
        this.records = records;
        this.permissions = permissions;
    }

    /** The member behind the request; "not found" where no member is involved (the platform host). */
    Caller member() {
        TenantContext context = contexts.current().filter(current -> current.membershipId() != null)
                .orElseThrow(() -> ApiException.notFound(NO_TEMPLATE));
        return new Caller(context.userId(), context.membershipId());
    }

    /** The member, for a template they can see. */
    Caller visible(UUID templateId) {
        Caller caller = member();
        if (templateId == null || !records.titlesOf(Templates.OBJECT, List.of(templateId)).containsKey(templateId)) {
            throw ApiException.notFound(NO_TEMPLATE);
        }
        return caller;
    }

    /** The member, for a template they can see, who holds the ability. */
    Caller require(UUID templateId, Ability ability) {
        Caller caller = visible(templateId);
        if (!has(caller, ability)) {
            throw new ApiException(ErrorCode.FORBIDDEN);
        }
        return caller;
    }

    /** The member, who holds the ability, for something that is not about one template. */
    Caller require(Ability ability) {
        Caller caller = member();
        if (!has(caller, ability)) {
            throw new ApiException(ErrorCode.FORBIDDEN);
        }
        return caller;
    }

    boolean has(Caller caller, Ability ability) {
        return permissions.has(caller.membershipId(), ability);
    }
}
