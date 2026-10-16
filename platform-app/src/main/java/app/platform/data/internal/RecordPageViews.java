package app.platform.data.internal;

import app.platform.data.DataRecord;
import app.platform.data.Records;
import app.platform.metadata.DefinitionKind;
import app.platform.metadata.FieldConfiguration.ReferenceConfiguration;
import app.platform.metadata.FieldDefinition;
import app.platform.metadata.FieldType;
import app.platform.metadata.Metadata;
import app.platform.metadata.ObjectDefinition;
import app.platform.metadata.RecordPage;
import app.platform.metadata.RecordPageResolution;
import app.platform.metadata.Tab;
import app.platform.security.Ability;
import app.platform.security.MemberAccess;
import app.platform.security.ObjectAccess;
import app.platform.security.ObjectAction;
import app.platform.security.Permissions;
import app.platformapi.RecordPageActionView;
import app.platformapi.RecordPageBlockView;
import app.platformapi.RecordPageDescriptorView;
import app.platformapi.RecordPageHeaderView;
import app.platformapi.RecordPageTabView;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What a screen needs to draw the record page of one object for the signed-in member (Sprint 20, ADR-0131): the
 * header, the tabs and the actions, each already filtered to what the caller may reach, the same way
 * {@link ObjectForms} does for the fields of a form. Renders a {@link RecordPage} that
 * {@link Metadata#recordPageFor} resolves; when none resolves the platform's own minimal page is described instead
 * (a header and one {@code DETAILS} tab), so every object still has a working record view with no configuration.
 *
 * <p>A block the caller may not reach (a related object they cannot read, a tab whose custom page is gone) is left
 * out of its tab exactly as {@code MyApplications} leaves out an entry the caller may not open; a tab left with no
 * block is left out of the page. Nothing here reads more of a record's values than {@link Records#get} already
 * gives for the caller: a field they may not read never reaches a visibility rule.
 */
@Service
@Transactional(readOnly = true)
class RecordPageViews {

    private static final List<String> DEFAULT_ACTIONS = List.of("EDIT", "DELETE", "CLONE", "SHARE");
    private static final int DEFAULT_LIMIT = 5;
    private static final int MAX_LIMIT = 50;
    private static final int MAX_DERIVED_LISTS = 10;
    private static final int DERIVED_COLUMNS = 4;
    private static final int SUMMARY_FIELDS = 4;
    private static final Set<FieldType> TOO_WIDE = Set.of(FieldType.LONG_TEXT, FieldType.MULTI_PICKLIST);

    /** The parts of a page's body: blocks before the tab strip, the tabs, blocks after it and the side region. */
    private record Body(List<RecordPageBlockView> above, List<RecordPageTabView> tabs,
            List<RecordPageBlockView> below, List<RecordPageBlockView> sidebar) {
    }

    private final RecordGate gate;
    private final FieldSecurity security;
    private final Metadata metadata;
    private final MemberAccess members;
    private final Permissions permissions;
    private final Records records;

    RecordPageViews(RecordGate gate, FieldSecurity security, Metadata metadata, MemberAccess members,
            Permissions permissions, Records records) {
        this.gate = gate;
        this.security = security;
        this.metadata = metadata;
        this.members = members;
        this.permissions = permissions;
        this.records = records;
    }

    /**
     * Describes the record page for the member.
     *
     * @param recordId the record it is open on, or null for a create form
     * @throws app.platformapi.ApiException {@code NOT_FOUND} for an object the member may not read or that does not
     *         exist, or a record they cannot reach
     */
    RecordPageDescriptorView describe(String objectApiName, String recordType, UUID recordId) {
        RecordGate.Passed passed = gate.pass(objectApiName, ObjectAction.READ);
        ObjectDefinition object = passed.object();
        ObjectAccess access = security.accessOf(passed.membershipId(), object.apiName());
        UUID profile = members.profileOf(passed.membershipId()).orElse(null);
        DataRecord record = recordId == null ? null : records.get(object.apiName(), recordId);
        String type = recordType != null ? recordType : record == null ? null : record.recordType();
        Predicate<String> hasAbility = key -> Ability.fromKey(key)
                .map(ability -> permissions.has(passed.membershipId(), ability)).orElse(false);

        RecordPageResolution resolution = metadata.recordPageFor(object.apiName(), profile, type);
        RecordPage page = resolution.recordPage()
                .flatMap(name -> metadata.recordPages(object.apiName()).stream()
                        .filter(candidate -> candidate.apiName().equals(name)).findFirst())
                .orElse(null);

        Map<String, Object> values = record == null ? null : record.fields();
        RecordPageHeaderView header = header(page, object, access);
        Body body = page == null ? defaultBody(object, passed.membershipId())
                : body(page, passed.membershipId(), hasAbility, values);
        List<RecordPageActionView> actions = actions(page, access, hasAbility, values);
        return new RecordPageDescriptorView(page == null ? null : page.apiName(), layoutOf(object, page), header,
                body.above(), body.tabs(), body.below(), body.sidebar(), actions);
    }

    /** The layout the page names, when it still exists; otherwise the member's own resolution decides. */
    private String layoutOf(ObjectDefinition object, RecordPage page) {
        if (page == null || !(page.definition().get("layout") instanceof String name)) {
            return null;
        }
        return metadata.layouts(object.apiName()).stream().anyMatch(layout -> layout.apiName().equals(name)) ? name
                : null;
    }

    // ---- header ----

    private RecordPageHeaderView header(RecordPage page, ObjectDefinition object, ObjectAccess access) {
        Map<?, ?> found = page == null ? null : component(page, "HEADER");
        List<String> highlights = new ArrayList<>();
        String title = null;
        if (found != null && found.get("titleField") instanceof String field
                && access.readableFields().contains(field)) {
            title = field;
        }
        if (found != null && found.get("highlightFields") instanceof List<?> fields) {
            for (Object field : fields) {
                if (field instanceof String name && access.readableFields().contains(name)) {
                    highlights.add(name);
                }
            }
        }
        if (title == null) {
            title = TitleFields.of(object, access.readableFields()).map(FieldDefinition::apiName).orElse(null);
        }
        if (page == null) {
            highlights.addAll(summary(object, access.readableFields(), title));
        }
        return new RecordPageHeaderView(title, highlights);
    }

    // ---- body ----

    /** The platform's own page: the details, and one list for each link another object has to this one. */
    private Body defaultBody(ObjectDefinition object, UUID membershipId) {
        List<RecordPageTabView> tabs = new ArrayList<>();
        tabs.add(new RecordPageTabView("Content", List.of(details("Details"))));
        List<RecordPageBlockView> related = derivedRelatedLists(object, membershipId);
        if (!related.isEmpty()) {
            tabs.add(new RecordPageTabView("Related Records", related));
        }
        return new Body(List.of(), tabs, List.of(), List.of());
    }

    private static RecordPageBlockView details(String label) {
        return new RecordPageBlockView("DETAILS", label, null, null, null, null, null, null, null);
    }

    private Body body(RecordPage page, UUID membershipId, Predicate<String> hasAbility, Map<String, Object> values) {
        List<RecordPageBlockView> above = new ArrayList<>();
        List<RecordPageTabView> tabs = new ArrayList<>();
        List<RecordPageBlockView> below = new ArrayList<>();
        List<RecordPageBlockView> sidebar = new ArrayList<>();
        boolean seenTabs = false;
        if (page.definition().get("components") instanceof List<?> components) {
            for (Object raw : components) {
                if (!(raw instanceof Map<?, ?> component)
                        || !RecordPageVisibility.visible(component.get("visibleWhen"), hasAbility, values)) {
                    continue;
                }
                if ("TABS".equals(component.get("type"))) {
                    tabs.addAll(tabsOf(component, membershipId, hasAbility, values));
                    seenTabs = true;
                } else {
                    Optional<RecordPageBlockView> block = block(component, membershipId);
                    if ("SIDEBAR".equals(component.get("region"))) {
                        block.ifPresent(sidebar::add);
                    } else {
                        block.ifPresent(seenTabs ? below::add : above::add);
                    }
                }
            }
        }
        return new Body(above, tabs, below, sidebar);
    }

    private List<RecordPageTabView> tabsOf(Map<?, ?> tabsComponent, UUID membershipId, Predicate<String> hasAbility,
            Map<String, Object> values) {
        if (!(tabsComponent.get("tabs") instanceof List<?> rawTabs)) {
            return List.of();
        }
        List<RecordPageTabView> result = new ArrayList<>();
        for (Object rawTab : rawTabs) {
            if (!(rawTab instanceof Map<?, ?> tab) || !(tab.get("components") instanceof List<?> rawComponents)) {
                continue;
            }
            List<RecordPageBlockView> blocks = new ArrayList<>();
            for (Object rawComponent : rawComponents) {
                if (rawComponent instanceof Map<?, ?> component
                        && RecordPageVisibility.visible(component.get("visibleWhen"), hasAbility, values)) {
                    block(component, membershipId).ifPresent(blocks::add);
                }
            }
            if (!blocks.isEmpty()) {
                String label = tab.get("label") instanceof String text ? text : "Tab";
                result.add(new RecordPageTabView(label, blocks));
            }
        }
        return result;
    }

    private Optional<RecordPageBlockView> block(Map<?, ?> component, UUID membershipId) {
        String type = component.get("type") instanceof String text ? text : "";
        String label = component.get("label") instanceof String text ? text : null;
        return switch (type) {
            case "DETAILS" -> Optional.of(details(label == null ? "Details" : label));
            case "RELATED_LIST" -> relatedList(component, label, membershipId);
            case "TAB" -> customTab(component, label);
            default -> Optional.empty();
        };
    }

    private Optional<RecordPageBlockView> relatedList(Map<?, ?> component, String label, UUID membershipId) {
        String relatedObject = component.get("object") instanceof String text ? text : null;
        String field = component.get("field") instanceof String text ? text : null;
        if (relatedObject == null || field == null) {
            return Optional.empty();
        }
        int limit = component.get("limit") instanceof Number number
                ? Math.max(1, Math.min(MAX_LIMIT, number.intValue())) : DEFAULT_LIMIT;
        return metadata.object(relatedObject).map(related -> {
            ObjectAccess relatedAccess = security.accessOf(membershipId, relatedObject);
            List<String> columns = new ArrayList<>();
            if (component.get("columns") instanceof List<?> rawColumns) {
                for (Object column : rawColumns) {
                    if (column instanceof String name && relatedAccess.readableFields().contains(name)) {
                        columns.add(name);
                    }
                }
            }
            // The link itself is a field the list is filtered on: one the caller may not read does not exist for them.
            if (!relatedAccess.can(ObjectAction.READ) || columns.isEmpty()
                    || !relatedAccess.readableFields().contains(field)) {
                return null;
            }
            return new RecordPageBlockView("RELATED_LIST", label == null ? related.pluralLabel() : label,
                    relatedObject, field, columns, null, null, null, limit);
        }).filter(Objects::nonNull);
    }

    /** One list for each link another object the caller may read has to this one (the platform's own page). */
    private List<RecordPageBlockView> derivedRelatedLists(ObjectDefinition object, UUID membershipId) {
        List<RecordPageBlockView> result = new ArrayList<>();
        for (ObjectDefinition related : metadata.objects()) {
            if (related.managedBy() != null || result.size() >= MAX_DERIVED_LISTS) {
                continue;
            }
            ObjectAccess access = security.accessOf(membershipId, related.apiName());
            if (!access.can(ObjectAction.READ)) {
                continue;
            }
            List<FieldDefinition> links = related.fields().stream()
                    .filter(field -> !field.retired() && field.configuration() instanceof ReferenceConfiguration link
                            && link.targetObject().equals(object.apiName())
                            && access.readableFields().contains(field.apiName()))
                    .toList();
            for (FieldDefinition link : links) {
                List<String> columns = columns(related, access.readableFields(), link);
                if (!columns.isEmpty() && result.size() < MAX_DERIVED_LISTS) {
                    String label = links.size() > 1 ? related.pluralLabel() + " (" + link.label() + ")"
                            : related.pluralLabel();
                    result.add(new RecordPageBlockView("RELATED_LIST", label, related.apiName(), link.apiName(),
                            columns, null, null, null, DEFAULT_LIMIT));
                }
            }
        }
        return result;
    }

    /** The name of a record first, then a few fields that fit a column, never the link the list is built on. */
    private static List<String> columns(ObjectDefinition related, Set<String> readable, FieldDefinition link) {
        List<String> columns = new ArrayList<>();
        TitleFields.of(related, readable).map(FieldDefinition::apiName).ifPresent(columns::add);
        for (FieldDefinition field : related.fields()) {
            if (columns.size() >= DERIVED_COLUMNS) {
                break;
            }
            if (!columns.contains(field.apiName()) && !field.apiName().equals(link.apiName())
                    && fits(field, readable)) {
                columns.add(field.apiName());
            }
        }
        return columns;
    }

    /** A few fields to show under the title of the platform's own header card. */
    private static List<String> summary(ObjectDefinition object, Set<String> readable, String title) {
        List<String> names = new ArrayList<>();
        for (FieldDefinition field : object.fields()) {
            if (names.size() >= SUMMARY_FIELDS) {
                break;
            }
            if (!field.apiName().equals(title) && fits(field, readable)) {
                names.add(field.apiName());
            }
        }
        if (names.size() < SUMMARY_FIELDS && readable.contains("ownerId") && !"ownerId".equals(title)) {
            names.add("ownerId");
        }
        return names;
    }

    /** A field a person or a standard definition made, readable, and short enough to sit in a cell. */
    private static boolean fits(FieldDefinition field, Set<String> readable) {
        return field.kind() != DefinitionKind.SYSTEM && !field.retired() && readable.contains(field.apiName())
                && !TOO_WIDE.contains(field.type());
    }

    private Optional<RecordPageBlockView> customTab(Map<?, ?> component, String label) {
        String tabApiName = component.get("tab") instanceof String text ? text : null;
        if (tabApiName == null) {
            return Optional.empty();
        }
        return metadata.tabs().stream().filter(tab -> tab.apiName().equals(tabApiName))
                .filter(tab -> tab.kind() == Tab.Kind.CUSTOM).findFirst()
                .flatMap(tab -> metadata.customPages().stream()
                        .filter(page -> page.apiName().equals(tab.customPage())).findFirst()
                        .map(page -> new RecordPageBlockView("TAB", label == null ? tab.label() : label, null, null,
                                null, tab.apiName(), page.apiName(), page.assetId(), null)));
    }

    // ---- actions ----

    private List<RecordPageActionView> actions(RecordPage page, ObjectAccess access, Predicate<String> hasAbility,
            Map<String, Object> values) {
        List<?> configured = page == null ? null : (List<?>) page.definition().get("actions");
        List<RecordPageActionView> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        if (configured != null) {
            for (Object raw : configured) {
                if (raw instanceof Map<?, ?> action && action.get("action") instanceof String kind
                        && allowed(kind, access) && RecordPageVisibility.visible(action.get("visibleWhen"),
                                hasAbility, values) && seen.add(kind)) {
                    String label = action.get("label") instanceof String text ? text : defaultLabel(kind);
                    result.add(new RecordPageActionView(kind, label));
                }
            }
            return result;
        }
        for (String kind : DEFAULT_ACTIONS) {
            if (allowed(kind, access)) {
                result.add(new RecordPageActionView(kind, defaultLabel(kind)));
            }
        }
        return result;
    }

    private static boolean allowed(String action, ObjectAccess access) {
        return switch (action) {
            case "EDIT", "SHARE" -> access.can(ObjectAction.UPDATE);
            case "DELETE" -> access.can(ObjectAction.DELETE);
            case "CLONE" -> access.can(ObjectAction.CREATE);
            default -> false;
        };
    }

    private static String defaultLabel(String action) {
        return switch (action) {
            case "EDIT" -> "Edit";
            case "DELETE" -> "Delete";
            case "CLONE" -> "Clone";
            case "SHARE" -> "Share";
            default -> action;
        };
    }

    // ---- reading the definition ----

    /** The one component of the kind in the page's top-level components, or null. */
    private static Map<?, ?> component(RecordPage page, String kind) {
        if (!(page.definition().get("components") instanceof List<?> components)) {
            return null;
        }
        for (Object component : components) {
            if (component instanceof Map<?, ?> map && kind.equals(map.get("type"))) {
                return map;
            }
        }
        return null;
    }
}
