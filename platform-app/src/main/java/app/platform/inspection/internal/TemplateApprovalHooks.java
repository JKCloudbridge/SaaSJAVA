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
 *   people who approve first decide the identification and its parts.</li>
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

    /** What the hook may write: the number and the application the routing rules give it (MC1.1, ADR-0259). */
    private static final List<String> WRITTEN = List.of(Templates.NUMBER, Templates.APPLICATION);

    private final BackgroundRecords records;
    private final NumberSeries numbers;
    private final SheetLifecycle sheets;
    private final NumberAssignments assignments;
    private final AudienceSuggestions suggestions;
    private final RecordSets sets;
    private final DocumentRouting routing;

    TemplateApprovalHooks(BackgroundRecords records, NumberSeries numbers, SheetLifecycle sheets,
            NumberAssignments assignments, AudienceSuggestions suggestions, RecordSets sets,
            DocumentRouting routing) {
        this.routing = routing;
        this.sets = sets;
        this.suggestions = suggestions;
        this.records = records;
        this.numbers = numbers;
        this.sheets = sheets;
        this.assignments = assignments;
    }

    /** The template object of the first set; the hook serves the template object of every set ({@link #objects()}). */
    @Override
    public String object() {
        return sets.templates().get(0);
    }

    @Override
    public java.util.Collection<String> objects() {
        return sets.templates();
    }

    @Override
    public List<String> writes() {
        return WRITTEN;
    }

    /** The set the call is about, by the object the engine names. */
    private RecordSet setOf(String object) {
        return sets.ofTemplate(object).orElseThrow(() -> new ApprovalHookRefusal("template_missing",
                "This template cannot be found."));
    }

    @Override
    public void onSubmit(SubmitContext context) {
        RecordSet set = setOf(context.object());
        DataRecord template = template(set, context.recordId());
        Map<String, Object> fields = template.fields();
        // The one rule of whether a template can be sent, which the lifecycle view asks too (FA10, ADR-0204).
        SheetLifecycle.Summary summary = sheets.summaries(set.template(), List.of(context.recordId()))
                .getOrDefault(context.recordId(), new SheetLifecycle.Summary(0, 0, 0, 0, 0, 0, 0));
        TemplateSendRule.blocked(summary, Boolean.TRUE.equals(fields.get(Templates.RETIRED))).ifPresent(block -> {
            throw new ApprovalHookRefusal(block.reason(), block.words());
        });
        try {
            sheets.submit(set.template(), context.recordId(), context.requestId(), context.submitter());
        } catch (SheetMoveRefusal refusal) {
            throw new ApprovalHookRefusal("sheet_" + refusal.reason().name().toLowerCase(Locale.ROOT),
                    Templates.fit(refusal.getMessage()));
        }
    }

    @Override
    public void beforeApprove(DecisionContext context, ApprovalHookWriter writer) {
        RecordSet set = setOf(context.object());
        if (context.asksForChoice()) {
            // A step the process marks as asking for a choice: no approval without a location (FA6G, ADR-0200).
            suggestions.accept(set.template(), context, context.recordId());
        }
        DataRecord template = template(set, context.recordId());
        if (!Templates.isBlank(template.fields().get(Templates.NUMBER))) {
            if (!assignments.fixed(set.series(), template.id(),
                    (String) template.fields().get(Templates.NUMBER))) {
                checkNumbering(set, template.fields(), template.id());
                // The number is fixed at this approval: the application is looked up once more, so it is the one the
                // organization's rules give now, and from here on it never moves (ADR-0259).
                route(set, template, writer, Map.of());
            }
            assignments.reserve(set.series(), set.template(), template.id(),
                    (String) template.fields().get(Templates.NUMBER), true);
            return;
        }
        checkNumbering(set, template.fields(), template.id());
        String number;
        try {
            number = numbers.next(set.series(), template.fields());
        } catch (NumberSeriesException unavailable) {
            // The checks above passed a moment ago; a number that still cannot be made is told in the same words.
            throw new ApprovalHookRefusal("not_numbered", "This template cannot get a number yet: "
                    + "check its identification, Part A and Part B (Edit), then approve again.");
        }
        try {
            route(set, template, writer, Map.of(Templates.NUMBER, number));
            assignments.reserve(set.series(), set.template(), template.id(), number, true);
        } catch (ApprovalHookWriteException refused) {
            throw new ApprovalHookRefusal("number_in_use", "Another template already has the number this one would "
                    + "get. Change its identification, Part A or Part B (Edit), then approve again.");
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
        sheets.reviewed(setOf(context.object()).template(), context.recordId(), context.requestId(), result,
                context.endedBy());
    }

    /**
     * Writes what this approval fixes on the template: the number when it has none, and the application the routing
     * rules give it now, when that differs from what the template holds. One write, so the number and the application
     * are never apart.
     */
    private void route(RecordSet set, DataRecord template, ApprovalHookWriter writer, Map<String, String> number) {
        String wanted = routing.applicationFor(set.template(), template.fields());
        String has = template.fields().get(Templates.APPLICATION) instanceof String text ? text : "";
        Map<String, String> values = new java.util.LinkedHashMap<>(number);
        // A rule that fits now is written; what the last Save gave stays when none fits (the write pipeline takes no
        // empty value, and a Save is where a value is cleared).
        if (wanted != null && !wanted.equals(has)) {
            values.put(Templates.APPLICATION, wanted);
        }
        if (!values.isEmpty()) {
            writer.set(values);
        }
    }

    // ---- the numbering checks of the first approval ----

    private void checkNumbering(RecordSet set, Map<String, Object> fields, UUID templateId) {
        if (Templates.isBlank(fields.get(Templates.CATEGORY))) {
            throw new ApprovalHookRefusal("category_needed", "Choose the identification (Edit), "
                    + "then approve again.");
        }
        Optional<List<String>> missing = numbers.missingParts(set.series(), fields);
        if (missing.isEmpty()) {
            throw new ApprovalHookRefusal("category_not_numbered", "This identification has no numbering yet. Ask "
                    + "your administrator.");
        }
        if (!missing.get().isEmpty()) {
            throw new ApprovalHookRefusal("number_part_needed", Templates.neededWords(missing.get()));
        }
        for (String part : List.of(Templates.PART_A, Templates.PART_B)) {
            if (!Templates.isBlank(fields.get(part)) && !Templates.isPart(fields.get(part))) {
                throw new ApprovalHookRefusal("part_invalid", Templates.neededWords(List.of(part)));
            }
        }
        Optional<String> known = numbers.known(set.series(), fields);
        if (known.isPresent() && taken(set, known.get(), templateId)) {
            throw new ApprovalHookRefusal("number_in_use", "Another template already has the number this one would "
                    + "get. Change its identification, Part A or Part B (Edit), then approve again.");
        }
        if (known.isPresent()) {
            try {
                assignments.checkAvailable(set.series(), templateId, known.get());
            } catch (NumberSeriesException taken) {
                throw new ApprovalHookRefusal("number_in_use", "Another template already has the number this one "
                        + "would get. Change its identification, Part A or Part B (Edit), then approve again.");
            }
        }
    }

    /** Whether another template already has the number: the unique index is the backstop, this says it in words. */
    private boolean taken(RecordSet set, String number, UUID templateId) {
        QueryFilterNode filter = new QueryFilterNode("EQ", null, Templates.NUMBER,
                List.of(number));
        return records.scan(set.template(), filter, null, 2).stream()
                .anyMatch(other -> !other.id().equals(templateId));
    }

    private DataRecord template(RecordSet set, UUID id) {
        return records.find(set.template(), id).orElseThrow(() -> new ApprovalHookRefusal("template_missing",
                "This template cannot be found."));
    }
}
