package app.platform.inspection.internal;

import app.platform.data.BackgroundRecords;
import app.platform.data.DataRecord;
import app.platform.inspection.internal.TemplateAccess.Caller;
import app.platform.inspection.internal.TemplateOptionStore.Options;
import app.platform.security.Ability;
import app.platform.sharedkernel.audit.AuditOutcome;
import app.platform.sharedkernel.audit.AuditRecord;
import app.platform.sharedkernel.audit.AuditRecorder;
import app.platform.sheets.SheetExports;
import app.platform.sheets.SheetLifecycle;
import app.platform.tenant.TenantContexts;
import app.platformapi.ApiException;
import app.platformapi.ErrorCode;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The sheet of a template as an Excel file (FA7, ADR-0201). Three questions, asked in this order and each answered by
 * the one place that owns it:
 * <ol>
 *   <li>May the caller see the template? The rule of every record ({@link TemplateAccess#visible}); everybody who may
 *   not, for a template that does not exist and on the platform host get "not found", alike.</li>
 *   <li>Is download allowed? The "allow download" option of the template, or the right to publish (Plant QMS and the
 *   organization's administrator may check their work whatever the option says). A hidden button is not the rule: this
 *   is. Refused in fixed words, with "forbidden".</li>
 *   <li>May the caller read that revision? {@link SheetExports} asks the rule of the Sheet tab and nothing else.</li>
 * </ol>
 *
 * <p>The footer row carries the template's number and the revision in use (the revision shown when none is in use yet).
 * <strong>What is never written down</strong>: the number, the file name and any cell. The audit record and the metric
 * carry the template's identifier, the revision number and the size.
 */
@Service
@Transactional
class TemplateDownloads {

    static final String NOT_ALLOWED = "Download is not allowed for this template.";

    /**
     * A file to send.
     *
     * @param bytes the workbook
     * @param fileName the name it is offered under
     */
    record Download(byte[] bytes, String fileName) {

        /** Prints nothing of the file. */
        @Override
        public String toString() {
            return "Download[" + bytes.length + " bytes]";
        }
    }

    private final TemplateAccess access;
    private final TemplateOptionStore options;
    private final SheetLifecycle lifecycle;
    private final SheetExports exports;
    private final BackgroundRecords records;
    private final AuditRecorder audit;
    private final TenantContexts contexts;
    private final MeterRegistry meters;
    private final TemplateRevisions revisions;
    private final FooterProperties footer;

    TemplateDownloads(TemplateAccess access, TemplateOptionStore options, SheetLifecycle lifecycle,
            SheetExports exports, BackgroundRecords records, AuditRecorder audit, TenantContexts contexts,
            MeterRegistry meters, TemplateRevisions revisions, FooterProperties footer) {
        this.footer = footer;
        this.revisions = revisions;
        this.access = access;
        this.options = options;
        this.lifecycle = lifecycle;
        this.exports = exports;
        this.records = records;
        this.audit = audit;
        this.contexts = contexts;
        this.meters = meters;
    }

    /**
     * The file of one revision.
     *
     * @param set the set of records the template belongs to
     * @param templateId the template
     * @param versionNo the stored number of the revision, or null for the one in use
     * @throws ApiException {@code NOT_FOUND}, {@code FORBIDDEN} (download is not allowed), {@code PAYLOAD_TOO_LARGE}
     */
    Download download(RecordSet set, UUID templateId, Integer versionNo) {
        Caller caller = access.visible(set, templateId);
        Options chosen = options.find(templateId);
        boolean heldCopy = versionNo != null && lifecycle.heldDraft(set.template(), templateId)
                .filter(held -> held.versionNo() == versionNo).isPresent();
        // The revised copy of a change request may be downloaded by the people who change it, whatever the option says
        // (FA10, ADR-0204). The sheet rule asks again below, and it shows the held copy only to the people asked to
        // decide the request (and the organization's administrator): reading it and changing it are the same people.
        if (!chosen.allowDownload() && !heldCopy && !access.has(caller, Ability.SHEETS_PUBLISH)) {
            meters.counter("platform.inspection.template", "act", "download", "result", "not_allowed").increment();
            throw new ApiException(ErrorCode.FORBIDDEN, NOT_ALLOWED);
        }
        SheetLifecycle.Summary summary = lifecycle.summaries(set.template(), List.of(templateId))
                .getOrDefault(templateId, new SheetLifecycle.Summary(0, 0, 0, 0, 0, 0, 0));
        int wanted = versionNo != null ? versionNo : summary.active();
        if (wanted <= 0) {
            throw ApiException.notFound("This revision does not exist.");
        }
        DataRecord header = records.find(set.template(), templateId)
                .orElseThrow(() -> ApiException.notFound("This template does not exist."));
        Object number = header.fields().get(Templates.NUMBER);
        String numberText = number == null ? null : number.toString();
        int shown = revisions.of(set, templateId, summary.active() > 0 ? summary.active() : wanted);
        // The day of the publish is only known for the Revision in use (the template carries one day, MC1, ADR-0257).
        java.time.Instant revised = Templates.lastRevisedAt(header.fields());
        java.time.LocalDate day = set.footerDate() && revised != null && wanted == summary.active()
                ? revised.atZone(footer.zoneId()).toLocalDate() : null;
        byte[] bytes = exports.xlsx(set.template(), templateId, wanted,
                TemplateSheetTexts.footer(numberText, shown, day));
        audit.record(AuditRecord.of("inspection.template.downloaded", AuditOutcome.SUCCESS).forUser(caller.userId())
                .inTenant(contexts.require().tenantId()).onObject(set.template(), templateId.toString())
                .with("version_no", Integer.toString(wanted)).with("bytes", Integer.toString(bytes.length)));
        meters.counter("platform.inspection.template", "act", "download", "result", "ok").increment();
        return new Download(bytes, TemplateSheetTexts.fileName(numberText, shown));
    }
}
