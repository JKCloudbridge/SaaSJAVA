package app.platform.inspection.internal;

import app.platform.data.BackgroundRecords;
import app.platform.data.DataRecord;
import app.platform.data.NumberSeries;
import app.platform.data.NumberSeriesException;
import app.platform.data.NumberAssignments;
import app.platform.sharedkernel.approval.ApprovalHookRefusal;
import app.platform.sharedkernel.approval.ApprovalHookWriteException;
import app.platform.sharedkernel.approval.ApprovalHookWriter;
import app.platform.sharedkernel.approval.ApprovalHooks;
import app.platform.sheets.SheetLifecycle;
import app.platform.sheets.SheetMoveRefusal;
import app.platform.sheets.SheetStatus;
import app.platformapi.QueryFilterNode;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * What the approval engine asks of the cluster for a template (FA4, ADR-0176 section 2, ADR-0187, ADR-0188). It runs
 * inside the engine's own transaction while the header is held, so everything it does is undone with the decision that
 * asked, and it must be short.
 *
 * <ul>
 *   <li><strong>On submit</strong>: the draft is the only thing under review. The template must not be retired; then
 *   the draft goes to review under the request. The numbering is <em>not</em> asked of the author (FA6A, ADR-0196): the
 *   people who approve first decide the identity category and sub-category.</li>
 *   <li><strong>Before an approval</strong>: Save normally allocated the provisional number (ADR-0198). First Approve
 *   checks completeness and fixes the reservation and identity. An unnumbered header is still allocated through the
 *   narrow writer as a backstop. Any number of steps and skipped steps work; later approvals never allocate again.
 *   Rejection retains every allocation; it cannot reverse a first-Approve fixed mark.</li>
 *   <li><strong>At a step that asks for a choice</strong> (FA6G, ADR-0200): the approval carries the places the
 *   template is suggested for; a location is required, a department is not; it is kept with the request and shares
 *   nothing.</li>
 *   <li><strong>When the request ends</strong>: the version it reviewed is approved, rejected or withdrawn.
 *   Approving does not publish.</li>
 * </ul>
 *
 * <p>The refusal words are fixed; they never repeat what an author typed. Nothing a template holds (name, number,
 * category, codes) is written to a log, an audit record or a metric: the engine and the sheets module audit by
 * identifiers and counts. */
@Component
class TemplateApprovalHooks implements ApprovalHooks {

    private static final List<String> NUMBER_FIELD = List.of("number");

    private final BackgroundRecords records;
    private final NumberSeries numbers;
    private final SheetLifecycle sheets;
    private final NumberAssignments assignments;
    private final AudienceSuggestions suggestions;

    TemplateApprovalHooks(BackgroundRecords records, NumberSeries numbers, SheetLifecycle sheets,
            NumberAssignments assignments, AudienceSuggestions suggestions) {
        this.suggestions = suggestions;
        this.records = records;
        this.numbers = numbers;
        this.sheets = sheets;
        this.assignments = assignments;
    }

    @Override
    public String object() {
        return Templates.OBJECT;
    }

    @Override
    public List<String> writes() {
        return NUMBER_FIELD;
    }

    @Override
    public void onSubmit(SubmitContext context) {
        DataRecord template = template(context.recordId());
        Map<String, Object> fields = template.fields();
        if (Boolean.TRUE.equals(fields.get(Templates.RETIRED))) {
            throw new ApprovalHookRefusal("template_retired",
                    "This template is retired and cannot be sent for approval.");
        }
        try {
            sheets.submit(Templates.OBJECT, context.recordId(), context.requestId(), context.submitter());
        } catch (SheetMoveRefusal refusal) {
            throw new ApprovalHookRefusal("sheet_" + refusal.reason().name().toLowerCase(Locale.ROOT),
                    Templates.fit(refusal.getMessage()));
        }
    }

    @Override
    public void beforeApprove(DecisionContext context, ApprovalHookWriter writer) {
        if (context.asksForChoice()) {
            // A step the process marks as asking for a choice: no approval without a location (FA6G, ADR-0200).
            suggestions.accept(context, context.recordId());
        }
        DataRecord template = template(context.recordId());
        if (!Templates.isBlank(template.fields().get(Templates.NUMBER))) {
            if (!assignments.fixed(Templates.SERIES, template.id(),
                    (String) template.fields().get(Templates.NUMBER))) {
                checkNumbering(template.fields(), template.id());
            }
            assignments.reserve(Templates.SERIES, Templates.OBJECT, template.id(),
                    (String) template.fields().get(Templates.NUMBER), true);
            return;
        }
        checkNumbering(template.fields(), template.id());
        String number;
        try {
            number = numbers.next(Templates.SERIES, template.fields());
        } catch (NumberSeriesException unavailable) {
            // The checks above passed a moment ago; a number that still cannot be made is told in the same words.
            throw new ApprovalHookRefusal("not_numbered", "This template cannot get a number yet: check its identity "
                    + "category, sub-category, part code and plan year (Edit), then approve again.");
        }
        try {
            writer.set(Map.of(Templates.NUMBER, number));
            assignments.reserve(Templates.SERIES, Templates.OBJECT, template.id(), number, true);
        } catch (ApprovalHookWriteException refused) {
            throw new ApprovalHookRefusal("number_in_use", "Another template already has the number this one would "
                    + "get. Change its part code or identity sub-category (Edit), then approve again.");
        }
    }

    @Override
    public void onEnded(EndContext context) {
        SheetStatus result = switch (context.outcome()) {
            case APPROVED -> SheetStatus.APPROVED;
            case REJECTED -> SheetStatus.REJECTED;
            case RECALLED, CANCELLED -> SheetStatus.WITHDRAWN;
            default -> throw new IllegalStateException("Unreachable: every outcome is handled above");
        };
        sheets.reviewed(Templates.OBJECT, context.recordId(), context.requestId(), result, context.endedBy());
    }

    // ---- the numbering checks of the first approval ----

    private void checkNumbering(Map<String, Object> fields, UUID templateId) {
        if (Templates.isBlank(fields.get(Templates.CATEGORY))) {
            throw new ApprovalHookRefusal("category_needed", "Choose the identity category (Edit), "
                    + "then approve again.");
        }
        Optional<List<String>> missing = numbers.missingParts(Templates.SERIES, fields);
        if (missing.isEmpty()) {
            throw new ApprovalHookRefusal("category_not_numbered", "This identity category has no numbering yet. Ask "
                    + "your administrator.");
        }
        if (!missing.get().isEmpty()) {
            throw new ApprovalHookRefusal("number_part_needed", Templates.neededWords(missing.get()));
        }
        Object year = fields.get(Templates.PLAN_YEAR);
        if (!Templates.isBlank(year) && !Templates.isYear(year)) {
            throw new ApprovalHookRefusal("plan_year_invalid",
                    "The plan year is four digits, for example 2026 (Edit), then approve again.");
        }
        Optional<String> known = numbers.known(Templates.SERIES, fields);
        if (known.isPresent() && taken(known.get(), templateId)) {
            throw new ApprovalHookRefusal("number_in_use", "Another template already has the number this one would "
                    + "get. Change its part code or identity sub-category (Edit), then approve again.");
        }
        if (known.isPresent()) {
            try {
                assignments.checkAvailable(Templates.SERIES, templateId, known.get());
            } catch (NumberSeriesException taken) {
                throw new ApprovalHookRefusal("number_in_use", "Another template already has the number this one "
                        + "would get. Change its part code or identity sub-category (Edit), then approve again.");
            }
        }
    }

    /** Whether another template already has the number: the unique index is the backstop, this says it in words. */
    private boolean taken(String number, UUID templateId) {
        QueryFilterNode filter = new QueryFilterNode("EQ", null, Templates.NUMBER,
                List.of(number));
        return records.scan(Templates.OBJECT, filter, null, 2).stream()
                .anyMatch(other -> !other.id().equals(templateId));
    }

    private DataRecord template(UUID id) {
        return records.find(Templates.OBJECT, id).orElseThrow(() -> new ApprovalHookRefusal("template_missing",
                "This template cannot be found."));
    }
}
