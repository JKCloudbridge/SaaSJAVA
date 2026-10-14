package app.platform.security.internal;

import app.platformapi.ApiPaths;
import app.platformapi.ApiResponse;
import app.platformapi.ObjectSharingView;
import app.platformapi.SaveObjectSharingRequest;
import app.platformapi.SaveSharingRuleRequest;
import app.platformapi.SharingRuleView;
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
 * Record-level security setup (Sprint 17, ADR-0116): the organization-wide default of each object and the sharing
 * rules. Thin: the rules are in {@link SharingService}. Answered only on an organization host and only for members who
 * hold the ability to manage sharing; an identifier of another organization is simply not found.
 */
@RestController
@Tag(name = "Sharing")
class SharingController {

    private static final String OBJECTS = ApiPaths.SHARING + "/objects";
    private static final String RULES = ApiPaths.SHARING + "/rules";
    private static final String GATE = "NOT_FOUND on the platform host, FORBIDDEN without the ability to manage "
            + "sharing. ";

    private final SharingService sharing;

    SharingController(SharingService sharing) {
        this.sharing = sharing;
    }

    @GetMapping(OBJECTS)
    @Operation(
            operationId = "listObjectSharing",
            summary = "How the records of every object are shared by default",
            description = "One entry per object of the organization: the organization-wide default and the hierarchy "
                    + "switch, and whether an administrator has set the object up (an object nobody set up is "
                    + "PUBLIC_READ_WRITE with the hierarchy on: every member who may read the object reads every "
                    + "record). " + GATE)
    ApiResponse<List<ObjectSharingView>> listObjects() {
        return ApiResponse.of(sharing.objects());
    }

    @GetMapping(OBJECTS + "/{objectApiName}")
    @Operation(
            operationId = "getObjectSharing",
            summary = "How the records of one object are shared by default",
            description = "NOT_FOUND for an object that does not exist. " + GATE)
    ApiResponse<ObjectSharingView> getObject(@PathVariable String objectApiName) {
        return ApiResponse.of(sharing.object(objectApiName));
    }

    @PutMapping(OBJECTS + "/{objectApiName}")
    @Operation(
            operationId = "setObjectSharing",
            summary = "Set the organization-wide default of an object",
            description = "PRIVATE: members reach only the records they own, the records of the people below them "
                    + "(when the hierarchy switch is on), the records a rule gives them and the records shared with "
                    + "them or with a group they are in. PUBLIC_READ: every member reads every record, changes need "
                    + "the same mechanisms. PUBLIC_READ_WRITE: every member reads and changes every record. "
                    + "Members who hold view-all or modify-all on the object are not held by the default. The "
                    + "change is immediate on every instance and no record is rewritten. The request carries the "
                    + "version that was read once the object has been set up (CONCURRENT_MODIFICATION otherwise). "
                    + "Audited. " + GATE)
    ApiResponse<ObjectSharingView> setObject(@PathVariable String objectApiName,
            @Valid @RequestBody SaveObjectSharingRequest body) {
        return ApiResponse.of(sharing.setObject(objectApiName, body));
    }

    @GetMapping(RULES)
    @Operation(
            operationId = "listSharingRules",
            summary = "The sharing rules of the organization",
            description = "All of them, or those of one object. " + GATE)
    ApiResponse<List<SharingRuleView>> listRules(@RequestParam(required = false) String object) {
        return ApiResponse.of(sharing.rules(object));
    }

    @PostMapping(RULES)
    @Operation(
            operationId = "createSharingRule",
            summary = "Create a sharing rule",
            description = "OWNER: the records owned by the members of a role (or of a role and its subordinates) are "
                    + "given to a role, a role and its subordinates or a public group. GROUP: the same for the "
                    + "records owned by the members of a public group. CRITERIA: the records that meet a condition "
                    + "on their fields (the model of the record query, on fields flagged as indexed) are given. The "
                    + "level is READ or READ_WRITE; a rule never gives the right to delete. VALIDATION_ERROR lists "
                    + "every problem with its place. CONFLICT at the most rules per object. Audited by names, never "
                    + "by the condition. " + GATE)
    ResponseEntity<ApiResponse<SharingRuleView>> createRule(@Valid @RequestBody SaveSharingRuleRequest body) {
        return ResponseEntity.status(HttpStatus.CREATED).header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(ApiResponse.of(sharing.createRule(body)));
    }

    @GetMapping(RULES + "/{ruleId}")
    @Operation(
            operationId = "getSharingRule",
            summary = "One sharing rule",
            description = "NOT_FOUND for a rule of another organization. " + GATE)
    ApiResponse<SharingRuleView> getRule(@PathVariable UUID ruleId) {
        return ApiResponse.of(sharing.rule(ruleId));
    }

    @PutMapping(RULES + "/{ruleId}")
    @Operation(
            operationId = "updateSharingRule",
            summary = "Change a sharing rule",
            description = "The object and the kind of a rule do not change. The request carries the version that was "
                    + "read (CONCURRENT_MODIFICATION otherwise). Takes effect at the next question on every "
                    + "instance. Audited. " + GATE)
    ApiResponse<SharingRuleView> updateRule(@PathVariable UUID ruleId,
            @Valid @RequestBody SaveSharingRuleRequest body) {
        return ApiResponse.of(sharing.updateRule(ruleId, body));
    }

    @DeleteMapping(RULES + "/{ruleId}")
    @Operation(
            operationId = "deleteSharingRule",
            summary = "Remove a sharing rule",
            description = "What the rule gave is taken back at the next question. Audited. " + GATE)
    ResponseEntity<Void> deleteRule(@PathVariable UUID ruleId) {
        sharing.deleteRule(ruleId);
        return ResponseEntity.noContent().header(HttpHeaders.CACHE_CONTROL, "no-store").build();
    }
}
