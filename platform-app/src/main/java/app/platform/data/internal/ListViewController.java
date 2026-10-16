package app.platform.data.internal;

import app.platformapi.ApiPaths;
import app.platformapi.ApiResponse;
import app.platformapi.CreateListViewRequest;
import app.platformapi.ListViewView;
import app.platformapi.UpdateListViewRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The saved list views of an object (Sprint 19, ADR-0130). Thin: the rules are in {@link ListViews}. Answered only on
 * an organization host, for a signed-in member who may read the object. A view only stores how to ask; opening one is
 * the ordinary record query of the person who opens it.
 */
@RestController
@Tag(name = "Records")
class ListViewController {

    private static final String VIEWS = ApiPaths.OBJECTS + "/{objectApiName}/views";
    private static final String UNKNOWN = "NOT_FOUND for an object that does not exist, that a platform module owns or "
            + "that the caller may not read (all three answer alike). ";

    private final ListViews views;

    ListViewController(ListViews views) {
        this.views = views;
    }

    @GetMapping(VIEWS)
    @Operation(
            operationId = "listListViews",
            summary = "The saved list views of an object that the caller sees",
            description = "The shared views of the object, then the caller's own personal ones, each with the filter, "
                    + "the order and the columns it was saved with. A view is not a permission: running it is an "
                    + "ordinary query, decided by the caller's own permissions and record-level security. " + UNKNOWN)
    ApiResponse<List<ListViewView>> list(@PathVariable String objectApiName) {
        return ApiResponse.of(views.list(objectApiName));
    }

    @PostMapping(VIEWS)
    @Operation(
            operationId = "createListView",
            summary = "Save a list view",
            description = "PERSONAL is for the caller only; SHARED is for everyone who may read the object and needs "
                    + "the ability to share list views (FORBIDDEN without it). The definition is checked by the "
                    + "first three stages of the query engine (shape, fields, operators and values) against what "
                    + "the caller may read: VALIDATION_ERROR lists the places that are wrong under definition. "
                    + "CONFLICT for a name already used among the views the caller sees in the object, and at the "
                    + "most views allowed. Nothing is run and no record is read. Audited by identifier and scope. "
                    + UNKNOWN)
    ResponseEntity<ApiResponse<ListViewView>> create(@PathVariable String objectApiName,
            @Valid @RequestBody CreateListViewRequest body) {
        return ResponseEntity.status(HttpStatus.CREATED).header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(ApiResponse.of(views.create(objectApiName, body)));
    }

    @PutMapping(VIEWS + "/{viewId}")
    @Operation(
            operationId = "updateListView",
            summary = "Change a saved list view",
            description = "The request carries the version that was read: CONCURRENT_MODIFICATION (409) when the view "
                    + "has changed since, and nothing is changed. A personal view is changed only by the person who "
                    + "made it; a shared view needs the ability to share list views, whoever made it, and only its "
                    + "maker can make it personal again. NOT_FOUND for a view that does not exist or that is "
                    + "another person's personal view. " + UNKNOWN)
    ApiResponse<ListViewView> update(@PathVariable String objectApiName, @PathVariable UUID viewId,
            @Valid @RequestBody UpdateListViewRequest body) {
        return ApiResponse.of(views.update(objectApiName, viewId, body));
    }

    @DeleteMapping(VIEWS + "/{viewId}")
    @Operation(
            operationId = "deleteListView",
            summary = "Remove a saved list view",
            description = "The version that was read must be given: CONCURRENT_MODIFICATION (409) when the view has "
                    + "changed since. The same rights as changing it. NOT_FOUND for a view that does not exist or "
                    + "that is another person's personal view. " + UNKNOWN)
    ResponseEntity<Void> delete(@PathVariable String objectApiName, @PathVariable UUID viewId,
            @RequestParam long version) {
        views.delete(objectApiName, viewId, version);
        return ResponseEntity.noContent().header(HttpHeaders.CACHE_CONTROL, "no-store").build();
    }
}
