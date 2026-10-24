package app.platform.inspection.internal;

import app.platform.data.BackgroundRecords;
import app.platform.data.DataRecord;
import app.platform.inspection.internal.ChangeRequestAccess.Reach;
import app.platform.inspection.internal.ChangeRequestStore.CommentRow;
import app.platform.inspection.internal.ChangeRequestStore.RequestRow;
import app.platform.inspection.internal.ChangeRequestViews.AnswerChangeRequest;
import app.platform.inspection.internal.ChangeRequestViews.ChangeCommentView;
import app.platform.inspection.internal.ChangeRequestViews.ChangeRequestView;
import app.platform.inspection.internal.ChangeRequestViews.ChangeRequestsView;
import app.platform.inspection.internal.ChangeRequestViews.CreateChangeRequest;
import app.platform.inspection.internal.ChangeRequestViews.NewChangeComment;
import app.platform.inspection.internal.ChangeRequestViews.TurnIntoVersionRequest;
import app.platform.inspection.internal.ChangeRequestViews.TurnedView;
import app.platform.sharedkernel.directory.ReferenceLabels;
import app.platform.sharedkernel.notice.NoticeKind;
import app.platform.sharedkernel.notice.NoticeRequest;
import app.platform.sharedkernel.notice.Notices;
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

    private final ChangeRequestAccess access;
    private final ChangeRequestStore store;
    private final ChangeRequestProperties properties;
    private final ChangeRequestChanges changes;
    private final SheetLifecycle sheets;
    private final BackgroundRecords records;
    private final Notices notices;
    private final List<ReferenceLabels> labels;
    private final DepartmentAudiences departments;

    ChangeRequests(ChangeRequestAccess access, ChangeRequestStore store, ChangeRequestProperties properties,
            ChangeRequestChanges changes, SheetLifecycle sheets, BackgroundRecords records, Notices notices,
            List<ReferenceLabels> labels, DepartmentAudiences departments) {
        this.access = access;
        this.store = store;
        this.properties = properties;
        this.changes = changes;
        this.sheets = sheets;
        this.records = records;
        this.notices = notices;
        this.labels = labels;
        this.departments = departments;
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
        int open = (int) rows.stream().filter(row -> row.state() == ChangeRequestStatus.OPEN).count();
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
            throw new ApiException(ErrorCode.CONFLICT, "This template has no version in use, so nothing can be asked "
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
        RequestRow made = store.insertRequest(templateId, request.versionNo(), ChangeText.clean(request.title()),
                reach.userId(), departments.departmentsOf(reach.membershipId()));
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
        tell(header.ownerId(), reach.userId(), templateId, "requested", "chg:" + made.id() + ":requested");
        return view(frame(reach, List.of(made)), made);
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
        tell(other, reach.userId(), templateId, "commented", "chg:" + written.id() + ":commented");
        return view(frame(reach, List.of(row)), row);
    }

    // ---- answering ----

    /** Accepts, declines (with a reason) or reopens a request. Needs the right to change the template. */
    ChangeRequestView answer(UUID templateId, UUID requestId, AnswerChangeRequest answer) {
        Reach reach = access.reach(templateId);
        store.lock(templateId);
        RequestRow row = visibleRequest(reach, requestId);
        requireReview(reach, "answer");
        String act;
        boolean moved;
        switch (answer.action()) {
            case "ACCEPT" -> {
                act = "accepted";
                requireMove(row, ChangeRequestStatus.ACCEPTED);
                moved = store.answer(requestId, row.state(), ChangeRequestStatus.ACCEPTED, null, null, reach.userId());
            }
            case "DECLINE" -> {
                act = "declined";
                requireMove(row, ChangeRequestStatus.DECLINED);
                String note = noteOf(answer.note());
                moved = store.answer(requestId, row.state(), ChangeRequestStatus.DECLINED, note, null, reach.userId());
            }
            case "REOPEN" -> {
                act = "reopened";
                requireMove(row, ChangeRequestStatus.OPEN);
                Optional<SheetStatus> ended = row.resultingVersion() == null ? Optional.empty()
                        : sheets.statusOf(Templates.OBJECT, templateId, row.resultingVersion());
                if (ended.isEmpty() || (ended.get() != SheetStatus.REJECTED && ended.get() != SheetStatus.WITHDRAWN)) {
                    refuse("reopen", "version_used");
                    throw new ApiException(ErrorCode.CONFLICT, "The version this request became was not rejected or "
                            + "withdrawn, so the request cannot be reopened.");
                }
                moved = store.reopen(requestId, reach.userId());
            }
            default -> throw ApiException.validation("action", "Choose ACCEPT, DECLINE or REOPEN.");
        }
        if (!moved) {
            refuse(act, "raced");
            throw new ApiException(ErrorCode.CONFLICT, RACE_WORDS);
        }
        changes.answered(reach.userId(), templateId, requestId, act);
        if (!"reopened".equals(act)) {
            tell(row.createdBy(), reach.userId(), templateId, act, "chg:" + requestId + ":" + act);
        }
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
                    + " requests into a version at once.");
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
            throw new ApiException(ErrorCode.CONFLICT, "This template has no version in use to start from.");
        }
        List<RequestRow> rows = new ArrayList<>();
        for (UUID id : ids) {
            RequestRow row = store.find(templateId, id).orElseThrow(() -> ApiException.notFound(NO_REQUEST));
            if (!row.state().canBeTurned()) {
                refuse("turn", "not_open");
                throw new ApiException(ErrorCode.CONFLICT, "A request that is already answered or turned into a "
                        + "version cannot be turned into another. Reload the page.");
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
            tell(row.createdBy(), reach.userId(), templateId, "done", "chg:" + row.id() + ":done");
        }
        changes.turned(reach.userId(), templateId, started.versionNo(), started.created(), rows.size());
        return new TurnedView(started.versionNo(), started.created(), rows.size());
    }

    // ---- rules ----

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

    private String noteOf(String note) {
        Optional<String> problem = ChangeText.problem("reason", note, properties.maxNoteChars(), true);
        if (problem.isPresent()) {
            throw ApiException.validation("note", problem.get());
        }
        return ChangeText.clean(note);
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
            problems.put(at + "place", List.of("That row or column is not in the version you read."));
        }
        return anchor;
    }

    // ---- telling people ----

    /** Tells one person, unless it is the person who did it or there is nobody to tell. */
    private void tell(UUID recipient, UUID actor, UUID templateId, String event, String key) {
        if (recipient == null || recipient.equals(actor)) {
            return;
        }
        notices.notify(new NoticeRequest(NoticeKind.CHANGE_REQUEST, recipient, Templates.OBJECT, templateId,
                Map.of("event", event, "application", Templates.APPLICATION, "entry", Templates.ENTRY), key));
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
        private Optional<Layout> active;
        private boolean activeRead;

        private Frame(Reach reach, List<RequestRow> rows) {
            this.reach = reach;
            this.summary = summary(reach.templateId());
            this.canRequest = summary.active() > 0 && !retired(header(reach.templateId()));
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
        return new ChangeRequestView(row.id(), row.title(), status.name(), row.versionNo(),
                frame.names.get(row.createdBy()), row.requesterDepartment(), mine, row.createdAt(), row.decidedAt(),
                row.decisionNote(),
                row.resultingVersion(), became.map(SheetStatus::name).orElse(null), total, there,
                status.takesComments() && (review || mine), review && status.canMoveTo(ChangeRequestStatus.ACCEPTED),
                review && status.canMoveTo(ChangeRequestStatus.DECLINED),
                review && status.canBeTurned() && frame.canRequest, reopenable, comments);
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
