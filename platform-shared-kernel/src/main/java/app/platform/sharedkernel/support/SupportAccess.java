package app.platform.sharedkernel.support;

import app.platform.sharedkernel.TenantId;
import java.util.UUID;

/**
 * The one enforcement point of controlled support access (ADR-0035). Platform support staff have no standing access to
 * an organization: before <em>any</em> read or change of an organization's data on behalf of a platform person, the
 * code that does it must call {@link #require(TenantId, UUID)}, which refuses unless the organization itself approved a
 * request of that person and the approved window has not ended or been revoked.
 *
 * <p>The interface lives in the shared kernel, like the audit and event contracts, so that a module that reads tenant
 * data (later sprints) can ask without depending on the module that keeps the grants. Nothing in the platform reads an
 * organization's business data on behalf of support staff yet; this is the door every such read must go through.
 */
public interface SupportAccess {

    /**
     * Checks that the platform person has an active grant for the organization right now.
     *
     * @param organization the organization whose data would be touched; chosen by the (authorized) platform person,
     *         never the tenant of a request
     * @param platformUser the platform person asking
     * @throws SupportAccessDeniedException when there is no approved, unexpired, unrevoked grant
     */
    void require(TenantId organization, UUID platformUser);
}
