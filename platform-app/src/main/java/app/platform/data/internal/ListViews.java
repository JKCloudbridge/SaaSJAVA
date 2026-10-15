package app.platform.data.internal;

import app.platform.data.QueryFilter;
import app.platform.data.QuerySort;
import app.platform.data.RecordQuery;
import app.platform.security.Ability;
import app.platform.security.ObjectAccess;
import app.platform.security.ObjectAction;
import app.platform.security.Permissions;
import app.platformapi.ApiException;
import app.platformapi.CreateListViewRequest;
import app.platformapi.ErrorCode;
import app.platformapi.ListViewDefinition;
import app.platformapi.ListViewView;
import app.platformapi.UpdateListViewRequest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * The saved list views of an object (Sprint 19, ADR-0130): a filter, an order and a choice of columns kept under a
 * name, so the same list can be opened again.
 *
 * <p><strong>A view is data, and it grants nothing.</strong> Opening a view runs its definition as an ordinary record
 * query for whoever opens it, so the object permission, the field permissions and record-level security of the person
 * looking decide what the list shows, exactly as for a list nobody saved. A shared view cannot show a person a record
 * or a field they could not reach another way.
 *
 * <p>Who may do what:
 * <ul>
 * <li>Anyone who may read the object may save a <em>personal</em> view and change or remove their own.</li>
 * <li>A <em>shared</em> view (everyone who may read the object sees it) is made, changed and removed only by a member
 * who holds the ability {@link Ability#VIEWS_SHARE}, whoever made it. Only the person who made a shared view can turn
 * it back into a personal one, because it would otherwise vanish from everyone but them.</li>
 * <li>A personal view of someone else, and a view of an object the member may not read, answer as a view that does not
 * exist.</li>
 * </ul>
 *
 * <p>At save time the definition is checked by the same first three stages the query engine runs (shape, fields,
 * operators and values), against what the <em>saver</em> may read, so a view that could never run is refused with the
 * places that are wrong. Nothing is executed and no record is read. Names and labels are text a person typed, so the
 * audit records carry the view's identifier and scope only.
 */
@Service
@Transactional
class ListViews {

    static final String PERSONAL = "PERSONAL";
    static final String SHARED = "SHARED";

    private static final String NO_VIEW = "This list view does not exist.";

    private final JsonMapper json = JsonMapper.builder().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .build();

    private final RecordGate gate;
    private final FieldSecurity security;
    private final QueryAnalyzer analyzer;
    private final ListViewStore store;
    private final Permissions permissions;
    private final RecordChanges changes;
    private final ListViewProperties properties;

    ListViews(RecordGate gate, FieldSecurity security, QueryAnalyzer analyzer, ListViewStore store,
            Permissions permissions, RecordChanges changes, ListViewProperties properties) {
        this.gate = gate;
        this.security = security;
        this.analyzer = analyzer;
        this.store = store;
        this.permissions = permissions;
        this.changes = changes;
        this.properties = properties;
    }

    /** The views of the object this member sees: the shared ones, then their own. */
    @Transactional(readOnly = true)
    List<ListViewView> list(String objectApiName) {
        RecordGate.Passed passed = gate.pass(objectApiName, ObjectAction.READ);
        return store.visibleTo(passed.object().apiName(), passed.userId()).stream()
                .map(row -> view(row, passed.userId())).toList();
    }

    ListViewView create(String objectApiName, CreateListViewRequest body) {
        RecordGate.Passed passed = gate.pass(objectApiName, ObjectAction.READ);
        String scope = scope(body.scope());
        String label = label(body.label());
        if (SHARED.equals(scope)) {
            requireShareRight(passed);
        }
        String definition = checked(passed, body.definition());
        requireRoom(passed, scope);
        ListViewStore.Row row;
        try {
            row = store.insert(passed.object().apiName(), label, scope, definition, passed.userId());
        } catch (DuplicateKeyException e) {
            throw taken();
        }
        changes.viewCreated(passed.userId(), passed.object().apiName(), row.id(), scope);
        return view(row, passed.userId());
    }

    ListViewView update(String objectApiName, UUID id, UpdateListViewRequest body) {
        RecordGate.Passed passed = gate.pass(objectApiName, ObjectAction.READ);
        ListViewStore.Row current = reachable(passed, id);
        String scope = scope(body.scope());
        String label = label(body.label());
        boolean mine = current.createdBy().equals(passed.userId());
        if (SHARED.equals(current.scope()) || SHARED.equals(scope)) {
            requireShareRight(passed);
        }
        if (SHARED.equals(current.scope()) && PERSONAL.equals(scope) && !mine) {
            throw ApiException.validation("scope", "Only the person who made a view can make it personal.");
        }
        String definition = checked(passed, body.definition());
        if (!scope.equals(current.scope())) {
            requireRoom(passed, scope);
        }
        ListViewStore.Row changed;
        try {
            changed = store.update(passed.object().apiName(), id, body.version(), label, scope, definition,
                    passed.userId()).orElseThrow(() -> new ApiException(ErrorCode.CONCURRENT_MODIFICATION));
        } catch (DuplicateKeyException e) {
            throw taken();
        }
        changes.viewChanged(passed.userId(), passed.object().apiName(), id, scope, changed.version());
        return view(changed, passed.userId());
    }

    void delete(String objectApiName, UUID id, long version) {
        RecordGate.Passed passed = gate.pass(objectApiName, ObjectAction.READ);
        ListViewStore.Row current = reachable(passed, id);
        if (SHARED.equals(current.scope())) {
            requireShareRight(passed);
        }
        if (!store.delete(passed.object().apiName(), id, version, passed.userId())) {
            throw new ApiException(ErrorCode.CONCURRENT_MODIFICATION);
        }
        changes.viewRemoved(passed.userId(), passed.object().apiName(), id, current.scope());
    }

    // ---- rules ----

    /** The view, when this member may see it; a personal view of another person is a view that does not exist. */
    private ListViewStore.Row reachable(RecordGate.Passed passed, UUID id) {
        return store.find(passed.object().apiName(), id)
                .filter(row -> SHARED.equals(row.scope()) || row.createdBy().equals(passed.userId()))
                .orElseThrow(() -> ApiException.notFound(NO_VIEW));
    }

    private void requireShareRight(RecordGate.Passed passed) {
        if (!permissions.has(passed.membershipId(), Ability.VIEWS_SHARE)) {
            throw new ApiException(ErrorCode.FORBIDDEN);
        }
    }

    private void requireRoom(RecordGate.Passed passed, String scope) {
        long held = store.count(passed.object().apiName(), scope, passed.userId());
        int most = SHARED.equals(scope) ? properties.maxSharedPerObject() : properties.maxPersonalPerObject();
        if (held >= most) {
            throw new ApiException(ErrorCode.CONFLICT, SHARED.equals(scope)
                    ? "This object has the most shared list views it can have. Remove one first."
                    : "You have the most personal list views you can have for this object. Remove one first.");
        }
    }

    private static String scope(String given) {
        if (PERSONAL.equals(given) || SHARED.equals(given)) {
            return given;
        }
        throw ApiException.validation("scope", "Choose PERSONAL or SHARED.");
    }

    private static String label(String given) {
        String label = given == null ? "" : given.strip();
        if (label.isEmpty() || label.length() > 80) {
            throw ApiException.validation("label", "Give the view a name of 1 to 80 characters.");
        }
        return label;
    }

    private static ApiException taken() {
        return new ApiException(ErrorCode.CONFLICT, "A list view with this name already exists.");
    }

    /**
     * The definition as the JSON text to store, after the first three stages of the query engine accepted it for what
     * the saver may read. Problems are reported at {@code definition.<place>}.
     */
    private String checked(RecordGate.Passed passed, ListViewDefinition definition) {
        ObjectAccess access = security.accessOf(passed.membershipId(), passed.object().apiName());
        List<String> columns = definition.columns();
        if (columns.stream().distinct().count() != columns.size()) {
            throw ApiException.validation("definition.columns", "Each column can be listed once.");
        }
        try {
            QueryFilter filter = definition.filter() == null ? null
                    : FilterNodes.toFilter(definition.filter(), "filter");
            List<QuerySort> sort = definition.sort().stream()
                    .map(key -> new QuerySort(key.field(), Boolean.TRUE.equals(key.descending()))).toList();
            analyzer.analyze(passed.object(), access,
                    new RecordQuery(filter, sort, columns.isEmpty() ? null : columns, List.of(), 0, null));
        } catch (ApiException e) {
            if (e.code() != ErrorCode.VALIDATION_ERROR) {
                throw e;
            }
            Map<String, List<String>> places = new LinkedHashMap<>();
            // The engine calls the columns of a view "fields"; a view calls them columns.
            e.fields().forEach((place, problems) -> places.put("definition." + place.replaceFirst("^fields", "columns"),
                    new ArrayList<>(problems)));
            throw ApiException.validation(places);
        }
        return json.writeValueAsString(definition);
    }

    private ListViewView view(ListViewStore.Row row, UUID user) {
        return new ListViewView(row.id().toString(), row.objectApiName(), row.label(), row.scope(),
                row.createdBy().equals(user), json.readValue(row.definition(), ListViewDefinition.class),
                row.version(), row.updatedAt());
    }
}
