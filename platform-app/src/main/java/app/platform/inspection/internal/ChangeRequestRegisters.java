package app.platform.inspection.internal;

import app.platform.inspection.internal.ChangeRequestAccess.Reach;
import app.platform.inspection.internal.ChangeRequestStore.RequestRow;
import app.platform.inspection.internal.RegisterLayout.Parsed;
import app.platform.inspection.internal.RegisterLayout.Part;
import app.platform.inspection.internal.RegisterRules.Checked;
import app.platform.inspection.internal.RegisterRules.Completed;
import app.platform.inspection.internal.RegisterStore.Facts;
import app.platform.inspection.internal.RegisterStore.RegisterRow;
import app.platform.inspection.internal.RegisterViews.RegisterCompletion;
import app.platform.inspection.internal.RegisterViews.RegisterFileView;
import app.platform.inspection.internal.RegisterViews.RegisterInput;
import app.platform.inspection.internal.RegisterViews.RegisterView;
import app.platform.sheets.SheetPages;
import app.platformapi.ApiException;
import app.platformapi.ErrorCode;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The register form of a change request: read it, draw it as a spreadsheet page, complete the bottom of it, give it as
 * an Excel file and take the typed parts out of an uploaded one (FA8, ADR-0202).
 *
 * <p><strong>One set of values.</strong> The form view and the Excel view are both drawn from the stored values
 * ({@link RegisterValues}), so whatever way a person filled or looked at the form, the other view shows the same.
 * <strong>Who sees it</strong> is {@link ChangeRequestAccess}'s answer for the request, asked each time.
 * <strong>Who completes it</strong> is {@link ChangeRequestTasks}'s answer: the people asked to decide the request now,
 * and the administrator, while the request is open. The top of the form is written once when the request is raised and
 * never changes; the new revision and the day it was published are stamped by the publish and typed by nobody.
 *
 * <p>The words are the organization's: shown to the people who may see the request and never written to a log, an audit
 * record, an event, a notice or an error. The audit says which boxes changed, by name.
 */
@Service
@Transactional
class ChangeRequestRegisters {

    private static final String XLSX_NAME_FALLBACK = "Change-request-register";
    private static final Map<String, Part> COMPLETED = Map.of("department", Part.DEPARTMENT,
            "obsoletedFormat", Part.OBSOLETED_FORMAT, "obsoletedEffectiveOn", Part.OBSOLETED_DATE,
            "approvedBy", Part.APPROVED_BY, "signatureNote", Part.SIGNATURE, "effectiveOn", Part.EFFECTIVE_DATE);

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

    private final ChangeRequestAccess access;
    private final ChangeRequestStore requests;
    private final RegisterStore store;
    private final RegisterFacts facts;
    private final ChangeRequestTasks tasks;
    private final ChangeRequestChanges changes;
    private final SheetPages pages;
    private final TemplateAccess templates;

    ChangeRequestRegisters(ChangeRequestAccess access, ChangeRequestStore requests, RegisterStore store,
            RegisterFacts facts, ChangeRequestTasks tasks, ChangeRequestChanges changes, SheetPages pages,
            TemplateAccess templates) {
        this.access = access;
        this.requests = requests;
        this.store = store;
        this.facts = facts;
        this.tasks = tasks;
        this.changes = changes;
        this.pages = pages;
        this.templates = templates;
    }

    // ---- the register of a request ----

    /** The register of a request the caller may see. */
    @Transactional(readOnly = true)
    RegisterView get(UUID templateId, UUID requestId) {
        Reach reach = access.reach(templateId);
        RequestRow row = access.request(reach, requestId);
        return view(registerOf(row), tasks.mayAct(row, reach.userId(), reach.membershipId()));
    }

    /** The register as an Excel file. */
    @Transactional(readOnly = true)
    Download file(UUID templateId, UUID requestId) {
        Reach reach = access.reach(templateId);
        RequestRow row = access.request(reach, requestId);
        RegisterRow register = registerOf(row);
        return new Download(pages.xlsx(RegisterLayout.page(register.values())),
                fileName(register.values().requestNo()));
    }

    /**
     * Completes the bottom of the register. Needs to be asked to decide the request now (or to manage access) and the
     * request to be open: a register is final once its request is answered.
     *
     * @throws ApiException {@code NOT_FOUND}, {@code FORBIDDEN}, {@code CONFLICT} (not open),
     *         {@code VALIDATION_ERROR} (names the box), {@code CONCURRENT_MODIFICATION} (changed since it was read)
     */
    RegisterView complete(UUID templateId, UUID requestId, RegisterCompletion body) {
        Reach reach = access.reach(templateId);
        requests.lock(templateId);
        RequestRow row = access.request(reach, requestId);
        if (row.state() != ChangeRequestStatus.OPEN) {
            changes.refused("register", "closed");
            throw new ApiException(ErrorCode.CONFLICT, "This request is answered, so its register is final.");
        }
        if (!tasks.mayAct(row, reach.userId(), reach.membershipId())) {
            changes.refused("register", "forbidden");
            throw new ApiException(ErrorCode.FORBIDDEN, "Only the people asked to decide this request can complete "
                    + "its register.");
        }
        Checked<Completed> checked = RegisterRules.complete(body);
        if (!checked.accepted()) {
            changes.refused("register", "invalid");
            throw ApiException.validation(checked.problems());
        }
        RegisterRow before = registerOf(row);
        RegisterRow saved = store.complete(requestId, body.version(), checked.value(), reach.userId())
                .orElseThrow(() -> {
                    changes.refused("register", "conflict");
                    return new ApiException(ErrorCode.CONCURRENT_MODIFICATION);
                });
        changes.registerCompleted(reach.userId(), templateId, requestId, changedBoxes(before.values(), saved.values()));
        return view(saved, true);
    }

    // ---- the form before the request is raised ----

    /**
     * The register as it would be drawn for a request about the template with these typed parts: the platform's parts
     * filled in, nothing stored. For the screen that shows the Excel view while the person types.
     */
    @Transactional(readOnly = true)
    RegisterView draft(UUID templateId, RegisterInput typed) {
        RegisterValues values = draftValues(templateId, typed);
        return new RegisterView(nz(values.requestNo()), nz(values.documentName()), values.documentNo(),
                nz(values.revNo()), RegisterRules.show(values.requestedOn()), values.requestedBy(),
                nz(values.changeRequested()), values.basisForChange(), values.horizontalDeployment(), null, null, null,
                null, null, values.department(), values.obsoletedFormat(), RegisterRules.show(
                        values.obsoletedEffectiveOn()), null, null, 0L, false,
                pages.grid(RegisterLayout.page(values)), pages.print(RegisterLayout.page(values)));
    }

    /** The register with these typed parts as an Excel file, for a person who prefers to fill it there. */
    @Transactional(readOnly = true)
    Download draftFile(UUID templateId, RegisterInput typed) {
        RegisterValues values = draftValues(templateId, typed);
        return new Download(pages.xlsx(RegisterLayout.page(values)), fileName(values.requestNo()));
    }

    /**
     * Takes the typed parts out of a filled register. Nothing is stored; the form is filled with what was found and the
     * person raises the request from it. Parts the platform writes or the approvers complete are not taken.
     */
    @Transactional(readOnly = true)
    RegisterFileView read(UUID templateId, byte[] file) {
        templates.visible(templateId);
        Parsed parsed = RegisterLayout.parse(pages.read(file));
        if (parsed.refusal() != null) {
            throw ApiException.validation("file", parsed.refusal());
        }
        Map<Part, String> found = parsed.typed().values();
        String day = parsed.typed().dateText();
        List<String> notes = new ArrayList<>();
        if (found.isEmpty() && day == null) {
            notes.add("The file held none of the parts you type, so nothing was taken from it.");
        }
        if (day != null && RegisterLayout.parseDate(day).isEmpty()) {
            notes.add("The day the format or document is obsoleted was not written as year-month-day, so it was "
                    + "taken as typed; correct it before you raise the request.");
        }
        String shownDay = day == null ? null : RegisterLayout.parseDate(day).map(RegisterRules::show).orElse(day);
        return new RegisterFileView(new RegisterInput(found.getOrDefault(Part.REQUEST_NO, ""),
                found.getOrDefault(Part.CHANGE_REQUESTED, ""), found.get(Part.BASIS_FOR_CHANGE),
                found.get(Part.HORIZONTAL_DEPLOYMENT), found.get(Part.OBSOLETED_FORMAT), shownDay), notes);
    }

    // ---- the pieces ----

    private RegisterRow registerOf(RequestRow row) {
        return store.find(row.id()).orElseThrow(() -> ApiException.notFound("This change request has no register."));
    }

    private RegisterValues draftValues(UUID templateId, RegisterInput typed) {
        TemplateAccess.Caller caller = templates.visible(templateId);
        Facts platform = facts.of(templateId, caller.userId(), caller.membershipId());
        return new RegisterValues(clean(typed.requestNo()), platform.documentName(), platform.documentNo(),
                platform.revNo(), LocalDate.now(ZoneOffset.UTC), platform.requestedBy(),
                clean(typed.changeRequested()), clean(typed.basisForChange()), clean(typed.horizontalDeployment()),
                null, null, null, null, null, platform.department(), clean(typed.obsoletedFormat()),
                RegisterLayout.parseDate(typed.obsoletedEffectiveOn()).orElse(null), null, null);
    }

    private RegisterView view(RegisterRow row, boolean canComplete) {
        RegisterValues v = row.values();
        return new RegisterView(v.requestNo(), v.documentName(), v.documentNo(), v.revNo(),
                RegisterRules.show(v.requestedOn()), v.requestedBy(), v.changeRequested(), v.basisForChange(),
                v.horizontalDeployment(), v.approvedBy(), v.signatureNote(), v.newRevNo(),
                RegisterRules.show(v.effectiveOn()), row.newRevisionAt(), v.department(), v.obsoletedFormat(),
                RegisterRules.show(v.obsoletedEffectiveOn()), v.serverUpdated(), v.masterListUpdated(), row.version(),
                canComplete, pages.grid(RegisterLayout.page(v)), pages.print(RegisterLayout.page(v)));
    }

    /** The names of the boxes whose words changed. */
    private static List<String> changedBoxes(RegisterValues before, RegisterValues after) {
        List<String> changed = new ArrayList<>();
        COMPLETED.forEach((name, part) -> {
            if (!java.util.Objects.equals(of(before, part), of(after, part))) {
                changed.add(name);
            }
        });
        if (!java.util.Objects.equals(before.serverUpdated(), after.serverUpdated())) {
            changed.add("serverUpdated");
        }
        if (!java.util.Objects.equals(before.masterListUpdated(), after.masterListUpdated())) {
            changed.add("masterListUpdated");
        }
        java.util.Collections.sort(changed);
        return changed;
    }

    private static Object of(RegisterValues v, Part part) {
        return switch (part) {
            case DEPARTMENT -> v.department();
            case OBSOLETED_FORMAT -> v.obsoletedFormat();
            case OBSOLETED_DATE -> v.obsoletedEffectiveOn();
            case APPROVED_BY -> v.approvedBy();
            case SIGNATURE -> v.signatureNote();
            case EFFECTIVE_DATE -> v.effectiveOn();
            default -> null;
        };
    }

    private static String clean(String text) {
        String clean = ChangeText.clean(text);
        return clean.isEmpty() ? null : clean;
    }

    private static String nz(String text) {
        return text == null ? "" : text;
    }

    /** The name a register is offered under: the number the requester typed with anything odd made a hyphen. */
    static String fileName(String requestNo) {
        String base = requestNo == null || requestNo.isBlank() ? XLSX_NAME_FALLBACK
                : requestNo.strip().replaceAll("[^A-Za-z0-9._-]", "-");
        return (base.length() > 40 ? base.substring(0, 40) : base) + ".xlsx";
    }
}
