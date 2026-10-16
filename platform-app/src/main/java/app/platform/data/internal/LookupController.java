package app.platform.data.internal;

import app.platformapi.ApiPaths;
import app.platformapi.ApiResponse;
import app.platformapi.LookupLabelsRequest;
import app.platformapi.LookupLabelsView;
import app.platformapi.LookupOptionView;
import app.platformapi.LookupOptionsRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The names behind link fields and the records a link could point to (Sprint 19, ADR-0129). Thin: the rules are in
 * {@link LookupService}. Answered only on an organization host, for a signed-in member who may read the object and the
 * field; the target of the link is judged by the member's own permissions on it.
 */
@RestController
@Tag(name = "Records")
class LookupController {

    private static final String LOOKUPS = ApiPaths.OBJECTS + "/{objectApiName}/lookups/{fieldApiName}";
    private static final String UNKNOWN = "NOT_FOUND for an object that does not exist, that a platform module owns or "
            + "that the caller may not read, and for a field that is not a link of the object or that the caller may "
            + "not read (all answer alike). ";

    private final LookupService lookups;

    LookupController(LookupService lookups) {
        this.lookups = lookups;
    }

    @PostMapping(LOOKUPS + "/labels")
    @Operation(
            operationId = "getLookupLabels",
            summary = "The names of the records a link field points to",
            description = "For the identifiers a list or a form holds in a lookup or master-detail field (at most "
                    + "100). Each record is named by the field that names a record of the target object for the "
                    + "caller (the first searchable text field, else the first required one, else the auto-number); "
                    + "a person is named by their display name while their membership is active. An identifier that "
                    + "is missing from the answer names a record the caller may not read, one that does not exist, "
                    + "one in the recycle bin, or a person who is no longer a member, and the answer does not say "
                    + "which. The target is judged by the caller's own permissions and record-level security. A "
                    + "POST only because the identifiers travel in the body. VALIDATION_ERROR for a value that is not "
                    + "an identifier. " + UNKNOWN)
    ApiResponse<LookupLabelsView> labels(@PathVariable String objectApiName, @PathVariable String fieldApiName,
            @Valid @RequestBody LookupLabelsRequest body) {
        return ApiResponse.of(new LookupLabelsView(lookups.labels(objectApiName, fieldApiName, body.ids())));
    }

    @PostMapping(LOOKUPS + "/options")
    @Operation(
            operationId = "getLookupOptions",
            summary = "The records a link field could point to, for a picker",
            description = "Records of the target object the caller may read whose name contains the text, a few at a "
                    + "time (at most 25, 10 by default): a search over the searchable fields when the target has "
                    + "some, otherwise a match on the name field. Without text the first records in name order. "
                    + "People are offered while their membership is active. Nothing here lists a directory: the "
                    + "text must match. A POST only because the text travels in the body, never in the address, where "
                    + "every proxy on the way would log it. The write that follows is judged again (a link is accepted "
                    + "only to a record the caller can read). " + UNKNOWN)
    ApiResponse<List<LookupOptionView>> options(@PathVariable String objectApiName,
            @PathVariable String fieldApiName, @Valid @RequestBody LookupOptionsRequest body) {
        return ApiResponse.of(lookups.options(objectApiName, fieldApiName, body.text(),
                body.limit() == null ? 0 : body.limit()));
    }
}
