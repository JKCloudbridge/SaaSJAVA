package app.platform.application.internal;

import app.platform.metadata.ApplicationDefinition;
import app.platform.metadata.ApplicationDefinition.NavigationItem;
import app.platform.metadata.Metadata;
import app.platform.metadata.RecordPage;
import app.platform.security.MemberAccess;
import app.platform.tenant.TenantContext;
import app.platform.tenant.TenantContexts;
import app.platformapi.ApiException;
import app.platformapi.MyApplicationView;
import app.platformapi.NavigationEntryView;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Which applications the signed-in member sees and what their navigation shows (Sprint 12, ADR-0073). The browser
 * builds its menu from this and hardcodes none of it (architecture note 3, section 15).
 *
 * <p>The member is the one the host and the token name, never one a request names. An application is shown when it
 * lists the member's profile or lists no profile at all; the profile decides what is <em>offered</em>, not what is
 * <em>allowed</em>: every page a link opens is still decided by the permissions of that page. An item that points to
 * something that no longer exists is left out (the dependency graph keeps that from being published, so this is a
 * second line of defence). Showing only what the member may read is the security-aware navigation of Sprint 18.
 */
@Service
class MyApplications {

    private final Metadata metadata;
    private final MemberAccess access;
    private final TenantContexts contexts;

    MyApplications(Metadata metadata, MemberAccess access, TenantContexts contexts) {
        this.metadata = metadata;
        this.access = access;
        this.contexts = contexts;
    }

    /**
     * The applications the caller may use, each with its resolved navigation.
     *
     * @throws ApiException {@code NOT_FOUND} where no member is involved (the platform address)
     */
    @Transactional(readOnly = true)
    List<MyApplicationView> mine() {
        TenantContext context = contexts.current().filter(current -> current.membershipId() != null)
                .orElseThrow(() -> ApiException.notFound("This is not available at this address."));
        UUID profile = access.profileOf(context.membershipId()).orElse(null);
        List<MyApplicationView> visible = new ArrayList<>();
        for (ApplicationDefinition application : metadata.applications()) {
            if (application.visibleTo(profile)) {
                visible.add(new MyApplicationView(application.apiName(), application.label(),
                        application.iconKey().isEmpty() ? null : application.iconKey(), entries(application)));
            }
        }
        return visible;
    }

    private List<NavigationEntryView> entries(ApplicationDefinition application) {
        List<NavigationEntryView> entries = new ArrayList<>();
        for (NavigationItem item : application.items()) {
            switch (item.type()) {
                case LINK -> entries.add(new NavigationEntryView("LINK", item.label(), null, null, item.path()));
                case OBJECT -> metadata.object(item.objectApiName()).ifPresent(object -> entries.add(
                        new NavigationEntryView("OBJECT", labelOf(item, object.pluralLabel()), object.apiName(), null,
                                null)));
                case RECORD_PAGE -> page(item).ifPresent(page -> entries.add(new NavigationEntryView(
                        "RECORD_PAGE", labelOf(item, page.label()), page.objectApiName(), page.apiName(), null)));
                default -> entries.add(new NavigationEntryView("HEADING", item.label(), null, null, null));
            }
        }
        return entries;
    }

    private Optional<RecordPage> page(NavigationItem item) {
        return metadata.recordPages(item.objectApiName()).stream()
                .filter(page -> page.apiName().equals(item.pageApiName())).findFirst();
    }

    private static String labelOf(NavigationItem item, String fallback) {
        return item.label().isEmpty() ? fallback : item.label();
    }
}
