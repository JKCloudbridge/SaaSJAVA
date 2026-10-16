package app.platform.data.internal;

import app.platformapi.ApiPaths;
import app.platformapi.ApiResponse;
import app.platformapi.RecordPageDescriptorView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The member's own record page of an object (Sprint 20, ADR-0131): presents what {@link RecordPageViews} already
 * decided, nothing more. No administrative ability is needed, the same discipline as the object form
 * ({@link FormController}, Sprint 19, ADR-0129): a member reads their own screen, never the setup.
 */
@RestController
@Tag(name = "Records")
class RecordPageController {

    private final RecordPageViews views;

    RecordPageController(RecordPageViews views) {
        this.views = views;
    }

    @GetMapping(ApiPaths.OBJECTS + "/{objectApiName}/record-page")
    @Operation(
            operationId = "getRecordPage",
            summary = "The record page of an object, resolved for the signed-in member",
            description = "The header, the tabs and the actions, each already filtered to what the caller may "
                    + "reach; a block or an action the caller may not use is left out, never shown as refused. With "
                    + "no recordType and no recordId the object's own default rule decides; with a recordId its own "
                    + "record type is used unless recordType overrides it. NOT_FOUND for an object the caller may "
                    + "not read or that does not exist, or a record they cannot reach.")
    ApiResponse<RecordPageDescriptorView> get(@PathVariable String objectApiName,
            @RequestParam(required = false) String recordType, @RequestParam(required = false) UUID recordId) {
        return ApiResponse.of(views.describe(objectApiName, recordType, recordId));
    }
}
