package app.platform.inspection.internal;

import app.platform.data.QueriedRecord;
import app.platform.data.QueryFilter;
import app.platform.data.QuerySort;
import app.platform.data.RecordQuery;
import app.platform.data.Records;
import app.platform.inspection.internal.FillInViews.FillInPage;
import app.platform.inspection.internal.FillInViews.FillInRow;
import app.platform.inspection.internal.FillInViews.FillReportRequest;
import app.platform.inspection.internal.FillInViews.ReportColumnView;
import app.platform.inspection.internal.FillInViews.ReportColumnsView;
import app.platform.sharedkernel.audit.AuditOutcome;
import app.platform.sharedkernel.audit.AuditRecord;
import app.platform.sharedkernel.audit.AuditRecorder;
import app.platform.sheets.FillReports;
import app.platform.sheets.SheetLifecycle;
import app.platform.tenant.TenantContexts;
import app.platformapi.ApiException;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The report on fill-ins (FF2, ADR-0193): "show me every fill-in of this template where this column holds this value".
 *
 * <p><strong>Who sees which.</strong> The sheets module says which fill-ins hold the value, from a table of typed
 * columns, and knows nothing of visibility. The cluster then asks the record store's own query which of those headers
 * the caller may see, in batches, so a person sees exactly the fill-ins they see in the list, by the same code (there
 * is no second rule, ADR-0115): a filler finds their own, a person who may view every fill-in finds all. A fill-in the
 * caller cannot see is never counted, named or hinted at. To choose a column the caller must be able to see the
 * template, like any other act on it.
 *
 * <p><strong>Paging.</strong> The page is read by the key of the last fill-in looked at and never by an offset. A
 * caller
 * who can see few of the matches makes the search look through more batches to fill a page; the number of batches is
 * bounded, and when the bound is reached the page may be short and carries a cursor to continue from. A short page with
 * a cursor therefore does not mean the end; only a missing cursor does.
 *
 * <p><strong>Never written down.</strong> The value asked for is text of the organization: it travels in a body, is
 * never logged, audited or put in a metric tag, and a refusal never repeats it. The audit record carries the template,
 * the version, the column and the number of rows returned.
 */
// Not read-only: the report leaves an audit record, written in the same transaction.
@Service
@Transactional
class FillReport {

    private static final int DEFAULT_SIZE = 50;
    private static final int MOST = 200;
    /** Candidates asked from the report table at a time; below the largest list the query engine takes in a filter. */
    private static final int BATCH = 50;
    /** Batches looked through for one page, so a caller who sees few of many matches cannot make one request long. */
    private static final int MOST_BATCHES = 20;
    private static final Set<String> STATUSES = Set.of("DRAFT", "SUBMITTED", "RETURNED", "APPROVED");
    private static final List<String> FIELDS = List.of(Fills.NAME, Fills.TEMPLATE_ID, Fills.STATUS);

    private final TemplateAccess access;
    private final FillReports reports;
    private final Records records;
    private final SheetLifecycle sheets;
    private final FillIns fillIns;
    private final AuditRecorder audit;
    private final TenantContexts contexts;
    private final MeterRegistry meters;

    FillReport(TemplateAccess access, FillReports reports, Records records, SheetLifecycle sheets, FillIns fillIns,
            AuditRecorder audit, TenantContexts contexts, MeterRegistry meters) {
        this.access = access;
        this.reports = reports;
        this.records = records;
        this.sheets = sheets;
        this.fillIns = fillIns;
        this.audit = audit;
        this.contexts = contexts;
        this.meters = meters;
    }

    /**
     * The columns of a version the author flagged reportable.
     *
     * @param versionNo the version, or null for the version in use
     * @throws ApiException {@code NOT_FOUND} for a template the caller cannot see
     */
    ReportColumnsView columns(UUID templateId, Integer versionNo) {
        access.visible(templateId);
        int version = version(templateId, versionNo);
        List<ReportColumnView> columns = reports.columns(Templates.OBJECT, templateId, version).stream()
                .map(column -> new ReportColumnView(column.id(), column.label(), column.type(), column.values()))
                .toList();
        return new ReportColumnsView(version, columns);
    }

    /**
     * One page of the fill-ins the caller may see that hold the value.
     *
     * @throws ApiException {@code NOT_FOUND} for a template the caller cannot see, {@code VALIDATION_ERROR} for a
     *         column that is not reportable, a condition that does not fit it, a status or a cursor that is not one
     */
    FillInPage run(FillReportRequest request) {
        access.visible(request.templateId());
        String wantedStatus = status(request.status());
        int version = version(request.templateId(), request.versionNo());
        int size = request.size() == null || request.size() <= 0 ? DEFAULT_SIZE : Math.min(request.size(), MOST);
        FillReports.Condition condition = new FillReports.Condition(operator(request.operator()),
                request.values(), request.from(), request.to());
        UUID after = cursor(request.cursor());
        List<FillInRow> rows = new ArrayList<>();
        UUID scanned = after;
        boolean more = true;
        for (int batches = 0; batches < MOST_BATCHES && more && rows.size() < size; batches++) {
            FillReports.Batch batch = reports.find(Fills.OBJECT, Templates.OBJECT, request.templateId(), version,
                    request.columnId(), condition, scanned, BATCH);
            more = batch.more();
            if (batch.fillIds().isEmpty()) {
                break;
            }
            scanned = batch.last();
            rows.addAll(visible(batch.fillIds(), wantedStatus));
        }
        String next;
        if (rows.size() > size) {
            // More were found than fit: continue after the last one shown (the rest of the batch is older than it).
            rows = new ArrayList<>(rows.subList(0, size));
            next = rows.get(size - 1).fillId().toString();
        } else {
            next = more && scanned != null ? scanned.toString() : null;
        }
        audit.record(AuditRecord.of("inspection.fill.report", AuditOutcome.SUCCESS)
                .inTenant(contexts.require().tenantId()).onObject(Templates.OBJECT, request.templateId().toString())
                .with("version_no", Integer.toString(version)).with("column", request.columnId())
                .with("rows", Integer.toString(rows.size())));
        meters.counter("platform.inspection.fill.report", "result", rows.isEmpty() ? "empty" : "ok").increment();
        return new FillInPage(rows, next);
    }

    // ---- the pieces ----

    /** Of these headers, the rows of those the caller may see, in the order given (newest first). */
    private List<FillInRow> visible(List<UUID> candidates, String status) {
        List<Object> ids = new ArrayList<>();
        candidates.forEach(id -> ids.add(id.toString()));
        QueryFilter byId = new QueryFilter(QueryFilter.Op.IN, null, "id", ids);
        QueryFilter filter = byId;
        if (status != null) {
            filter = new QueryFilter(QueryFilter.Op.AND, List.of(byId,
                    new QueryFilter(QueryFilter.Op.EQ, null, Fills.STATUS, List.of(status))), null, null);
        }
        // One page is enough: it asks for at most as many as it was given, and the engine's page size bounds it.
        RecordQuery query = new RecordQuery(filter, List.of(new QuerySort("createdAt", true)), FIELDS, List.of(),
                candidates.size(), null);
        List<QueriedRecord> seen = records.query(Fills.OBJECT, query).records();
        Map<UUID, FillInRow> byHeader = new HashMap<>();
        for (FillInRow row : fillIns.rowsOf(seen)) {
            byHeader.put(row.fillId(), row);
        }
        List<FillInRow> ordered = new ArrayList<>();
        for (UUID id : candidates) {
            FillInRow row = byHeader.get(id);
            if (row != null) {
                ordered.add(row);
            }
        }
        return ordered;
    }

    private int version(UUID templateId, Integer requested) {
        if (requested != null) {
            if (requested < 1) {
                throw ApiException.validation("versionNo", "Choose a version of the template.");
            }
            return requested;
        }
        SheetLifecycle.Summary summary = sheets.summaries(Templates.OBJECT, List.of(templateId)).get(templateId);
        if (summary == null || summary.active() == 0) {
            throw ApiException.validation("versionNo", "This template has no version in use. Choose a version.");
        }
        return summary.active();
    }

    private static FillReports.Operator operator(String name) {
        try {
            return FillReports.Operator.valueOf(name == null ? "" : name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ApiException.validation("operator", "Choose EQUALS, ONE_OF or BETWEEN.");
        }
    }

    private static String status(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        String wanted = status.toUpperCase(Locale.ROOT);
        if (!STATUSES.contains(wanted)) {
            throw ApiException.validation("status", "Choose DRAFT, SUBMITTED, RETURNED or APPROVED.");
        }
        return wanted;
    }

    private static UUID cursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(cursor);
        } catch (IllegalArgumentException e) {
            throw ApiException.validation("cursor", "This cursor does not belong to this question.");
        }
    }
}
