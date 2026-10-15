package app.platform.data.internal;

import app.platformapi.ApiPaths;
import app.platformapi.ApiResponse;
import app.platformapi.ObjectFormView;
import app.platformapi.RecordCheckView;
import app.platformapi.ValidateRecordRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * What a form needs from the server besides the records (Sprint 19, ADR-0129): the member's own description of an
 * object, and the preview of what a save would be told. Thin: the rules are in {@link ObjectForms} and
 * {@link RecordCheck}. Answered only on an organization host, for a signed-in member; what is described and what is
 * judged is decided by that member's permissions, never by a role of the platform.
 */
@RestController
@Tag(name = "Records")
class FormController {

    private static final String UNKNOWN = "NOT_FOUND for an object that does not exist, that a platform module owns or "
            + "that the caller may not read (all three answer alike). ";

    private final ObjectForms forms;
    private final RecordCheck checks;

    FormController(ObjectForms forms, RecordCheck checks) {
        this.forms = forms;
        this.checks = checks;
    }

    @GetMapping(ApiPaths.OBJECTS + "/{objectApiName}/form")
    @Operation(
            operationId = "getObjectForm",
            summary = "Describe an object for the signed-in member: fields, record types, layout, permissions",
            description = "What a screen needs to draw the list, the create form and the edit form of the object for "
                    + "this member, in one answer. Only the fields the member may read are described (the others are "
                    + "absent, not masked), restricted to what the record type offers, with what they may type, the "
                    + "comparisons a filter accepts and whether a list can be ordered by each. The layout is the "
                    + "object's page layout for the member's profile and the record type, reduced to what they see, "
                    + "or one the platform makes when the object has none. recordType describes the object for one "
                    + "of the record types the member may use (VALIDATION_ERROR at recordType for any other, "
                    + "answered like a type that does not exist); without it, recordId (the record being edited) "
                    + "decides by the type that record holds, and without both the member's default type does. "
                    + "Presentation only: every request that follows is decided again. " + UNKNOWN
                    + "Also NOT_FOUND for a record they cannot reach. Organization hosts only.")
    ApiResponse<ObjectFormView> form(@PathVariable String objectApiName,
            @RequestParam(required = false) String recordType, @RequestParam(required = false) UUID recordId) {
        return ApiResponse.of(forms.describe(objectApiName, recordType, recordId));
    }

    @PostMapping(ApiPaths.OBJECTS + "/{objectApiName}/records/validate")
    @Operation(
            operationId = "validateRecord",
            summary = "Ask what the server would say to a save, without saving",
            description = "The create or the change of a record (the one with recordId) run through every stage of "
                    + "the write pipeline (record type, field permission, the type of each value, required fields, "
                    + "linked records, unique fields, validation rules, formulas) and then undone: nothing is stored, "
                    + "no auto-number is used up, nothing is audited or announced. A save that would be refused is "
                    + "refused here with the same VALIDATION_ERROR, the same places and the same words, because it "
                    + "is the same code; one that would work answers with the results its formulas would give "
                    + "(and the formula fields it would leave empty). A "
                    + "change of an existing record is judged first like a real one: NOT_FOUND for a record the "
                    + "caller cannot read, FORBIDDEN for one they may read and not change. FORBIDDEN for a caller "
                    + "who may read the object but not create (or change) in it. " + UNKNOWN)
    ApiResponse<RecordCheckView> validate(@PathVariable String objectApiName,
            @Valid @RequestBody ValidateRecordRequest body) {
        RecordCheck.Result result = checks.check(objectApiName, body.recordId(), body.recordType(), body.fields(),
                body.clear());
        return ApiResponse.of(new RecordCheckView(result.calculated(), result.cleared()));
    }
}
