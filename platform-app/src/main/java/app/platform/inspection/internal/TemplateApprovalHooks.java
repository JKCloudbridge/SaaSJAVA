package app.platform.inspection.internal;

import app.platform.data.BackgroundRecords;
import app.platform.data.DataRecord;
import app.platform.data.NumberSeries;
import app.platform.data.NumberSeriesException;
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
 *   <li><strong>On submit</strong>: the draft is the only thing under review. The template must not be retired, must be
 *   able to get its number (a category that has a format, and the parts its number is made of), and the number it
 *   would get must be free; then the draft goes to review under the request.</li>
 *   <li><strong>Before an approval</strong>: when the template has no number yet, the approval gives it one from the
 *   series and writes it through the engine's narrow writer. It reads the record, not the position of the step, so a
 *   skipped first step makes no difference, and a later approval of the same step (everyone must approve) finds the
 *   number and does nothing. A template rejected at the first step never reaches this, so it takes no number.</li>
 *   <li><strong>When the request ends</strong>: the version it reviewed is approved, rejected or withdrawn.
 *   Approving does not publish.</li>
 * </ul>
 *
 * <p>The refusal words are fixed; they never repeat what an author typed. Nothing a template holds (name, number,
 * category, codes) is written to a log, an audit record or a metric: the engine and the sheets module audit by
 *identifiers and counts. /
@Component
class TemplateApprovalHooks implements ApprovalHooks {

    private static final List<String> NUMBER_FIELD = List.of("number");

    private final BackgroundRecords records;
    private final NumberSeries numbers;
    private final SheetLifecycle sheets;

    TemplateApprovalHooks(BackgroundRecords records, NumberSeries numbers, SheetLifecycle sheets) {
        this.records = records;
        this.numbers = numbers;
        this.sheets = sheets;
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
        if (Templates.isBlank(fields.get(Templates.NUMBER))) {
            checkNumbering(fields, template.id());
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
        DataRecord template = template(context.recordId());
        if (!Templates.isBlank(template.fields().get(Templates.NUMBER))) {
            return;
        }
        String number;
        try {
            number = numbers.next(Templates.SERIES, template.fields());
        } catch (NumberSeriesException unavailable) {
            // Submit checked this; it can only have changed in review (an approver corrected the category).
            throw new ApprovalHookRefusal("not_numbered", "This template cannot get a number yet: check its category, "
                    + "sub-category, part code and plan year, then approve again.");
        }
        try {
            writer.set(Map.of(Templates.NUMBER, number));
        } catch (ApprovalHookWriteException refused) {
            throw new ApprovalHookRefusal("number_in_use", "Another template already has the number this one would "
                    + "get. Change its part code or sub-category, then approve again.");
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

    // ---- the numbering checks of the submit ----

    private void checkNumbering(Map<String, Object> fields, UUID templateId) {
        if (Templates.isBlank(fields.get(Templates.CATEGORY))) {
            throw new ApprovalHookRefusal("category_needed", "Choose the category before sending the template for "
                    + "approval.");
        }
        Optional<List<String>> missing = numbers.missingParts(Templates.SERIES, fields);
        if (missing.isEmpty()) {
            throw new ApprovalHookRefusal("category_not_numbered", "This category has no numbering yet. Ask your "
                    + "administrator.");
        }
        if (!missing.get().isEmpty()) {
            throw new ApprovalHookRefusal("number_part_needed", Templates.neededWords(missing.get()));
        }
        Object year = fields.get(Templates.PLAN_YEAR);
        if (!Templates.isBlank(year) && !Templates.isYear(year)) {
            throw new ApprovalHookRefusal("plan_year_invalid", "The plan year is four digits, for example 2026.");
        }
        Optional<String> known = numbers.known(Templates.SERIES, fields);
        if (known.isPresent() && taken(known.get(), templateId)) {
            throw new ApprovalHookRefusal("number_in_use", "Another template already has the number this one would "
                    + "get. Change its part code or sub-category.");
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
