package app.platform.notification.internal;

import app.platform.metadata.ApprovalProcessDefinition;
import app.platform.metadata.Metadata;
import app.platform.metadata.NotificationTemplate;
import app.platform.metadata.NotificationTemplates;
import app.platform.metadata.ObjectDefinition;
import app.platform.metadata.WorkflowDefinition;
import app.platform.sharedkernel.notice.NoticeKind;
import app.platform.sharedkernel.notice.NoticeText;
import app.platform.tenant.TenantContexts;
import app.platform.tenant.Tenants;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Builds the words of a notice when it is read (Sprint 25, ADR-0155). A notice stores facts, never text, so a change of
 * the template reaches the notices that exist already. The text is the template the administrator wrote for the kind
 * or, when none was written, the platform's own; the facts enter it through the closed list of placeholders and each is
 * a label cleaned by {@link NoticeText#safeName}. A label that no longer exists (a removed process, a renamed object)
 * simply leaves its place empty: a notice is never an error.
 *
 * <p>One {@link Context} serves one request: the name of the organization and the templates are read once, the labels
 * of the objects, processes and workflows are remembered as they are asked for. It must be used in the transaction
 * and tenant context the request runs in.
 */
@Component
class NoticeTexts {

    /**
     * The words of one notice.
     *
     * @param title the headline
     * @param body the sentence under it
     * @param href the path the notice leads to on the organization's host, or null
     */
    record Rendered(String title, String body, String href) {
    }

    private static final Map<String, String> OUTCOMES = Map.of("APPROVED", "approved", "REJECTED", "rejected",
            "RECALLED", "withdrawn");
    /** What happened to a change request, by the stable key the notice carries (FA5, ADR-0191). */
    private static final Map<String, String> EVENTS = Map.of(
            "requested", "a change was asked for",
            "commented", "a comment was added",
            "accepted", "it was accepted",
            "declined", "it was declined",
            "done", "it was turned into a new version");
    private static final Map<String, String> TRIAL = Map.of(
            "first", "The trial of {organization} is ending. When the grace period is over, the organization is "
                    + "suspended: nothing is deleted, and a platform administrator can open it again once a plan is "
                    + "active.",
            "final", "The trial of {organization} ends tomorrow. After that the organization is suspended: nothing "
                    + "is deleted, and a platform administrator can open it again once a plan is active.",
            "suspended", "The trial of {organization} is over and the organization is suspended. Nothing was "
                    + "deleted; a platform administrator can open it again once a plan is active.");

    private final Tenants tenants;
    private final TenantContexts contexts;
    private final Metadata metadata;
    private final NotificationTemplates templates;

    NoticeTexts(Tenants tenants, TenantContexts contexts, Metadata metadata, NotificationTemplates templates) {
        this.tenants = tenants;
        this.contexts = contexts;
        this.metadata = metadata;
        this.templates = templates;
    }

    /** A context for one request. */
    Context context() {
        String organization = tenants.findById(contexts.require().tenantId()).map(tenant -> tenant.displayName())
                .orElse("");
        Map<NoticeKind, Optional<NotificationTemplate>> written = new EnumMap<>(NoticeKind.class);
        for (NoticeKind kind : NoticeKind.values()) {
            if (kind.writable()) {
                written.put(kind, templates.find(kind));
            }
        }
        return new Context(organization, written);
    }

    /** What one request needs to build the words of its notices. */
    final class Context {

        private final String organization;
        private final Map<NoticeKind, Optional<NotificationTemplate>> written;
        private final Map<String, Optional<ObjectDefinition>> objects = new HashMap<>();
        private final Map<String, Map<String, ApprovalProcessDefinition>> processes = new HashMap<>();
        private final Map<String, Map<String, WorkflowDefinition>> workflows = new HashMap<>();

        private Context(String organization, Map<NoticeKind, Optional<NotificationTemplate>> written) {
            this.organization = organization;
            this.written = written;
        }

        /** The words and the place of one notice. */
        Rendered render(NoticeStore.Row row) {
            NoticeKind kind = NoticeKind.fromCode(row.kind()).orElse(null);
            if (kind == null) {
                return new Rendered("A notice", "", null);
            }
            Map<String, String> values = values(kind, row);
            String title;
            String body;
            if (kind == NoticeKind.TRIAL_NOTICE) {
                title = NoticeText.defaultTitle(kind);
                body = NoticeText.render(TRIAL.getOrDefault(row.facts().get("stage"), TRIAL.get("first")), values);
            } else {
                Optional<NotificationTemplate> custom = written.getOrDefault(kind, Optional.empty());
                title = NoticeText.render(custom.map(NotificationTemplate::title)
                        .orElseGet(() -> NoticeText.defaultTitle(kind)), values);
                body = NoticeText.render(custom.map(NotificationTemplate::body)
                        .orElseGet(() -> NoticeText.defaultBody(kind)), values);
            }
            return new Rendered(title, body, href(kind, row));
        }

        private Map<String, String> values(NoticeKind kind, NoticeStore.Row row) {
            Map<String, String> values = new LinkedHashMap<>();
            values.put("organization", organization);
            String object = row.objectApiName() == null ? null : objectLabel(row.objectApiName());
            values.put("object", object == null ? "" : object);
            String process = row.facts().get("process");
            if (process != null && row.objectApiName() != null) {
                ApprovalProcessDefinition definition = process(row.objectApiName(), process);
                values.put("process", definition == null ? "" : definition.label());
                String step = row.facts().get("step");
                if (definition != null && step != null) {
                    definition.steps().stream().filter(one -> one.name().equals(step)).findFirst()
                            .ifPresent(one -> values.put("step", one.label()));
                }
            }
            String workflow = row.facts().get("workflow");
            if (workflow != null && row.objectApiName() != null) {
                WorkflowDefinition definition = workflow(row.objectApiName(), workflow);
                values.put("workflow", definition == null ? "" : definition.label());
            }
            String outcome = row.facts().get("outcome");
            if (outcome != null) {
                values.put("outcome", OUTCOMES.getOrDefault(outcome, ""));
            }
            String event = row.facts().get("event");
            if (event != null) {
                values.put("event", EVENTS.getOrDefault(event, "there is news"));
            }
            return values;
        }

        private String objectLabel(String apiName) {
            return objects.computeIfAbsent(apiName, name -> metadata.object(name)).map(ObjectDefinition::label)
                    .orElse(apiName);
        }

        private ApprovalProcessDefinition process(String object, String apiName) {
            return processes.computeIfAbsent(object, name -> {
                Map<String, ApprovalProcessDefinition> byName = new HashMap<>();
                metadata.approvalProcesses(name).forEach(one -> byName.put(one.apiName(), one));
                return byName;
            }).get(apiName);
        }

        private WorkflowDefinition workflow(String object, String apiName) {
            return workflows.computeIfAbsent(object, name -> {
                Map<String, WorkflowDefinition> byName = new HashMap<>();
                metadata.workflows(name).forEach(one -> byName.put(one.apiName(), one));
                return byName;
            }).get(apiName);
        }

        /**
         * Where the notice leads. A task opens the inbox, where it is decided; anything about a record opens the
         * record, whose own screen says "not found" to a member who cannot read it (an approval grants no access).
         */
        private String href(NoticeKind kind, NoticeStore.Row row) {
            if (kind == NoticeKind.APPROVAL_TASK || kind == NoticeKind.APPROVAL_REMINDER) {
                return "/approvals";
            }
            if (kind == NoticeKind.CHANGE_REQUEST && row.recordId() != null) {
                // The page of the application that shows the record with its requests; both parts are facts of the
                // notice, checked at creation to be only letters, digits and a few marks. Without them: the record.
                String application = row.facts().get("application");
                String entry = row.facts().get("entry");
                if (application != null && entry != null) {
                    return "/apps/" + application + "/" + entry + "?open=" + row.recordId();
                }
            }
            if (row.objectApiName() != null && row.recordId() != null) {
                return "/records/" + row.objectApiName() + "/" + row.recordId();
            }
            return null;
        }
    }
}
