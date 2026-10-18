package app.platform.metadata.internal;

import app.platform.security.Ability;
import app.platformapi.ApiPaths;
import app.platformapi.ApiResponse;
import app.platformapi.ApprovalCatalogueView;
import app.platformapi.ApprovalProcessActivationRequest;
import app.platformapi.ApprovalProcessCheckView;
import app.platformapi.ApprovalProcessView;
import app.platformapi.CheckApprovalProcessRequest;
import app.platformapi.CreateApprovalProcessRequest;
import app.platformapi.UpdateApprovalProcessRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
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
 * The approval processes of an object (Sprint 24, ADR-0145). Thin: the rules are in {@link ApprovalProcessService}.
 * Answered only on an organization host; reading needs {@code approvals.view}; a change is made live at once, which is
 * a
 * publication of one change, so it needs {@code approvals.manage} and {@code metadata.publish} (ADR-0068); trying needs
 * {@code approvals.manage}. Several changes that belong together go through a change set instead, where the same
 * abilities are asked when the set is checked or published.
 */
@RestController
@Tag(name = "Approvals")
class ApprovalProcessController {

    private static final List<Ability> LIVE = List.of(Ability.APPROVALS_MANAGE, Ability.METADATA_PUBLISH);
    private static final String PROCESSES = ApiPaths.METADATA_APPROVALS;
    private static final String DEFINITION_NOTE = "The entry criteria are in the expression language (true or false, "
            + "they may use TODAY() and NOW()); the steps name who is asked by closed kinds of rule; the outcomes set "
            + "fields of the record to literals. The request is checked here (a VALIDATION_ERROR names every place); "
            + "whether the fields it names exist is checked after the change and a CONFLICT names what is wrong. A "
            + "request that is already pending keeps the definition it started under.";

    private final MetadataAdministration administration;
    private final ApprovalProcessService service;
    private final MetadataLifecycle lifecycle;
    private final ChangeCodec codec;

    ApprovalProcessController(MetadataAdministration administration, ApprovalProcessService service,
            MetadataLifecycle lifecycle, ChangeCodec codec) {
        this.administration = administration;
        this.service = service;
        this.lifecycle = lifecycle;
        this.codec = codec;
    }

    @GetMapping(PROCESSES)
    @Operation(
            operationId = "listApprovalProcesses",
            summary = "The approval processes of the organization",
            description = "For members who may view approvals. Name an object to list only its processes. NOT_FOUND "
                    + "for an object the organization does not have.")
    ApiResponse<List<ApprovalProcessView>> list(
            @RequestParam(name = "object", required = false) String objectApiName) {
        return ApiResponse.of(administration.run("metadata.approval.list", Ability.APPROVALS_VIEW,
                caller -> service.processes(objectApiName)));
    }

    @GetMapping(ApiPaths.METADATA_APPROVAL_CATALOGUE)
    @Operation(
            operationId = "getApprovalCatalogue",
            summary = "What an approval process can be made of",
            description = "The kinds of approver rule with what each one's value names, the completion rules, the "
                    + "outcomes, the states of a request and of a step, and the limits the platform enforces. The "
                    + "screens build their forms and filters from this and hold no list of their own. For members who "
                    + "may view approvals.")
    ApiResponse<ApprovalCatalogueView> catalogue() {
        return ApiResponse.of(administration.run("metadata.approval.catalogue", Ability.APPROVALS_VIEW,
                caller -> service.catalogue()));
    }

    @GetMapping(PROCESSES + "/{objectApiName}/{processApiName}")
    @Operation(
            operationId = "getApprovalProcess",
            summary = "One approval process",
            description = "NOT_FOUND for a process the object does not have.")
    ApiResponse<ApprovalProcessView> get(@PathVariable String objectApiName, @PathVariable String processApiName) {
        return ApiResponse.of(administration.run("metadata.approval.get", Ability.APPROVALS_VIEW,
                caller -> service.process(objectApiName, processApiName)));
    }

    @PostMapping(PROCESSES + "/{objectApiName}")
    @Operation(
            operationId = "createApprovalProcess",
            summary = "Add an approval process to an object",
            description = "Live at once, as a release of one change. The name is turned into the permanent API name "
                    + "with the ending __c. " + DEFINITION_NOTE + " CONFLICT for an object whose records belong to "
                    + "the platform, at the limit, when the plan has no approvals, or when the result would break a "
                    + "dependency. Audited by name and counts, never by the text.")
    ResponseEntity<ApiResponse<ApprovalProcessView>> create(@PathVariable String objectApiName,
            @Valid @RequestBody CreateApprovalProcessRequest body) {
        ApprovalProcessView created = administration.runAll("metadata.approval.create", LIVE,
                caller -> lifecycle.applyNow(caller,
                        codec.now(Change.Kind.CREATE_APPROVAL_PROCESS, objectApiName, null, body),
                        ApprovalProcessView.class));
        return ResponseEntity.status(HttpStatus.CREATED).header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(ApiResponse.of(created));
    }

    @PutMapping(PROCESSES + "/{objectApiName}/{processApiName}")
    @Operation(
            operationId = "updateApprovalProcess",
            summary = "Change an approval process",
            description = "Live at once, as a release of one change. The API name and the object never change. A "
                    + "change of the entry criteria, the steps, the outcomes, the fallback or the self-approval rule "
                    + "makes a new version of the definition; requests already pending keep the version they started "
                    + "under. " + DEFINITION_NOTE + " CONCURRENT_MODIFICATION when someone changed it since it was "
                    + "read.")
    ApiResponse<ApprovalProcessView> update(@PathVariable String objectApiName, @PathVariable String processApiName,
            @Valid @RequestBody UpdateApprovalProcessRequest body) {
        return ApiResponse.of(administration.runAll("metadata.approval.update", LIVE,
                caller -> lifecycle.applyNow(caller,
                        codec.now(Change.Kind.UPDATE_APPROVAL_PROCESS, objectApiName, processApiName, body),
                        ApprovalProcessView.class)));
    }

    @PostMapping(PROCESSES + "/{objectApiName}/{processApiName}/activation")
    @Operation(
            operationId = "activateApprovalProcess",
            summary = "Switch an approval process on or off",
            description = "Live at once, as a release of one change, and without a new version of the definition. A "
                    + "process that is switched off starts no new request; requests already pending finish and keep "
                    + "their lock. CONFLICT when the plan has no approvals and the process is being switched on. "
                    + "CONCURRENT_MODIFICATION when someone changed it since it was read.")
    ApiResponse<ApprovalProcessView> activate(@PathVariable String objectApiName, @PathVariable String processApiName,
            @Valid @RequestBody ApprovalProcessActivationRequest body) {
        return ApiResponse.of(administration.runAll("metadata.approval.activate", LIVE, caller -> {
            ApprovalProcessView current = service.process(objectApiName, processApiName);
            UpdateApprovalProcessRequest same = new UpdateApprovalProcessRequest(current.label(),
                    current.description(), body.active(), current.entryCriteria(), current.allowSelfApproval(),
                    current.steps(), current.outcomes(), current.fallback(), body.version());
            return lifecycle.applyNow(caller, codec.now(Change.Kind.UPDATE_APPROVAL_PROCESS, objectApiName,
                    processApiName, same), ApprovalProcessView.class);
        }));
    }

    @DeleteMapping(PROCESSES + "/{objectApiName}/{processApiName}")
    @Operation(
            operationId = "deleteApprovalProcess",
            summary = "Remove an approval process",
            description = "Live at once, as a release of one change. CONFLICT while the process has a request waiting "
                    + "for a decision: switch it off and remove it when they have ended. The undo of the release makes "
                    + "it again, as a new process.")
    ResponseEntity<Void> delete(@PathVariable String objectApiName, @PathVariable String processApiName) {
        administration.runAll("metadata.approval.delete", LIVE, caller -> lifecycle.applyNow(caller,
                codec.now(Change.Kind.DELETE_APPROVAL_PROCESS, objectApiName, processApiName, null), Boolean.class));
        return ResponseEntity.noContent().header(HttpHeaders.CACHE_CONTROL, "no-store").build();
    }

    @PostMapping(PROCESSES + "/{objectApiName}/check")
    @Operation(
            operationId = "checkApprovalProcess",
            summary = "Try entry criteria on sample values, and the steps and outcomes on the object",
            description = "Answers what would happen to a record with these values: RUNS (the criteria are true, or "
                    + "there are none), SKIPPED (false or no value: the record could not be submitted) or ERROR with "
                    + "the place when the evaluation itself fails. Criteria that are not valid are a VALIDATION_ERROR "
                    + "naming the place. There are no records yet, so the values are typed as text for the type of "
                    + "each field. Name steps and outcomes to learn, by place, which fields they name do not exist or "
                    + "cannot be used and which literals do not fit. Nothing is stored and nothing is logged. Needs "
                    + "approvals.manage. Who would be asked for a real record is another question: see resolve.")
    ApiResponse<ApprovalProcessCheckView> check(@PathVariable String objectApiName,
            @Valid @RequestBody CheckApprovalProcessRequest body) {
        return ApiResponse.of(administration.run("metadata.approval.check", Ability.APPROVALS_MANAGE,
                caller -> service.check(objectApiName, body)));
    }
}
