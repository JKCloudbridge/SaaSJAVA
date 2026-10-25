package app.platform.inspection.internal;

import app.platform.data.BackgroundRecords;
import app.platform.data.BackgroundWriteException;
import app.platform.data.DataRecord;
import app.platform.inspection.internal.ChangeRequestAccess.Reach;
import app.platform.inspection.internal.ChangeRequestStore.CommentRow;
import app.platform.inspection.internal.ChangeRequestStore.RequestRow;
import app.platform.inspection.internal.ChangeRequestViews.ChangeCommentView;
import app.platform.inspection.internal.ChangeRequestViews.ChangeRequestView;
import app.platform.inspection.internal.ChangeRequestViews.ChangeRequestsView;
import app.platform.inspection.internal.ChangeRequestViews.CreateChangeRequest;
import app.platform.inspection.internal.ChangeRequestViews.NewChangeComment;
import app.platform.inspection.internal.ChangeRequestViews.TurnIntoVersionRequest;
import app.platform.inspection.internal.ChangeRequestViews.TurnedView;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Change requests on a published template (FA5, ADR-0191): a person who can read a version in use asks for a change and
 * points at the places they mean; the people who may change the template answer; and when agreed, the requests are
 * turned into a new draft version that goes through approval like any other.
 *
 * <p><strong>A request changes nothing.</strong> It is a record of its own beside the template. It never touches the
 * header, the active version or any other version; the only version it can cause is a draft that the owner's own
 * act (turning requests into a version) makes, and a draft is not read by anyone who may only read the template.
 * The version a request read stays what it was, and the comments stay with the request whatever happens to that
 * version, which is how a rejected version keeps its comments.
 *
 * <p><strong>Who sees what</strong> is {@link ChangeRequestAccess}'s answer, asked each time.
 * <strong>The places</strong> are checked against the version that was read (ids of rows and columns, ADR-0173); a
 * later version is asked "is it still there?" and a comment whose place is gone says so.
 * <strong>The text</strong> is the organization's: it is stored, shown to the people who may see the request, and
 * never written to a log, an audit record, an event or an error.
 *
 * <p>Every change takes the template's lock first, then (for turning) the sheet's own lock, always in that order.
 */
@Service
@Transactional
class ChangeRequests {

    private static final String NO_REQUEST = "This change request does not exist.";
    private static final String RETIRED_WORDS = "This template is retired, so no change can be asked for.";
    private static final String RACE_WORDS = "This request was answered by someone else just now. Reload the page.";
    private static final String NO_PROCESS_WORDS = "A change request is decided by an approval, and this "
            + "organization has not set one up. Ask your administrator to make the approval process for "
            + "change requests in Setup.";
    private static final String NOT_ACCEPTED_WORDS = "This request has not been accepted yet, so it cannot be turned "
            + "into a revision.";
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

    ChangeRequests(ChangeRequestAccess access, ChangeRequestStore store, ChangeRequestProperties properties,
            ChangeRequestChanges changes, SheetLifecycle sheets, BackgroundRecords records,
            ChangeRequestNotices notices, List<ReferenceLabels> labels, DepartmentAudiences departments,
            ChangeRequestHeaders headers, ApprovalStarter starter) {
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
    }

    // ---- reading ----

    /** The requests the caller may see on the template, newest first, with what the screen may offer. */
    @Transactional(readOnly = true)
    ChangeRequestsView list(UUID templateId, String status) {
        Reach reach = access.reach(templateId);
        Optional<ChangeRequestStatus> wanted = Optional.empty();
        if (status != null && !status.isBlank()) {
            wanted = Optional.of(ChangeRequestStatus.of(status).orElseThrow(() -> ApiException.validation("status",
                    "Choose OPEN, ACCEPTED, DECLINED or DONE.")));
        }
        List<RequestRow> rows = store.requests(templateId, reach.canReview() ? null : reach.userId());
        // Open means still waiting for someone: not yet answered, or accepted and not yet made into a version.
        int open = (int) rows.stream().filter(row -> row.state() == ChangeRequestStatus.OPEN
                || row.state() == ChangeRequestStatus.ACCEPTED).count();
        if (wanted.isPresent()) {
            ChangeRequestStatus only = wanted.get();
            rows = rows.stream().filter(row -> row.state() == only).toList();
        }
        Frame frame = frame(reach, rows);
        List<ChangeRequestView> views = rows.stream().map(row -> view(frame, row)).toList();
        return new ChangeRequestsView(views, reach.canReview(), frame.canRequest, frame.summary.active(),
                frame.summary.draft(), open);
    }

    /** One request with its comments. */
    @Transactional(readOnly = true)
    ChangeRequestView get(UUID templateId, UUID requestId) {
        Reach reach = access.reach(templateId);
        RequestRow row = visibleRequest(reach, requestId);
        return view(frame(reach, List.of(row)), row);
    }

    /**
     * One request by its own identifier, for a screen that was given the request and not its template (the bell, the
     * approval inbox). The rest is as {@link #get}: a request of a template the caller cannot read, or one they may not
     * see, does not exist.
     */
    @Transactional(readOnly = true)
    ChangeRequestView getAnywhere(UUID requestId) {
        access.member();
        RequestRow row = store.findById(requestId).orElseThrow(() -> ApiException.notFound(NO_REQUEST));
        return get(row.templateId(), requestId);
    }

    /** The page of the request a review record belongs to, for a bell row that names the record; nothing checked. */
    @Transactional(readOnly = true)
    Optional<String> pathOfReview(UUID reviewId) {
        return store.findByHeader(reviewId).map(row -> RequestRecords.pathOf(row.templateId(), row.id()));
    }

    // ---- asking ----

    /**
     * Makes a request about a version the caller read, with its first comments. Needs only the right to read the
     * template. The version must be one that is or was in use, and every place a comment names must be in it.
     */
    ChangeRequestView create(UUID templateId, CreateChangeRequest request) {
        Reach reach = access.reach(templateId);
        store.lock(templateId);
        DataRecord header = header(templateId);
        if (retired(header)) {
            refuse("create", "retired");
            throw new ApiException(ErrorCode.CONFLICT, RETIRED_WORDS);
        }
        SheetLifecycle.Summary summary = summary(templateId);
        if (summary.active() <= 0) {
            refuse("create", "nothing_in_use");
            throw new ApiException(ErrorCode.CONFLICT, "This template has no revision in use, so nothing can be asked "
                    + "about yet.");
        }
        Map<String, List<String>> problems = new LinkedHashMap<>();
        ChangeText.problem("title", request.title(), properties.maxTitleChars(), false)
                .ifPresent(words -> problems.put("title", List.of(words)));
        Layout layout = readLayout(templateId, request.versionNo(), problems);
        List<NewChangeComment> asked = request.comments() == null ? List.of() : request.comments();
        if (asked.size() > properties.maxFirstComments()) {
            problems.put("comments", List.of("A request starts with at most " + properties.maxFirstComments()
                    + " comments; add the rest after it is made."));
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
        UUID reviewId = startReview(reach, templateId, header);
        RequestRow made = store.insertRequest(templateId, request.versionNo(), ChangeText.clean(request.title()),
                reach.userId(), departments.departmentsOf(reach.membershipId()), reviewId);
        int places = 0;
        for (int i = 0; i < asked.size(); i++) {
            ChangeAnchor anchor = anchors.get(i);
            store.insertComment(made.id(), templateId, anchor, anchor.label(layout),
                    ChangeText.clean(asked.get(i).text()), reach.userId());
            if (anchor.kind() != ChangeAnchor.Kind.TEMPLATE) {
                places++;
            }
        }
        changes.requested(reach.userId(), templateId, made.id(), made.versionNo(), asked.size(), places);
        tell(header.ownerId(), reach.userId(), templateId, made.id(), "requested", "chg:" + made.id() + ":requested");
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
        if (!starter.available(RequestRecords.OBJECT)) {
            refuse("create", "no_process");
            throw new ApiException(ErrorCode.CONFLICT, NO_PROCESS_WORDS);
        }
        Object name = template.fields().get(Templates.NAME);
        String shown = name instanceof String text && !text.isBlank() ? text.strip() : "Template";
        DataRecord review = headers.create(reach.userId(), template.ownerId(), templateId,
                shown.length() > NAME_LIMIT ? shown.substring(0, NAME_LIMIT) : shown);
        try {
            starter.startForPerson(RequestRecords.OBJECT, review.id(), reach.userId());
        } catch (ApprovalStartRefusal refused) {
            refuse("create", "review_" + refused.reason());
            throw new ApiException(ErrorCode.CONFLICT, refused.words());
        }
        return review.id();
    }

    /** Adds a comment to a request that is open or accepted: by the person who asked, or by a person who may review. */
    ChangeRequestView comment(UUID templateId, UUID requestId, NewChangeComment comment) {
        Reach reach = access.reach(templateId);
        store.lock(templateId);
        RequestRow row = visibleRequest(reach, requestId);
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
        Layout layout = sheets.layoutOf(Templates.OBJECT, templateId, row.versionNo()).orElseThrow();
        ChangeAnchor anchor = checkedComment("", comment, layout, problems);
        if (!problems.isEmpty()) {
            refuse("comment", "invalid");
            throw ApiException.validation(problems);
        }
        CommentRow written = store.insertComment(requestId, templateId, anchor, anchor.label(layout),
                ChangeText.clean(comment.text()), reach.userId());
        changes.commented(reach.userId(), templateId, requestId, written.id(), anchor.kind().name());
        // The other side is told: the owner when the person who asked writes, the person who asked when anyone else
        // does.
        UUID other = row.createdBy().equals(reach.userId()) ? header(templateId).ownerId() : row.createdBy();
        tell(other, reach.userId(), templateId, requestId, "commented", "chg:" + written.id() + ":commented");
        return view(frame(reach, List.of(row)), row);
    }

    // ---- reopening ----

    /**
     * Reopens a request whose new version was rejected or withdrawn, so it can be turned into a version again. Whether
     * a request is accepted or declined is decided by its approval and by nothing else (ADR-0199). Needs the right to
     * change the template.
     */
    ChangeRequestView reopen(UUID templateId, UUID requestId) {
        Reach reach = access.reach(templateId);
        store.lock(templateId);
        RequestRow row = visibleRequest(reach, requestId);
        requireReview(reach, "reopen");
        requireMove(row, ChangeRequestStatus.OPEN);
        Optional<SheetStatus> ended = row.resultingVersion() == null ? Optional.empty()
                : sheets.statusOf(Templates.OBJECT, templateId, row.resultingVersion());
        if (ended.isEmpty() || (ended.get() != SheetStatus.REJECTED && ended.get() != SheetStatus.WITHDRAWN)) {
            refuse("reopen", "version_used");
            throw new ApiException(ErrorCode.CONFLICT, "The revision this request became was not rejected or "
                    + "withdrawn, so the request cannot be reopened.");
        }
        if (row.headerId() == null || !store.reopenAccepted(requestId, reach.userId())) {
            refuse("reopen", "raced");
            throw new ApiException(ErrorCode.CONFLICT, RACE_WORDS);
        }
        followOnReviewRecord(row, ChangeRequestStatus.ACCEPTED);
        changes.answered(reach.userId(), templateId, requestId, "reopened");
        RequestRow now = store.find(templateId, requestId).orElseThrow();
        return view(frame(reach, List.of(now)), now);
    }

    /**
     * Turns requests into the next version of the template: a draft that is a copy of the version in use now (not of
     * the version each person read, so that nothing already approved is lost; the comments find their places by
     * id). When a draft already exists the requests are linked to it and it is left as it is. The draft then goes
     * through approval like any other. Needs the right to change the template.
     */
    TurnedView turn(UUID templateId, TurnIntoVersionRequest request) {
        Reach reach = access.reach(templateId);
        requireReview(reach, "turn");
        Set<UUID> ids = new LinkedHashSet<>(request.requestIds());
        if (ids.size() > properties.maxTurnedAtOnce()) {
            throw ApiException.validation("requestIds", "Turn at most " + properties.maxTurnedAtOnce()
                    + " requests into a revision at once.");
        }
        store.lock(templateId);
        DataRecord header = header(templateId);
        if (retired(header)) {
            refuse("turn", "retired");
            throw new ApiException(ErrorCode.CONFLICT, RETIRED_WORDS);
        }
        SheetLifecycle.Summary summary = summary(templateId);
        int base = summary.active() > 0 ? summary.active() : summary.approved();
        if (base <= 0) {
            refuse("turn", "nothing_in_use");
            throw new ApiException(ErrorCode.CONFLICT, "This template has no revision in use to start from.");
        }
        List<RequestRow> rows = new ArrayList<>();
        for (UUID id : ids) {
            RequestRow row = store.find(templateId, id).orElseThrow(() -> ApiException.notFound(NO_REQUEST));
            if (!row.state().canBeTurned()) {
                refuse("turn", "not_open");
                throw new ApiException(ErrorCode.CONFLICT, "A request that is already answered or turned into a "
                        + "revision cannot be turned into another. Reload the page.");
            }
            if (row.state() != ChangeRequestStatus.ACCEPTED) {
                // Its review has not ended approved: turning it now would be a way round the process (ADR-0199).
                refuse("turn", "not_accepted");
                throw new ApiException(ErrorCode.CONFLICT, NOT_ACCEPTED_WORDS);
            }
            rows.add(row);
        }
        SheetLifecycle.Started started;
        try {
            started = sheets.startDraft(Templates.OBJECT, templateId, base, reach.userId());
        } catch (SheetMoveRefusal refusal) {
            refuse("turn", refusal.reason().name().toLowerCase(java.util.Locale.ROOT));
            throw new ApiException(ErrorCode.CONFLICT, refusal.getMessage());
        }
        for (RequestRow row : rows) {
            if (!store.answer(row.id(), row.state(), ChangeRequestStatus.DONE, null, started.versionNo(),
                    reach.userId())) {
                refuse("turn", "raced");
                throw new ApiException(ErrorCode.CONFLICT, RACE_WORDS);
            }
            followOnReviewRecord(row, ChangeRequestStatus.DONE);
            tell(row.createdBy(), reach.userId(), templateId, row.id(), "done", "chg:" + row.id() + ":done");
        }
        changes.turned(reach.userId(), templateId, started.versionNo(), started.created(), rows.size());
        return new TurnedView(started.versionNo(), started.created(), rows.size());
    }

    // ---- rules ----

    /** Keeps the copy of the status on the review record in step; a refusal undoes the act that asked. */
    private void followOnReviewRecord(RequestRow row, ChangeRequestStatus status) {
        try {
            headers.status(row.headerId(), status);
        } catch (BackgroundWriteException refused) {
            // The record is the platform's own: a refusal is a fault, not an answer for the person. The reason is a
            // stable key and never a value.
            throw new IllegalStateException("The status of a change request could not follow on its review record ("
                    + refused.reason().key() + ")");
        }
    }

    private RequestRow visibleRequest(Reach reach, UUID requestId) {
        RequestRow row = store.find(reach.templateId(), requestId).orElse(null);
        if (row == null || !access.sees(reach, row)) {
            throw ApiException.notFound(NO_REQUEST);
        }
        return row;
    }

    private void requireReview(Reach reach, String act) {
        if (!reach.canReview()) {
            refuse(act, "forbidden");
            throw new ApiException(ErrorCode.FORBIDDEN);
        }
    }

    private void requireMove(RequestRow row, ChangeRequestStatus to) {
        if (!row.state().canMoveTo(to)) {
            refuse("answer", "bad_move");
            throw new ApiException(ErrorCode.CONFLICT, "This request is " + row.state().name().toLowerCase(
                    java.util.Locale.ROOT) + " and cannot be answered that way.");
        }
    }

    /** The places of the version that was read; a problem is put in the map when it cannot be asked about. */
    private Layout readLayout(UUID templateId, Integer versionNo, Map<String, List<String>> problems) {
        Optional<Layout> layout = versionNo == null ? Optional.empty()
                : sheets.layoutOf(Templates.OBJECT, templateId, versionNo);
        // A version that nobody but its authors may read answers like one that does not exist.
        if (layout.isEmpty() || !layout.get().status().readableByReaders()) {
            problems.put("versionNo", List.of("There is no such version to ask about."));
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
    private void tell(UUID recipient, UUID actor, UUID templateId, UUID requestId, String event, String key) {
        notices.tell(recipient, actor, templateId, requestId, event, key);
    }

    // ---- views ----

    /** What building the views of a few requests needs, read once. */
    private final class Frame {

        private final Reach reach;
        private final SheetLifecycle.Summary summary;
        private final boolean canRequest;
        private final Map<UUID, List<CommentRow>> comments = new HashMap<>();
        private final Map<UUID, String> names;
        private final Map<Integer, Optional<SheetStatus>> statuses = new HashMap<>();
        private final String templateName;
        private final String templateNumber;
        private Optional<Layout> active;
        private boolean activeRead;

        private Frame(Reach reach, List<RequestRow> rows) {
            this.reach = reach;
            this.summary = summary(reach.templateId());
            DataRecord template = header(reach.templateId());
            this.canRequest = summary.active() > 0 && !retired(template);
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
                active = summary.active() > 0 ? sheets.layoutOf(Templates.OBJECT, reach.templateId(), summary.active())
                        : Optional.empty();
                activeRead = true;
            }
            return active;
        }

        private Optional<SheetStatus> statusOf(int versionNo) {
            return statuses.computeIfAbsent(versionNo,
                    number -> sheets.statusOf(Templates.OBJECT, reach.templateId(), number));
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
        Optional<SheetStatus> became = row.resultingVersion() == null ? Optional.empty()
                : frame.statusOf(row.resultingVersion());
        boolean review = frame.reach.canReview();
        boolean reopenable = review && status == ChangeRequestStatus.DONE && became.isPresent()
                && (became.get() == SheetStatus.REJECTED || became.get() == SheetStatus.WITHDRAWN);
        // A request is accepted or declined by the task of the people its approval asked (ADR-0199); it can be turned
        // into a version only once accepted.
        boolean turnable = review && status.canBeTurned() && frame.canRequest && status == ChangeRequestStatus.ACCEPTED;
        return new ChangeRequestView(row.id(), row.title(), status.name(), row.versionNo(),
                frame.names.get(row.createdBy()), row.requesterDepartment(), mine, row.createdAt(), row.decidedAt(),
                row.resultingVersion(), became.map(SheetStatus::name).orElse(null), total, there,
                status.takesComments() && (review || mine), turnable, reopenable, comments,
                row.templateId(), frame.templateName, frame.templateNumber, row.headerId());
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

    private DataRecord header(UUID templateId) {
        return records.find(Templates.OBJECT, templateId)
                .orElseThrow(() -> ApiException.notFound("This template does not exist."));
    }

    private static boolean retired(DataRecord header) {
        return Boolean.TRUE.equals(header.fields().get(Templates.RETIRED));
    }

    private SheetLifecycle.Summary summary(UUID templateId) {
        return sheets.summaries(Templates.OBJECT, List.of(templateId)).getOrDefault(templateId,
                new SheetLifecycle.Summary(0, 0, 0, 0, 0, 0, 0));
    }

    private void refuse(String act, String reason) {
        changes.refused(act, reason);
    }
}
