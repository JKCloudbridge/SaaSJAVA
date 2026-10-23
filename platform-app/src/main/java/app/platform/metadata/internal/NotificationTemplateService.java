package app.platform.metadata.internal;

import app.platform.identity.OrganizationAdministration.Caller;
import app.platform.metadata.NotificationTemplate;
import app.platform.metadata.NotificationTemplates;
import app.platform.sharedkernel.notice.NoticeKind;
import app.platform.sharedkernel.notice.NoticeText;
import app.platformapi.ApiException;
import app.platformapi.CheckNotificationTemplateRequest;
import app.platformapi.CreateNotificationTemplateRequest;
import app.platformapi.ErrorCode;
import app.platformapi.NotificationPlaceholderView;
import app.platformapi.NotificationTemplateCheckView;
import app.platformapi.NotificationTemplateView;
import app.platformapi.NotificationTemplatesView;
import app.platformapi.UpdateNotificationTemplateRequest;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * The text an administrator writes for notices and mails (Sprint 25, ADR-0155). Like {@link ValidationRuleService} it
 * runs inside the transaction that {@link MetadataAdministration} opened, and it changes published rows only; drafts
 * are the change sets' business. The rules of the text itself are {@link NoticeText}, the same ones the notification
 * module relies on when it builds the words, so a text that passes here can always be rendered.
 *
 * <p>Nothing here repeats or logs the text: it is written by a person. The audit says which kind changed, not what it
 * says.
 */
@Service
class NotificationTemplateService implements NotificationTemplates {

    /** Labels used in the preview. They stand for nothing real: a preview never shows a value of a record. */
    private static final Map<String, String> SAMPLE = Map.of("organization", "Example Organization",
            "object", "Invoice", "process", "Large expense", "step", "Manager review", "workflow",
            "Remind the owner", "outcome", "approved");

    private final NotificationTemplateStore store;
    private final MetadataVersions versions;
    private final MetadataAudit audit;

    NotificationTemplateService(NotificationTemplateStore store, MetadataVersions versions, MetadataAudit audit) {
        this.store = store;
        this.versions = versions;
        this.audit = audit;
    }

    // ---- reading ----

    @Override
    public Optional<NotificationTemplate> find(NoticeKind kind) {
        return store.find(kind.name()).map(NotificationTemplateService::template);
    }

    /** Every kind an administrator may write, with the text in use. */
    NotificationTemplatesView templates() {
        Map<String, NotificationTemplateStore.Row> written = new LinkedHashMap<>();
        store.all().forEach(row -> written.put(row.kind(), row));
        List<NotificationTemplateView> views = Arrays.stream(NoticeKind.values()).filter(NoticeKind::writable)
                .map(kind -> view(kind, written.get(kind.name()))).toList();
        return new NotificationTemplatesView(views, NoticeText.TITLE_MAX, NoticeText.BODY_MAX);
    }

    NotificationTemplateView template(String kindCode) {
        NoticeKind kind = writableKind(kindCode);
        return view(kind, store.find(kind.name()).orElse(null));
    }

    // ---- trying ----

    /** The real rules answer; nothing is stored, audited or logged, and nothing of the text is repeated. */
    NotificationTemplateCheckView check(CheckNotificationTemplateRequest request) {
        NoticeKind kind = writableKind(request.kind());
        List<NoticeText.Problem> found = NoticeText.validate(kind, request.title(), request.body());
        Map<String, List<String>> problems = new LinkedHashMap<>();
        found.forEach(problem -> problems.computeIfAbsent(problem.place(), place -> new java.util.ArrayList<>())
                .add(problem.message()));
        if (!found.isEmpty()) {
            return new NotificationTemplateCheckView(problems, null, null);
        }
        return new NotificationTemplateCheckView(problems, NoticeText.render(request.title(), SAMPLE),
                NoticeText.render(request.body(), SAMPLE));
    }

    // ---- changing ----

    NotificationTemplateView create(Caller caller, CreateNotificationTemplateRequest request) {
        NoticeKind kind = writableKind(request.kind());
        String title = cleanLine(request.title());
        String body = request.body().strip();
        refuse(NoticeText.validate(kind, title, body));
        if (store.find(kind.name()).isPresent()) {
            throw ApiException.validation("kind", "The text of this kind has been written already; change it "
                    + "instead.");
        }
        versions.wrote();
        NotificationTemplateStore.Row row;
        try {
            row = store.insert(kind.name(), title, body, caller.userId());
        } catch (DuplicateKeyException e) {
            throw ApiException.validation("kind", "The text of this kind has been written already; change it "
                    + "instead.");
        }
        audit.notificationTemplateWritten(caller.userId(), kind.name(), true);
        return view(kind, row);
    }

    NotificationTemplateView update(Caller caller, String kindCode, UpdateNotificationTemplateRequest request) {
        NoticeKind kind = writableKind(kindCode);
        String title = cleanLine(request.title());
        String body = request.body().strip();
        refuse(NoticeText.validate(kind, title, body));
        NotificationTemplateStore.Row row = store.find(kind.name()).orElseThrow(
                () -> ApiException.notFound("The text of this kind has not been written."));
        if (row.version() != request.version()) {
            throw new ApiException(ErrorCode.CONCURRENT_MODIFICATION);
        }
        if (row.title().equals(title) && row.body().equals(body)) {
            return view(kind, row);
        }
        versions.wrote();
        if (!store.update(row.id(), request.version(), title, body, caller.userId())) {
            throw new ApiException(ErrorCode.CONCURRENT_MODIFICATION);
        }
        audit.notificationTemplateWritten(caller.userId(), kind.name(), false);
        return view(kind, store.find(kind.name()).orElseThrow());
    }

    /** Goes back to the platform's own text. */
    void delete(Caller caller, String kindCode) {
        NoticeKind kind = writableKind(kindCode);
        NotificationTemplateStore.Row row = store.find(kind.name()).orElseThrow(
                () -> ApiException.notFound("The text of this kind has not been written."));
        versions.wrote();
        store.delete(row.id(), caller.userId());
        audit.notificationTemplateReset(caller.userId(), kind.name());
    }

    // ---- helpers ----

    /** The view of a kind with the row that holds its text, or null when the platform's own text is in use. */
    NotificationTemplateView view(NoticeKind kind, NotificationTemplateStore.Row row) {
        List<NotificationPlaceholderView> placeholders = NoticeText.placeholders(kind).stream()
                .map(name -> new NotificationPlaceholderView(name, NoticeText.describe(name))).toList();
        return new NotificationTemplateView(kind.name(), kind.category().name(), summary(kind), row != null,
                row == null ? NoticeText.defaultTitle(kind) : row.title(),
                row == null ? NoticeText.defaultBody(kind) : row.body(), NoticeText.defaultTitle(kind),
                NoticeText.defaultBody(kind), placeholders, row == null ? null : row.version());
    }

    private static String summary(NoticeKind kind) {
        return switch (kind) {
            case APPROVAL_TASK -> "A member is asked to decide a step of an approval (notice and mail).";
            case APPROVAL_REMINDER -> "A step has waited longer than the process allows (notice and mail).";
            case APPROVAL_DECIDED -> "An approval a member submitted has ended (notice).";
            case WORKFLOW_NOTICE -> "A workflow asks a member to look at a record (notice and mail).";
            case TRIAL_NOTICE -> "The trial of the organization is ending.";
            case CHANGE_REQUEST -> "Something happened to a change request on a record (the platform's own text).";
        };
    }

    private static NoticeKind writableKind(String code) {
        NoticeKind kind = code == null ? null : NoticeKind.fromCode(code.strip()).orElse(null);
        if (kind == null || !kind.writable()) {
            throw ApiException.validation("kind", "Choose a kind of notice whose text can be written: "
                    + Arrays.stream(NoticeKind.values()).filter(NoticeKind::writable).map(NoticeKind::name).toList()
                    + ".");
        }
        return kind;
    }

    private static String cleanLine(String title) {
        return title.strip();
    }

    private static void refuse(List<NoticeText.Problem> found) {
        if (found.isEmpty()) {
            return;
        }
        Problems problems = new Problems();
        found.forEach(problem -> problems.add(problem.place(), problem.message()));
        problems.throwIfAny();
    }

    private static NotificationTemplate template(NotificationTemplateStore.Row row) {
        return new NotificationTemplate(NoticeKind.fromCode(row.kind()).orElseThrow(), row.title(), row.body(),
                row.version());
    }
}
