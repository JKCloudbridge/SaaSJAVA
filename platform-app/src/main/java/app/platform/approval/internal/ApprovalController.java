package app.platform.approval.internal;

import app.platform.approval.internal.ApprovalCaller.Member;
import app.platform.approval.internal.ApprovalStore.InstanceRow;
import app.platformapi.ApiPageResponse;
import app.platformapi.ApiPaths;
import app.platformapi.ApiResponse;
import app.platformapi.ApprovalChoicesView;
import app.platformapi.ApprovalDecisionRequest;
import app.platformapi.ApprovalDelegationView;
import app.platformapi.ApprovalInboxSummaryView;
import app.platformapi.ApprovalInstanceSummaryView;
import app.platformapi.ApprovalInstanceView;
import app.platformapi.ApprovalPersonView;
import app.platformapi.ApprovalReapplyRequest;
import app.platformapi.ApprovalReassignRequest;
import app.platformapi.ApprovalResolutionView;
import app.platformapi.ApprovalTaskView;
import app.platformapi.CreateApprovalDelegationRequest;
import app.platformapi.RecordApprovalView;
import app.platformapi.ResolveApprovalRequest;
import app.platformapi.SubmitApprovalRequest;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The member's side of approvals (Sprint 24, ADR-0145 to ADR-0152): the inbox, the requests, the decisions, the panel
 * of
 * a record, the delegations and the preview of who would be asked. Thin: the rules are in {@link ApprovalEngine},
 * {@link ApprovalViews}, {@link ApprovalDelegations} and {@link ApprovalResolveService}. Answered only on an
 * organization host for a signed-in member (the platform host answers "not found"). An approver acts because of the
 * task they hold, not because of an ability; the abilities are for the definitions, the delegations of others, the
 * history of every request and the override of a lock. Nothing the caller may not see is told: it does not exist.
 */
@RestController
@Tag(name = "Approvals")
class ApprovalController {

    private static final String APPROVALS = ApiPaths.APPROVALS;
    private static final String INSTANCES = APPROVALS + "/instances";
    private static final String RECORD = ApiPaths.OBJECTS + "/{objectApiName}/records/{id}/approval";
    private static final String UNKNOWN = "NOT_FOUND for what the caller may not see. ";
    private static final String COMMENT = "The comment is text of a person: at most 2000 characters, kept in the "
            + "history and shown only to those who may see it; it is never logged and never sent in an event or a "
            + "mail. ";
    private static final String VERSION = "The version is the one the caller read; a request that changed since is "
            + "answered CONCURRENT_MODIFICATION and names nothing. ";

    private final ApprovalCaller callers;
    private final ApprovalEngine engine;
    private final ApprovalViews views;
    private final ApprovalDelegations delegations;
    private final ApprovalResolveService resolve;

    ApprovalController(ApprovalCaller callers, ApprovalEngine engine, ApprovalViews views,
            ApprovalDelegations delegations, ApprovalResolveService resolve) {
        this.callers = callers;
        this.engine = engine;
        this.views = views;
        this.delegations = delegations;
        this.resolve = resolve;
    }

    // ---- the inbox ----

    @GetMapping(APPROVALS + "/inbox")
    @Operation(
            operationId = "listApprovalTasks",
            summary = "The caller's tasks",
            description = "Three tabs: TO_APPROVE (open tasks), DELEGATED (open tasks that came through a "
                    + "delegation) and DECIDED (approved, rejected or handed on). Open tasks are oldest first, "
                    + "decided ones newest first; cursor paging. Each task names its record by the title only when "
                    + "the caller can read the record, and says so when they cannot: an approval grants no access. "
                    + "Narrow by object and process.")
    ApiPageResponse<ApprovalTaskView> inbox(@RequestParam(name = "tab", required = false) String tab,
            @RequestParam(name = "object", required = false) String object,
            @RequestParam(name = "process", required = false) String process,
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "cursor", required = false) String cursor) {
        return views.inbox(callers.require(), tab, object, process, limit, cursor);
    }

    @GetMapping(APPROVALS + "/inbox/summary")
    @Operation(
            operationId = "getApprovalInboxSummary",
            summary = "How many tasks wait for the caller, and whether to show the link",
            description = "For the header: the number of open tasks and whether the member has anything to do with "
                    + "approvals (the plan has the feature, or they were asked or submitted something).")
    ApiResponse<ApprovalInboxSummaryView> inboxSummary() {
        return ApiResponse.of(views.summary(callers.require()));
    }

    // ---- requests ----

    @GetMapping(INSTANCES)
    @Operation(
            operationId = "listApprovals",
            summary = "Approval requests the caller may see",
            description = "Newest first, cursor paging. A member sees the requests they submitted or were asked to "
                    + "decide; a member who holds approvals.view-all or approvals.manage sees every request for the "
                    + "records they can read. Narrow by status, object, process, record, or attention (requests "
                    + "that nobody can decide right now).")
    ApiPageResponse<ApprovalInstanceSummaryView> list(@RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "object", required = false) String object,
            @RequestParam(name = "process", required = false) String process,
            @RequestParam(name = "attention", required = false, defaultValue = "false") boolean attention,
            @RequestParam(name = "record", required = false) UUID record,
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "cursor", required = false) String cursor) {
        return views.instances(callers.require(), status, object, process, attention, record, limit, cursor);
    }

    @GetMapping(INSTANCES + "/{id}")
    @Operation(
            operationId = "getApproval",
            summary = "One approval request",
            description = "The steps with the people asked, what the caller may do now, and the history for those "
                    + "who may see it (the submitter, anyone who was asked, and members with approvals.view-all for "
                    + "a record they can read). The history never shows a value of the record. " + UNKNOWN)
    ApiResponse<ApprovalInstanceView> get(@PathVariable UUID id) {
        return ApiResponse.of(views.instance(callers.require(), id));
    }

    @PostMapping(INSTANCES + "/{id}/approve")
    @Operation(
            operationId = "approveApproval",
            summary = "Approve the caller's task",
            description = "Allowed to a person who holds an open task of the active step, whatever their abilities; "
                    + "the task is checked in the transaction, with their standing (an active member who holds the "
                    + "licence their profile needs). On a step that needs any one approver the first approval "
                    + "completes it and the second approver's call is CONCURRENT_MODIFICATION. " + COMMENT + VERSION
                    + UNKNOWN)
    ApiResponse<ApprovalInstanceView> approve(@PathVariable UUID id, @Valid @RequestBody ApprovalDecisionRequest body) {
        Member member = callers.require();
        return ApiResponse.of(views.full(member, engine.approve(member, id, body.version(), body.comment())));
    }

    @PostMapping(INSTANCES + "/{id}/reject")
    @Operation(
            operationId = "rejectApproval",
            summary = "Reject the caller's task",
            description = "Ends the step and the request as REJECTED, whatever the completion rule. The comment is "
                    + "required (VALIDATION_ERROR otherwise). " + COMMENT + VERSION + UNKNOWN)
    ApiResponse<ApprovalInstanceView> reject(@PathVariable UUID id, @Valid @RequestBody ApprovalDecisionRequest body) {
        Member member = callers.require();
        return ApiResponse.of(views.full(member, engine.reject(member, id, body.version(), body.comment())));
    }

    @PostMapping(INSTANCES + "/{id}/recall")
    @Operation(
            operationId = "recallApproval",
            summary = "Withdraw a request",
            description = "The person who submitted it, or a member who holds approvals.override-lock. The record is "
                    + "unlocked and the outcome updates for RECALLED are made in the same transaction. " + COMMENT
                    + VERSION + UNKNOWN)
    ApiResponse<ApprovalInstanceView> recall(@PathVariable UUID id, @Valid @RequestBody ApprovalDecisionRequest body) {
        Member member = callers.require();
        return ApiResponse.of(views.full(member, engine.recall(member, id, body.version(), body.comment())));
    }

    @PostMapping(INSTANCES + "/{id}/cancel")
    @Operation(
            operationId = "cancelApproval",
            summary = "End a request as an administrator",
            description = "A member who holds approvals.override-lock; FORBIDDEN otherwise. The record is unlocked. "
                    + "This is the only way a request becomes CANCELLED. " + COMMENT + VERSION + UNKNOWN)
    ApiResponse<ApprovalInstanceView> cancel(@PathVariable UUID id, @Valid @RequestBody ApprovalDecisionRequest body) {
        Member member = callers.require();
        return ApiResponse.of(views.full(member, engine.cancel(member, id, body.version(), body.comment())));
    }

    @PostMapping(INSTANCES + "/{id}/reassign")
    @Operation(
            operationId = "reassignApproval",
            summary = "Hand the caller's task to one other person",
            description = "To one person who is an active member with a licence and holds no task of the step yet "
                    + "(so a task never goes back or loops); the submitter is excluded unless the process allows "
                    + "it. Recorded with both names. " + COMMENT + VERSION + UNKNOWN)
    ApiResponse<ApprovalInstanceView> reassign(@PathVariable UUID id,
            @Valid @RequestBody ApprovalReassignRequest body) {
        Member member = callers.require();
        return ApiResponse.of(views.full(member,
                engine.reassign(member, id, body.toUserId(), body.version(), body.comment())));
    }

    @PostMapping(INSTANCES + "/{id}/assign")
    @Operation(
            operationId = "assignApproval",
            summary = "Ask one more person to decide the active step",
            description = "For a member who holds approvals.manage: the way out of a step that nobody can decide "
                    + "(its attention is cleared). The person must be an active member with a licence and hold no "
                    + "task of the step yet. " + COMMENT + VERSION + UNKNOWN)
    ApiResponse<ApprovalInstanceView> assign(@PathVariable UUID id, @Valid @RequestBody ApprovalReassignRequest body) {
        Member member = callers.require();
        return ApiResponse.of(views.full(member,
                engine.assign(member, id, body.toUserId(), body.version(), body.comment())));
    }

    @PostMapping(INSTANCES + "/{id}/outcome")
    @Operation(
            operationId = "reapplyApprovalOutcome",
            summary = "Make the outcome updates again after they were refused",
            description = "For a member who holds approvals.manage, when the request ended but a rule refused what "
                    + "the process sets on the record (the request shows the stable reason). It writes the same "
                    + "updates to the record as it is now, so the record version the caller read is sent back; it "
                    + "is refused while a newer request of the record is pending. Audited. " + VERSION + UNKNOWN)
    ApiResponse<ApprovalInstanceView> reapply(@PathVariable UUID id, @Valid @RequestBody ApprovalReapplyRequest body) {
        Member member = callers.require();
        return ApiResponse.of(views.full(member, engine.reapply(member, id, body.recordVersion(), body.version())));
    }

    // ---- the panel of a record ----

    @GetMapping(RECORD)
    @Operation(
            operationId = "getRecordApproval",
            summary = "The approval panel of a record",
            description = "Whether the record is locked, the pending request or the last one that ended, the "
                    + "processes the caller could submit it under, and what the caller may do now. The record must "
                    + "be readable by the caller; otherwise it does not exist. A field the caller may not read is "
                    + "never shown, and neither is any value of the record.")
    ApiResponse<RecordApprovalView> record(@PathVariable String objectApiName, @PathVariable UUID id) {
        return ApiResponse.of(views.record(callers.require(), objectApiName, id));
    }

    @PostMapping(RECORD)
    @Operation(
            operationId = "submitRecordForApproval",
            summary = "Submit a record for approval",
            description = "For a member who may edit the record. The process is the one named, or the only active one "
                    + "whose entry criteria the record meets (none, or more than one, is a CONFLICT that says so). "
                    + "CONFLICT when the first step names nobody who can decide, when the plan has no approvals or "
                    + "when another person's request is pending; a request the caller submitted already is returned "
                    + "unchanged. The record is locked in the same transaction. " + COMMENT)
    ResponseEntity<ApiResponse<ApprovalInstanceView>> submit(@PathVariable String objectApiName,
            @PathVariable UUID id, @Valid @RequestBody SubmitApprovalRequest body) {
        Member member = callers.require();
        InstanceRow submitted = engine.submit(member, objectApiName, id, body.process(), body.comment());
        return ResponseEntity.status(HttpStatus.CREATED).header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(ApiResponse.of(views.full(member, submitted)));
    }

    // ---- delegations ----

    @GetMapping(APPROVALS + "/delegations")
    @Operation(
            operationId = "listApprovalDelegations",
            summary = "Delegations",
            description = "The caller's own by default. Name a delegator, or ask for all, only with approvals.manage "
                    + "(FORBIDDEN otherwise).")
    ApiResponse<List<ApprovalDelegationView>> delegationList(
            @RequestParam(name = "delegator", required = false) UUID delegator,
            @RequestParam(name = "all", required = false, defaultValue = "false") boolean all) {
        return ApiResponse.of(delegations.list(callers.require(), delegator, all));
    }

    @PostMapping(APPROVALS + "/delegations")
    @Operation(
            operationId = "createApprovalDelegation",
            summary = "Name a delegate for a range of days",
            description = "The delegate is asked beside the delegator (the delegator keeps the task) whenever a step "
                    + "becomes active within the range; tasks already assigned are not moved. No chains and none "
                    + "to oneself; the range is at most 366 days, both days count, in the time zone of the "
                    + "delegation. The delegate must be an active member who holds a licence. A member makes one "
                    + "for themselves; approvals.manage is needed for another person. Audited.")
    ResponseEntity<ApiResponse<ApprovalDelegationView>> delegationCreate(
            @Valid @RequestBody CreateApprovalDelegationRequest body) {
        return ResponseEntity.status(HttpStatus.CREATED).header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(ApiResponse.of(delegations.create(callers.require(), body)));
    }

    @DeleteMapping(APPROVALS + "/delegations/{id}")
    @Operation(
            operationId = "revokeApprovalDelegation",
            summary = "End a delegation",
            description = "The delegator, or a member who holds approvals.manage. Tasks the delegate already holds "
                    + "stay. NOT_FOUND for a delegation that does not exist.")
    ResponseEntity<Void> delegationRevoke(@PathVariable UUID id) {
        delegations.revoke(callers.require(), id);
        return ResponseEntity.noContent().header(HttpHeaders.CACHE_CONTROL, "no-store").build();
    }

    // ---- pickers ----

    @GetMapping(APPROVALS + "/people")
    @Operation(
            operationId = "searchApprovalPeople",
            summary = "People who can be asked to decide",
            description = "Active members who hold the licence their profile needs, found by a part of their name, at "
                    + "most 50 at a time; the text must match, so the list of members cannot be read page by page. "
                    + "For the pickers of a reassignment, a delegation and an approver rule. Names and identifiers "
                    + "only.")
    ApiResponse<List<ApprovalPersonView>> people(@RequestParam(name = "text", required = false) String text,
            @RequestParam(name = "limit", required = false) Integer limit) {
        callers.require();
        return ApiResponse.of(resolve.people(text, limit));
    }

    @GetMapping(ApiPaths.METADATA_APPROVALS + "/choices")
    @Operation(
            operationId = "getApprovalChoices",
            summary = "The profiles, roles and public groups an approver rule can name",
            description = "For the builder of approval processes. Needs approvals.manage.")
    ApiResponse<ApprovalChoicesView> choices() {
        return ApiResponse.of(resolve.choices(callers.require()));
    }

    // ---- the preview for an administrator ----

    @PostMapping(ApiPaths.METADATA_APPROVALS + "/{objectApiName}/resolve")
    @Operation(
            operationId = "resolveApprovalProcess",
            summary = "Who would each step ask for a record?",
            description = "Works out, for a record the caller can read, the people each of the given steps would "
                    + "ask, the people left out and why (inactive, unlicensed, the submitter), and whether the "
                    + "fallback approver would be used or nobody could be asked. The very resolution a real request "
                    + "uses; nothing is stored, locked or sent. Needs approvals.manage.")
    ApiResponse<ApprovalResolutionView> resolve(@PathVariable String objectApiName,
            @Valid @RequestBody ResolveApprovalRequest body) {
        return ApiResponse.of(resolve.resolve(callers.require(), objectApiName, body));
    }
}
