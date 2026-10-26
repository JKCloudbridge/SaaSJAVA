package app.platform.inspection.internal;

import app.platform.data.BackgroundRecords;
import app.platform.data.DataRecord;
import app.platform.inspection.internal.RegisterStore.Facts;
import app.platform.sharedkernel.directory.ReferenceLabels;
import app.platform.sheets.SheetLifecycle;
import app.platformapi.ApiException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The facts the platform writes in the register when a request is raised (FA8, ADR-0202): which document it is about,
 * which revision is in use, who asks and from which department. Read from where the platform already knows them, never
 * typed. A part the platform does not know (a template with no number yet, a person with no department) is left blank,
 * which is not an error.
 *
 * <p>The words are the organization's; nothing here is written to a log, an audit record, an event or a notice.
 */
@Component
class RegisterFacts {

    private static final int NAME_CHARS = 400;
    private static final int NUMBER_CHARS = 100;
    private static final int PERSON_CHARS = 200;

    private final BackgroundRecords records;
    private final TemplateRevisions revisions;
    private final SheetLifecycle sheets;
    private final DepartmentAudiences departments;
    private final List<ReferenceLabels> labels;

    RegisterFacts(BackgroundRecords records, TemplateRevisions revisions, SheetLifecycle sheets,
            DepartmentAudiences departments, List<ReferenceLabels> labels) {
        this.records = records;
        this.revisions = revisions;
        this.sheets = sheets;
        this.departments = departments;
        this.labels = labels;
    }

    /**
     * The facts for a request about a template, asked by a person.
     *
     * @throws ApiException {@code NOT_FOUND} for a template that does not exist, {@code CONFLICT} when no revision of
     *         it is in use
     */
    Facts of(UUID templateId, UUID userId, UUID membershipId) {
        DataRecord template = records.find(Templates.OBJECT, templateId)
                .orElseThrow(() -> ApiException.notFound("This template does not exist."));
        int active = sheets.summaries(Templates.OBJECT, List.of(templateId))
                .getOrDefault(templateId, new SheetLifecycle.Summary(0, 0, 0, 0, 0, 0, 0)).active();
        if (active <= 0) {
            throw new ApiException(app.platformapi.ErrorCode.CONFLICT,
                    "This template has no revision in use, so nothing can be asked about yet.");
        }
        return new Facts(cut(text(template, Templates.NAME), NAME_CHARS, "Template"),
                cut(text(template, Templates.NUMBER), NUMBER_CHARS, null), revisions.showOf(templateId, active),
                cut(nameOf(userId), PERSON_CHARS, null), departments.departmentsOf(membershipId));
    }

    private String nameOf(UUID userId) {
        Map<UUID, String> names = labels.stream().filter(one -> "User".equals(one.object())).findFirst()
                .map(one -> one.labels(Set.of(userId))).orElse(Map.of());
        return names.get(userId);
    }

    private static String text(DataRecord template, String field) {
        Object value = template.fields().get(field);
        return value instanceof String text && !text.isBlank() ? text.strip() : null;
    }

    private static String cut(String text, int most, String otherwise) {
        if (text == null) {
            return otherwise;
        }
        return text.length() <= most ? text : text.substring(0, most);
    }
}
