package app.platform.inspection.internal;

import app.platform.data.BackgroundRecords;
import app.platform.data.DataRecord;
import app.platform.inspection.internal.ChangeRequestAccess.Reach;
import app.platform.inspection.internal.ChangeRequestStore.CommentRow;
import app.platform.inspection.internal.ChangeRequestStore.RequestRow;
import app.platform.inspection.internal.ChangeRequestViews.ChangeCommentView;
import app.platform.inspection.internal.ChangeRequestViews.ChangeRequestView;
import app.platform.inspection.internal.ChangeRequestViews.ChangeRequestsView;
import app.platform.inspection.internal.ChangeRequestViews.CreateChangeRequest;
import app.platform.inspection.internal.ChangeRequestViews.NewChangeComment;
import app.platform.inspection.internal.ChangeRequestViews.PublishPrefill;
import app.platform.inspection.internal.RegisterRules.Checked;
import app.platform.inspection.internal.RegisterRules.Raised;
import app.platform.inspection.internal.RegisterStore.Facts;
import app.platform.inspection.internal.TemplateViews.SuggestedAudience;
import app.platform.security.Ability;
import app.platform.security.Permissions;
import app.platform.sharedkernel.approval.ApprovalStartRefusal;
import app.platform.sharedkernel.approval.ApprovalStarter;
import app.platform.sharedkernel.directory.ReferenceLabels;
import app.platform.sheets.SheetLifecycle;
import app.platform.sheets.SheetLifecycle.Layout;
import app.platform.sheets.SheetMoveRefusal;
import app.platform.sheets.SheetStatus;
import app.platformapi.ApiException;
import app.platformapi.ErrorCode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Change requests on a published template (FA5, ADR-0191), as one controlled change (FA8, ADR-0202). A person who can
 * read a revision in use fills the register form, optionally points at the places on the sheet they mean, and asks. The
 * organization's approval process decides the request, step by step; the people asked to decide it make the revised
 * copy of the sheet and change it; and the last approval publishes it as the next revision.
 *
 * <p><strong>A request changes nothing until it is published.</strong> It is a record of its own beside the template.
 * The revised copy is a draft of the sheet held for the request: it is not read by anyone who only reads the template,
 * and the revision in use stays what it was until the last approval makes the copy the one in use. Only one change is
 * in
 * progress on a template at a time.
 *
 * <p><strong>Who sees what</strong> is {@link ChangeRequestAccess}'s answer, asked each time; <strong>who is asked to
 * decide it</strong> is {@link ChangeRequestTasks}'s. <strong>The places</strong> are checked against the version that
 * was read (ids of rows and columns, ADR-0173). <strong>The text</strong> is the organization's: it is stored, shown to
 * the people who may see the request, and never written to a log, an audit record, an event or an error.
 *
 * <p>Every change takes the template's lock first, then (for the copy) the sheet's own lock, always in that order.
 */
@Service
@Transactional
class ChangeRequests {

    private static final String NO_REQUEST = "This change request does not exist.";
    private static final String RETIRED_WORDS = "This template is retired, so no change can be asked for.";
    private static final String NO_PROCESS_WORDS = "A change request is decided by an approval, and this "
            + "organization has not set one up. Ask your administrator to make the approval process for "
            + "change requests in Setup.";
    private static final String IN_PROGRESS_WORDS = "A change is already in progress on this template. You can ask for "
            + "another when it has been published or declined.";
    private static final int NAME_LIMIT = 160;

    private final ChangeRequestAccess access;
    private final ChangeRequestStore store;
    private final ChangeRequestProperties properties;
    private final ChangeRequestChanges changes;
    private final SheetLifecycle sheets;
    private final BackgroundRecords records;
    private final ChangeRequestNotices notices;
    private final List<ReferenceLabels> labels;
    private final DepartmentAudiences departments;
    private final ChangeRequestHeaders headers;
    private final ApprovalStarter starter;
    private final ChangeRequestTasks tasks;
    private final RegisterStore registers;
    private final RegisterFacts facts;
    private final AudienceSuggestions suggestions;
    private final TemplateOptionStore optionStore;
    private final Permissions permissions;
    private final TemplateRevisions revisions;
    private final RecordSets sets;

    ChangeRequests(ChangeRequestAccess access, ChangeRequestStore store, ChangeRequestProperties properties,
            ChangeRequestChanges changes, SheetLifecycle sheets, BackgroundRecords records,
            ChangeRequestNotices notices, List<ReferenceLabels> labels, DepartmentAudiences departments,
            ChangeRequestHeaders headers, ApprovalStarter starter, ChangeRequestTasks tasks, RegisterStore registers,
            RegisterFacts facts, AudienceSuggestions suggestions, TemplateOptionStore optionStore,
            Permissions permissions, TemplateRevisions revisions, RecordSets sets) {
        this.sets = sets;
        this.access = access;
        this.store = store;
        this.properties = properties;
        this.changes = changes;
        this.sheets = sheets;
        this.records = records;
        this.notices = notices;
        this.labels = labels;
        this.departments = departments;
        this.headers = headers;
        this.starter = starter;
        this.tasks = tasks;
        this.registers = registers;
        this.facts = facts;
        this.suggestions = suggestions;
        this.optionStore = optionStore;
        this.permissions = permissions;
        this.revisions = revisions;
    }

    // ---- reading ----

    /** The requests the caller may see on the template, newest first, with what the screen may offer. */
    @Transactional(readOnly = true)
    ChangeRequestsView list(RecordSet set, UUID templateId, String status) {
        Reach reach = access.reach(set, templateId);
        Optional<ChangeRequestStatus> wanted = Optional.empty();
        if (status != null && !status.isBlank()) {
            wanted = Optional.of(ChangeRequestStatus.of(status).orElseThrow(() -> ApiException.validation("status",
                    "Choose OPEN, DECLINED or DONE.")));
        }
        List<RequestRow> rows = store.requests(templateId, reach.canReview() ? null : reach.userId());
        int open = (int) rows.stream().filter(row -> row.state().inProgress()).count();
        boolean inProgress = store.countInProgress(templateId) > 0;
        if (wanted.isPresent()) {
            ChangeRequestStatus only = wanted.get();
            rows = rows.stream().filter(row -> row.state() == only).toList();
        }
        Frame frame = frame(reach, rows);
        List<ChangeRequestView> views = rows.stream().map(row -> view(frame, row)).toList();
        return new ChangeRequestsView(views, reach.canReview(), frame.canRequest && !inProgress,
                frame.summary.active(), frame.summary.draft(), open, inProgress);
    }

    /** One request with its comments. */
    @Transactional(readOnly = true)
    ChangeRequestView get(RecordSet set, UUID templateId, UUID requestId) {
        Reach reach = access.reach(set, templateId);
        RequestRow row = access.request(reach, requestId);
        return view(frame(reach, List.of(row)), row);
    }

    /**
     * One request by its own identifier, for a screen that was given the request and not its template (the bell, the
     * approval inbox). The rest is as {@link #get}: a request of a template the caller cannot read, or one they may not
     * see, does not exist; and so does a request of another set than the application names.
     */
    @Transactional(readOnly = true)
    ChangeRequestView getAnywhere(RecordSet set, UUID requestId) {
        access.member();
        RequestRow row = store.findById(requestId).filter(found -> set.template().equals(found.templateObject()))
                .orElseThrow(() -> ApiException.notFound(NO_REQUEST));
        return get(set, row.templateId(), requestId);
    }

    /**
     * One request by the identifier of its review record, for a screen of the approval (the choice of a step knows the
     * record under review, not the request). The rest is as {@link #get}.
     */
    @Transactional(readOnly = true)
    ChangeRequestView getByReview(RecordSet set, UUID reviewId) {
        access.member();
        RequestRow row = store.findByHeader(reviewId).filter(found -> set.template().equals(found.templateObject()))
                .orElseThrow(() -> ApiException.notFound(NO_REQUEST));
        return get(set, row.templateId(), row.id());
    }

    /**
     * The page of the request a review record belongs to, for a bell row that names the record; nothing checked. The
     * application named is one of those that show the request's own set that the organization has.
     */
    @Transactional(readOnly = true)
    Optional<String> pathOfReview(UUID reviewId) {
        return store.findByHeader(reviewId).flatMap(row -> sets.ofTemplate(row.templateObject())
                .map(set -> RequestRecords.pathOf(sets.applicationFor(set, records.find(set.template(),
                        row.templateId()).map(found -> found.fields().get(Templates.APPLICATION)).orElse(null)),
                        row.templateId(), row.id())));
    }

    // ---- asking ----

    /**
     * Makes a request about a version the caller read, with the register form they filled and, if they like, the places
     * they mean. Needs only the right to read the template. The version must be one that is or was in use, every place
     * a comment names must be in it, and no other change may be in progress on the template.
     */
    ChangeRequestView create(RecordSet set, UUID templateId, CreateChangeRequest request) {
        Reach reach = access.reach(set, templateId);
        store.lock(templateId);
        DataRecord header = header(set, templateId);
        if (retired(header)) {
            refuse("create", "retired");
            throw new ApiException(ErrorCode.CONFLICT, RETIRED_WORDS);
        }
        SheetLifecycle.Summary summary = summary(set, templateId);
        if (summary.active() <= 0) {
            refuse("create", "nothing_in_use");
            throw new ApiException(ErrorCode.CONFLICT, "This template has no revision in use, so nothing can be asked "
                    + "about yet.");
        }
        if (store.countInProgress(templateId) > 0) {
            refuse("create", "in_progress");
            throw new ApiException(ErrorCode.CONFLICT, IN_PROGRESS_WORDS);
        }
        Map<String, List<String>> problems = new LinkedHashMap<>();
        Checked<Raised> form = RegisterRules.raise(request.register());
        form.problems().forEach((field, words) -> problems.put("register." + field, words));
        Layout layout = readLayout(set, templateId, request.versionNo(), problems);
        List<NewChangeComment> asked = request.comments() == null ? List.of() : request.comments();
        if (asked.size() > properties.maxFirstComments()) {
            problems.put("comments", List.of("A request starts with at most " + properties.maxFirstComments()
                    + " places; add the rest after it is made."));
        }
        List<ChangeAnchor> anchors = new ArrayList<>();
        for (int i = 0; i < asked.size(); i++) {
            anchors.add(checkedComment("comments[" + i + "]", asked.get(i), layout, problems));
        }
        if (!problems.isEmpty()) {
            refuse("create", "invalid");
            throw ApiException.validation(problems);
        }
        if (store.countOnTemplate(templateId) >= properties.maxPerTemplate()) {
            refuse("create", "most_per_template");
            throw new ApiException(ErrorCode.CONFLICT, "This template has the most change requests it can have ("
                    + properties.maxPerTemplate() + ").");
        }
        if (store.countOpenBy(templateId, reach.userId()) >= properties.maxOpenPerPerson()) {
            refuse("create", "most_open");
            throw new ApiException(ErrorCode.CONFLICT, "You already have " + properties.maxOpenPerPerson()
                    + " open requests on this template. Wait for an answer before asking for more.");
        }
        Facts platform = facts.of(set, templateId, reach.userId(), reach.membershipId());
        UUID reviewId = startReview(reach, templateId, header);
        RequestRow made = store.insertRequest(set.template(), templateId, request.versionNo(),
                RegisterRules.titleOf(form.value().changeRequested()), reach.userId(), platform.department(),
                reviewId);
        registers.insert(made.id(), templateId, form.value(), platform, reach.userId());
        int places = 0;
        for (int i = 0; i < asked.size(); i++) {
            ChangeAnchor anchor = anchors.get(i);
            store.insertComment(made.id(), templateId, anchor, anchor.label(layout),
                    ChangeText.clean(asked.get(i).text()), reach.userId());
            if (anchor.kind() != ChangeAnchor.Kind.TEMPLATE) {
                places++;
            }
        }
        changes.requested(set, reach.userId(), templateId, made.id(), made.versionNo(), asked.size(), places);
        tell(set, header.ownerId(), reach.userId(), templateId, made.id(), "requested",
                "chg:" + made.id() + ":requested");
        return view(frame(reach, List.of(made)), made);
    }

    /**
     * Makes the record the approval engine reviews and sends it to the organization's process for change requests, in
     * the same transaction as the request (FA6E, ADR-0199). Every request is decided by its approval: when the
     * organization has no such process the request is refused with fixed words that say what to set up. A start that
     * is refused (for example the first step names nobody) refuses the request with the engine's fixed words: nothing
     * is left behind.
     *
     * @return the identifier of the record
     */
    private UUID startReview(Reach reach, UUID templateId, DataRecord template) {
        RecordSet set = reach.set();
        if (!starter.available(set.request())) {
            refuse("create", "no_process");
            throw new ApiException(ErrorCode.CONFLICT, NO_PROCESS_WORDS);
        }
        Object name = template.fields().get(Templates.NAME);
        String shown = name instanceof String text && !text.isBlank() ? text.strip() : "Template";
        DataRecord review = headers.create(set, reach.userId(), template.ownerId(), templateId,
                shown.length() > NAME_LIMIT ? shown.substring(0, NAME_LIMIT) : shown,
                text(template, Templates.APPLICATION));
        try {
            starter.startForPerson(set.request(), review.id(), reach.userId());
        } catch (ApprovalStartRefusal refused) {
            refuse("create", "review_" + refused.reason());
            throw new ApiException(ErrorCode.CONFLICT, refused.words());
        }
        return review.id();
    }

    /** Adds a comment to a request that is open: by the person who asked, or by a person who may review. */
    ChangeRequestView comment(RecordSet set, UUID templateId, UUID requestId, NewChangeComment comment) {
        Reach reach = access.reach(set, templateId);
        store.lock(templateId);
        RequestRow row = access.request(reach, requestId);
        if (!row.state().takesComments()) {
            refuse("comment", "closed");
            throw new ApiException(ErrorCode.CONFLICT, "This request is closed, so it takes no more comments.");
        }
        if (store.countComments(requestId) >= properties.maxCommentsPerRequest()) {
            refuse("comment", "most_comments");
            throw new ApiException(ErrorCode.CONFLICT, "This request has the most comments it can have ("
                    + properties.maxCommentsPerRequest() + ").");
        }
        Map<String, List<String>> problems = new LinkedHashMap<>();
        Layout layout = sheets.layoutOf(set.template(), templateId, row.versionNo()).orElseThrow();
        ChangeAnchor anchor = checkedComment("", comment, layout, problems);
        if (!problems.isEmpty()) {
            refuse("comment", "invalid");
            throw ApiException.validation(problems);
        }
        CommentRow written = store.insertComment(requestId, templateId, anchor, anchor.label(layout),
                ChangeText.clean(comment.text()), reach.userId());
        changes.commented(set, reach.userId(), templateId, requestId, written.id(), anchor.kind().name());
        // The other side is told: the owner when the person who asked writes, the person who asked when anyone else
        // does.
        UUID other = row.createdBy().equals(reach.userId()) ? header(set, templateId).ownerId() : row.createdBy();
        tell(set, other, reach.userId(), templateId, requestId, "commented", "chg:" + written.id() + ":commented");
        return view(frame(reach, List.of(row)), row);
    }

    // ---- the revised copy ----

    /**
     * Makes the revised copy of the sheet for a request: a draft that is a copy of the revision in use now, held for
     * the
     * request, which is not read by anyone who only reads the template. Only a person asked to decide the request now
     * (and the administrator) may; the revision in use is never edited.
     *
     * @throws ApiException {@code NOT_FOUND}, {@code FORBIDDEN}, {@code CONFLICT} (the request is not waiting for an
     *         approval, a copy already exists, no revision is in use, the sheet has the most versions it can have)
     */
    ChangeRequestView createCopy(RecordSet set, UUID templateId, UUID requestId) {
        Reach reach = access.reach(set, templateId);
        store.lock(templateId);
        RequestRow row = access.request(reach, requestId);
        if (row.state() != ChangeRequestStatus.OPEN) {
            refuse("copy", "closed");
            throw new ApiException(ErrorCode.CONFLICT, "This request is answered, so no copy can be made for it.");
        }
        if (!tasks.mayAct(row, reach.userId(), reach.membershipId())) {
            refuse("copy", "forbidden");
            throw new ApiException(ErrorCode.FORBIDDEN, "Only the people asked to decide this request can make the "
                    + "revised copy.");
        }
        UUID pending = tasks.pending(row).orElseThrow(() -> {
            refuse("copy", "not_waiting");
            return new ApiException(ErrorCode.CONFLICT, "This request is not waiting for an approval. Send it for "
                    + "approval again, then make the copy.");
        });
        SheetLifecycle.Summary summary = summary(set, templateId);
        if (summary.active() <= 0 || retired(header(set, templateId))) {
            refuse("copy", "nothing_in_use");
            throw new ApiException(ErrorCode.CONFLICT, "This template has no revision in use to copy.");
        }
        SheetLifecycle.Started started;
        try {
            started = sheets.startHeldDraft(set.template(), templateId, summary.active(), pending, reach.userId());
        } catch (SheetMoveRefusal refusal) {
            refuse("copy", refusal.reason().name().toLowerCase(java.util.Locale.ROOT));
            throw new ApiException(ErrorCode.CONFLICT, refusal.reason() == SheetMoveRefusal.Reason.DRAFT_EXISTS
                    ? "A draft of this sheet already exists from before. Ask your administrator to finish or "
                            + "withdraw it first."
                    : refusal.getMessage());
        }
        changes.copied(set, reach.userId(), templateId, requestId, started.versionNo());
        return view(frame(reach, List.of(row)), row);
    }

    // ---- rules ----

    /** The places of the version that was read; a problem is put in the map when it cannot be asked about. */
    private Layout readLayout(RecordSet set, UUID templateId, Integer versionNo, Map<String, List<String>> problems) {
        Optional<Layout> layout = versionNo == null ? Optional.empty()
                : sheets.layoutOf(set.template(), templateId, versionNo);
        // A version that nobody but its authors may read answers like one that does not exist.
        if (layout.isEmpty() || !layout.get().status().readableByReaders()) {
            problems.put("versionNo", List.of("There is no such revision to ask about."));
            return new Layout(SheetStatus.DRAFT, Map.of(), Map.of());
        }
        return layout.get();
    }

    /** Checks one comment: its text and its place. The place is the whole template when none is named. */
    private ChangeAnchor checkedComment(String place, NewChangeComment comment, Layout layout,
            Map<String, List<String>> problems) {
        String at = place.isEmpty() ? "" : place + ".";
        ChangeText.problem("comment", comment.text(), properties.maxCommentChars(), true)
                .ifPresent(words -> problems.put(at + "text", List.of(words)));
        ChangeAnchor anchor = new ChangeAnchor(comment.rowId(), comment.columnId());
        Optional<String> shape = anchor.shapeProblem();
        if (shape.isPresent()) {
            problems.put(at + "place", List.of(shape.get()));
        } else if (!anchor.presentIn(layout) && !problems.containsKey("versionNo")) {
            problems.put(at + "place", List.of("That row or column is not in the revision you read."));
        }
        return anchor;
    }

    // ---- telling people ----

    /** Tells one person, unless it is the person who did it or there is nobody to tell. */
    private void tell(RecordSet set, UUID recipient, UUID actor, UUID templateId, UUID requestId, String event,
            String key) {
        notices.tell(set, recipient, actor, templateId, requestId, event, key);
    }

    // ---- views ----

    /** What building the views of a few requests needs, read once. */
    private final class Frame {

        private final Reach reach;
        private final SheetLifecycle.Summary summary;
        private final boolean canRequest;
        private final boolean mayPublish;
        private final Map<UUID, List<CommentRow>> comments = new HashMap<>();
        private final Map<UUID, String> names;
        private final Map<Integer, Optional<SheetStatus>> statuses = new HashMap<>();
        private final String templateName;
        private final String templateNumber;
        private final Optional<SheetLifecycle.HeldDraft> held;
        private Optional<Layout> active;
        private boolean activeRead;

        private Frame(Reach reach, List<RequestRow> rows) {
            this.reach = reach;
            this.summary = summary(reach.set(), reach.templateId());
            DataRecord template = header(reach.set(), reach.templateId());
            this.canRequest = summary.active() > 0 && !retired(template);
            this.mayPublish = permissions.has(reach.membershipId(), Ability.SHEETS_PUBLISH);
            this.held = sheets.heldDraft(reach.set().template(), reach.templateId());
            this.templateName = text(template, Templates.NAME);
            this.templateNumber = text(template, Templates.NUMBER);
            for (CommentRow comment : store.comments(rows.stream().map(RequestRow::id).toList())) {
                comments.computeIfAbsent(comment.requestId(), key -> new ArrayList<>()).add(comment);
            }
            Set<UUID> people = new HashSet<>();
            rows.forEach(row -> people.add(row.createdBy()));
            comments.values().forEach(list -> list.forEach(comment -> people.add(comment.createdBy())));
            this.names = nameOf(people);
        }

        /** The places of the version in use, read the first time a comment asks. */
        private Optional<Layout> active() {
            if (!activeRead) {
                active = summary.active() > 0
                        ? sheets.layoutOf(reach.set().template(), reach.templateId(), summary.active())
                        : Optional.empty();
                activeRead = true;
            }
            return active;
        }

        private Optional<SheetStatus> statusOf(int versionNo) {
            return statuses.computeIfAbsent(versionNo,
                    number -> sheets.statusOf(reach.set().template(), reach.templateId(), number));
        }
    }

    private Frame frame(Reach reach, List<RequestRow> rows) {
        return new Frame(reach, rows);
    }

    private ChangeRequestView view(Frame frame, RequestRow row) {
        ChangeRequestStatus status = row.state();
        boolean mine = row.createdBy().equals(frame.reach.userId());
        List<CommentRow> written = frame.comments.getOrDefault(row.id(), List.of());
        List<ChangeCommentView> comments = new ArrayList<>();
        int total = 0;
        int there = 0;
        for (CommentRow comment : written) {
            ChangeAnchor anchor = comment.anchor();
            boolean inActive = anchor.kind() == ChangeAnchor.Kind.TEMPLATE
                    || frame.active().map(anchor::presentIn).orElse(false);
            if (anchor.kind() != ChangeAnchor.Kind.TEMPLATE) {
                total++;
                if (inActive) {
                    there++;
                }
            }
            comments.add(new ChangeCommentView(comment.id(), anchor.kind().name(), comment.rowId(),
                    comment.columnId(), comment.label() == null ? "" : comment.label(), inActive, comment.body(),
                    comment.createdAt(), frame.names.get(comment.createdBy()),
                    comment.createdBy().equals(frame.reach.userId())));
        }
        boolean open = status == ChangeRequestStatus.OPEN;
        boolean asked = open && tasks.mayAct(row, frame.reach.userId(), frame.reach.membershipId());
        // The version this request is about: its revised copy while it is prepared, the version it published once done.
        Integer resulting = row.resultingVersion();
        if (resulting == null && open && frame.held.isPresent()) {
            resulting = frame.held.get().versionNo();
        }
        Optional<SheetStatus> became = resulting == null ? Optional.empty() : frame.statusOf(resulting);
        Integer revision = status == ChangeRequestStatus.DONE && resulting != null
                ? revisions.of(frame.reach.set(), row.templateId(), resulting) : null;
        boolean canCreateCopy = asked && frame.canRequest && frame.held.isEmpty();
        boolean canChangeCopy = asked && frame.held.isPresent();
        return new ChangeRequestView(row.id(), row.title(), status.name(), row.versionNo(),
                frame.names.get(row.createdBy()), row.requesterDepartment(), mine, row.createdAt(), row.decidedAt(),
                resulting, became.map(SheetStatus::name).orElse(null), total, there,
                status.takesComments() && (frame.reach.canReview() || mine), canCreateCopy, canChangeCopy, comments,
                row.templateId(), frame.templateName, frame.templateNumber, row.headerId(), revision,
                open && frame.mayPublish ? prefill(row) : null);
    }

    /** What the window of the last approval starts from: the suggestion of the step that asked and the options now. */
    private PublishPrefill prefill(RequestRow row) {
        AudienceSuggestions.Held held = tasks.pending(row).map(suggestions::held)
                .orElse(new AudienceSuggestions.Held(List.of(), 0));
        TemplateOptionStore.Options options = optionStore.find(row.templateId());
        Map<String, String> locationLabels = held.places().isEmpty() ? Map.of()
                : departments.labels(Templates.LOCATION);
        Map<String, String> departmentLabels = held.places().isEmpty() ? Map.of()
                : departments.labels(Templates.DEPARTMENT);
        List<SuggestedAudience> places = held.places().stream().map(place -> new SuggestedAudience(place.location(),
                locationLabels.getOrDefault(place.location(), place.location()), place.department(),
                place.department() == null ? null
                        : departmentLabels.getOrDefault(place.department(), place.department()))).toList();
        return new PublishPrefill(places, held.dropped(), options.viewOnly(), options.allowDownload());
    }

    private static String text(DataRecord template, String field) {
        Object value = template.fields().get(field);
        return value instanceof String text && !text.isBlank() ? text : null;
    }

    /** The names of the people, for the active members among them; a person who left has none. */
    private Map<UUID, String> nameOf(Set<UUID> people) {
        if (people.isEmpty()) {
            return Map.of();
        }
        return labels.stream().filter(one -> "User".equals(one.object())).findFirst()
                .map(one -> one.labels(people)).orElse(Map.of());
    }

    // ---- helpers ----

    private DataRecord header(RecordSet set, UUID templateId) {
        return records.find(set.template(), templateId)
                .orElseThrow(() -> ApiException.notFound("This template does not exist."));
    }

    private static boolean retired(DataRecord header) {
        return Boolean.TRUE.equals(header.fields().get(Templates.RETIRED));
    }

    private SheetLifecycle.Summary summary(RecordSet set, UUID templateId) {
        return sheets.summaries(set.template(), List.of(templateId)).getOrDefault(templateId,
                new SheetLifecycle.Summary(0, 0, 0, 0, 0, 0, 0));
    }

    private void refuse(String act, String reason) {
        changes.refused(act, reason);
    }
}
