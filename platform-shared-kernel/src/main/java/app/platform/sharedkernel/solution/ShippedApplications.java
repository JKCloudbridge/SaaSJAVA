package app.platform.sharedkernel.solution;

import java.util.List;
import java.util.UUID;

/**
 * The applications the platform itself ships, as the member's menu merges them with the organization's own
 * (Functional track FA1, ADR-0175, ADR-0181). The module that loads the shipped solutions implements it; the module
 * that builds the menu asks it through an optional bean, so the menu has no dependency on solutions (the inversion of
 * {@code RecordLocks}).
 *
 * <p>An implementation answers for the organization of the thread's tenant context. It lists a shipped application
 * only while the organization has the plan feature of its solution, asked at the moment of the call and never stored,
 * and it writes no row of the organization for the application, its entries or its pages. Whether the member may open
 * an entry is not decided here: the menu judges every entry that names an object by the member's read permission, with
 * the rule it applies to the organization's own applications.
 */
public interface ShippedApplications {

    /**
     * The shipped applications that exist for the organization of the current context.
     *
     * @param membershipId the membership of the member who asks (a shipped solution may keep its policies in step on
     *        the way)
     */
    List<ShippedApplicationView> forMember(UUID membershipId);
}
