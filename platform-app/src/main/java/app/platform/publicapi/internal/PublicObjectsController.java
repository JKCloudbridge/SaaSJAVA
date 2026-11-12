package app.platform.publicapi.internal;

import app.platform.data.ObjectDescriptions;
import app.platform.publicapi.PublicObjectDescription;
import app.platform.publicapi.PublicObjectSummary;
import app.platformapi.ApiPaths;
import app.platformapi.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Which objects exist for the caller and what their fields are (Sprint 30, ADR-0245). Thin: the answers come from
 * {@link ObjectDescriptions}, the code that tells the screens, so the rights shown are the rights enforced.
 */
@RestController
@Tag(name = "Objects")
class PublicObjectsController {

    private static final String UNKNOWN = "NOT_FOUND for an object that does not exist, that the platform itself "
            + "owns (a user, a profile ...), that belongs to another organization or that the caller may not read: "
            + "all four answer alike. ";

    private final ObjectDescriptions objects;

    PublicObjectsController(ObjectDescriptions objects) {
        this.objects = objects;
    }

    @GetMapping(ApiPaths.PUBLIC_OBJECTS)
    @Operation(
            operationId = "listObjects",
            summary = "The objects the caller may read",
            description = "Every object of the organization whose records the caller may read, standard and custom "
                    + "alike, by API name, with what the caller may do with its records. The API name is what the "
                    + "address of every other operation takes. Objects the platform itself owns, and objects the "
                    + "caller may not read, are not listed. NOT_FOUND on the platform host.")
    ResponseEntity<ApiResponse<List<PublicObjectSummary>>> list() {
        List<PublicObjectSummary> summaries = objects.readable().stream().map(PublicRecordMapper::summary).toList();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.of(summaries));
    }

    @GetMapping(ApiPaths.PUBLIC_OBJECTS + "/{object}")
    @Operation(
            operationId = "describeObject",
            summary = "One object and the fields of it the caller may read",
            description = "The fields the caller may read with their types, limits and choices, which of them the "
                    + "caller may write, and for each the comparisons a filter accepts and whether a query can order "
                    + "by it. Read the lists from here; do not copy them. A field the caller may not read is not "
                    + "listed, and writing it is answered like writing a field that does not exist. Also the record "
                    + "types the caller may use. " + UNKNOWN)
    ResponseEntity<ApiResponse<PublicObjectDescription>> describe(@PathVariable("object") String object) {
        PublicObjectDescription description = PublicRecordMapper.description(objects.describe(object));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.of(description));
    }
}
