package app.platform.application.internal;

import app.platform.metadata.ApplicationDefinition;
import app.platform.metadata.ApplicationDefinition.NavigationItem;
import app.platform.metadata.CustomPage;
import app.platform.metadata.Metadata;
import app.platform.metadata.RecordPage;
import app.platform.metadata.Tab;
import app.platform.security.Decisions;
import app.platform.security.MemberAccess;
import app.platform.security.ObjectAccess;
import app.platform.security.ObjectAction;
import app.platform.sharedkernel.storage.ObjectStore;
import app.platform.tenant.TenantContext;
import app.platform.tenant.TenantContexts;
import app.platformapi.ApiException;
import app.platformapi.MyApplicationView;
import app.platformapi.NavigationEntryView;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Which applications the signed-in member sees and what their navigation shows (Sprint 12, ADR-0073; made
 * security-aware in Sprint 18, ADR-0128). The browser builds its menu from this and hardcodes none of it (architecture
 * note 3, section 15).
 *
 * <p>The member is the one the host and the token name, never one a request names. An application is shown when it
 * lists the member's profile or lists no profile at all. Inside it, an entry is listed only when the member may open
 * it: an object or record page entry needs the permission to read the object (the same answer the data interface
 * gives, so an object the member may not read is left out exactly as one that does not exist), and a tab entry needs
 * the tab and the custom page it shows to exist. A heading that would lead no entry is left out, and so is an
 * application that had entries and has none left. An item that points to something that no longer exists is left
 * out too (the dependency graph keeps that from being published, so this is a second line of defence).
 *
 * <p>This is a convenience for the person, never the check: every page, record and file is decided again by its own
 * endpoint on every request.
 */
@Service
class MyApplications {

    private final Metadata metadata;
    private final MemberAccess access;
    private final Decisions decisions;
    private final ObjectStore objects;
    private final TenantContexts contexts;

    MyApplications(Metadata metadata, MemberAccess access, Decisions decisions, ObjectStore objects,
            TenantContexts contexts) {
        this.metadata = metadata;
        this.access = access;
        this.decisions = decisions;
        this.objects = objects;
        this.contexts = contexts;
    }

    /**
     * The applications the caller may use, each with its resolved navigation.
     *
     * @throws ApiException {@code NOT_FOUND} where no member is involved (the platform address)
     */
    @Transactional(readOnly = true)
    List<MyApplicationView> mine() {
        TenantContext context = member();
        UUID profile = access.profileOf(context.membershipId()).orElse(null);
        Map<String, ObjectAccess> readable = decisions.accessToAll(context.membershipId());
        boolean scriptsAvailable = objects.configured();
        List<MyApplicationView> visible = new ArrayList<>();
        for (ApplicationDefinition application : metadata.applications()) {
            if (!application.visibleTo(profile)) {
                continue;
            }
            List<NavigationEntryView> entries = entries(application, readable, scriptsAvailable);
            boolean leadsAnywhere = entries.stream().anyMatch(entry -> !"HEADING".equals(entry.type()));
            if (application.items().isEmpty() || leadsAnywhere) {
                visible.add(new MyApplicationView(application.apiName(), application.label(),
                        application.iconKey().isEmpty() ? null : application.iconKey(), entries));
            }
        }
        return visible;
    }

    /**
     * Whether the signed-in member may open the custom page: some application they see lists a tab that shows it.
     * Sprint 20 adds the record pages whose tabs show a custom page here, and nowhere else.
     */
    @Transactional(readOnly = true)
    Optional<CustomPage> openable(String customPageApiName) {
        TenantContext context = member();
        UUID profile = access.profileOf(context.membershipId()).orElse(null);
        Optional<CustomPage> page = metadata.customPages().stream()
                .filter(candidate -> candidate.apiName().equals(customPageApiName)).findFirst();
        if (page.isEmpty()) {
            return Optional.empty();
        }
        boolean listed = metadata.applications().stream().filter(application -> application.visibleTo(profile))
                .flatMap(application -> application.items().stream())
                .filter(item -> item.type() == ApplicationDefinition.ItemType.TAB)
                .map(item -> metadata.tabs().stream().filter(tab -> tab.apiName().equals(item.tabApiName()))
                        .findFirst())
                .flatMap(Optional::stream)
                .anyMatch(tab -> tab.kind() == Tab.Kind.CUSTOM && tab.customPage().equals(customPageApiName));
        return listed ? page : Optional.empty();
    }

    private TenantContext member() {
        return contexts.current().filter(current -> current.membershipId() != null)
                .orElseThrow(() -> ApiException.notFound("This is not available at this address."));
    }

    private List<NavigationEntryView> entries(ApplicationDefinition application, Map<String, ObjectAccess> readable,
            boolean scriptsAvailable) {
        List<NavigationEntryView> entries = new ArrayList<>();
        for (NavigationItem item : application.items()) {
            switch (item.type()) {
                case LINK -> entries.add(new NavigationEntryView("LINK", item.label(), null, null, item.path()));
                case OBJECT -> metadata.object(item.objectApiName()).filter(object -> mayRead(readable, object.apiName()))
                        .ifPresent(object -> entries.add(new NavigationEntryView("OBJECT",
                                labelOf(item, object.pluralLabel()), object.apiName(), null, null)));
                case RECORD_PAGE -> page(item).filter(page -> mayRead(readable, page.objectApiName()))
                        .ifPresent(page -> entries.add(new NavigationEntryView("RECORD_PAGE",
                                labelOf(item, page.label()), page.objectApiName(), page.apiName(), null)));
                case TAB -> tab(item, scriptsAvailable).ifPresent(entries::add);
                default -> entries.add(new NavigationEntryView("HEADING", item.label(), null, null, null));
            }
        }
        return withoutEmptyHeadings(entries);
    }

    private static boolean mayRead(Map<String, ObjectAccess> readable, String object) {
        ObjectAccess access = readable.get(object);
        return access != null && access.can(ObjectAction.READ);
    }

    private Optional<NavigationEntryView> tab(NavigationItem item, boolean scriptsAvailable) {
        Optional<Tab> tab = metadata.tabs().stream().filter(candidate -> candidate.apiName().equals(item.tabApiName()))
                .findFirst().filter(candidate -> candidate.kind() == Tab.Kind.CUSTOM);
        Optional<CustomPage> page = tab.flatMap(found -> metadata.customPages().stream()
                .filter(candidate -> candidate.apiName().equals(found.customPage())).findFirst());
        if (tab.isEmpty() || page.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new NavigationEntryView("CUSTOM", labelOf(item, tab.get().label()), null, null, null,
                tab.get().apiName(), page.get().apiName(), page.get().assetId(), scriptsAvailable));
    }

    private Optional<RecordPage> page(NavigationItem item) {
        return metadata.recordPages(item.objectApiName()).stream()
                .filter(page -> page.apiName().equals(item.pageApiName())).findFirst();
    }

    /** A heading stays only when an entry that is not a heading follows it before the next heading. */
    private static List<NavigationEntryView> withoutEmptyHeadings(List<NavigationEntryView> entries) {
        List<NavigationEntryView> kept = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            NavigationEntryView entry = entries.get(i);
            if (!"HEADING".equals(entry.type())) {
                kept.add(entry);
                continue;
            }
            boolean leads = i + 1 < entries.size() && !"HEADING".equals(entries.get(i + 1).type());
            if (leads) {
                kept.add(entry);
            }
        }
        return kept;
    }

    private static String labelOf(NavigationItem item, String fallback) {
        return item.label().isEmpty() ? fallback : item.label();
    }
}
