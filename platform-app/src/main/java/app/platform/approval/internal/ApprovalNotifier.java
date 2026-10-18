package app.platform.approval.internal;

import app.platform.approval.internal.ApprovalStore.InstanceRow;
import app.platform.approval.internal.ApprovalStore.StepRow;
import app.platform.identity.User;
import app.platform.identity.UserStatus;
import app.platform.identity.Users;
import app.platform.metadata.Metadata;
import app.platform.metadata.ObjectDefinition;
import app.platform.sharedkernel.mail.MailQueue;
import app.platform.sharedkernel.mail.MailRequest;
import app.platform.sharedkernel.mail.MailTemplate;
import app.platform.sharedkernel.notice.NoticeKind;
import app.platform.sharedkernel.notice.NoticeRequest;
import app.platform.sharedkernel.notice.Notices;
import app.platform.tenant.TenantContexts;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Tells people what happens in an approval: that they have a task, that a task has waited too long, that a request they
 * submitted ended. Each is an in-app notice through the shared-kernel {@link Notices} contract and, for a task and a
 * reminder, a mail through {@link MailQueue}; whether either reaches the person is their own choice, decided where the
 * notice is made and where the mail is sent (ADR-0156). Nothing here waits for either channel: both are written in the
 * transaction of the change and delivered later.
 *
 * <p><strong>Facts only</strong> (ADR-0152, ADR-0154). The notice holds the kind, the API names of the process and the
 * step, and the object and identifier of the record; the mail request holds the organization, the object (API name and
 * label), the record identifier and the labels of the process and step. Neither holds a title, a value of the record or
 * a comment, because both outlive the request and the mail leaves the platform's control. The relay builds the link to
 * the inbox when it sends; a decision is never made from a link.
 *
 * <p><strong>Once</strong> (ADR-0154). The keys of the notices are made of the identifiers of the step and the person,
 * so a retried step, a second instance and a redelivered event make the same request and get nothing new. A person who
 * has been mailed about a task in the last minute is not mailed again (the inbox shows every task).
 */
@Component
class ApprovalNotifier {

    private static final long QUIET_SECONDS = 60;

    private final MailQueue mail;
    private final Notices notices;
    private final Users users;
    private final Metadata metadata;
    private final TenantContexts contexts;
    private final ApprovalProperties.Mail settings;

    ApprovalNotifier(MailQueue mail, Notices notices, Users users, Metadata metadata, TenantContexts contexts,
            ApprovalProperties properties) {
        this.mail = mail;
        this.notices = notices;
        this.users = users;
        this.metadata = metadata;
        this.contexts = contexts;
        this.settings = properties.mail();
    }

    /** Tells each person who has a new task of the step. */
    void tasksCreated(InstanceRow instance, StepRow step, Collection<UUID> assignees) {
        for (UUID assignee : assignees) {
            notice(NoticeKind.APPROVAL_TASK, instance, step, assignee, "apv-task:" + step.id() + ":" + assignee);
            mail(MailTemplate.APPROVAL_REQUEST, instance, step, assignee, true);
        }
    }

    /** Reminds each person who still has an open task of the step; {@code sequence} is the number of the reminder. */
    void reminded(InstanceRow instance, StepRow step, Collection<UUID> assignees, int sequence) {
        for (UUID assignee : assignees) {
            notice(NoticeKind.APPROVAL_REMINDER, instance, step, assignee,
                    "apv-remind:" + step.id() + ":" + sequence + ":" + assignee);
            mail(MailTemplate.APPROVAL_REMINDER, instance, step, assignee, false);
        }
    }

    /**
     * Tells the person who submitted a request that it ended, unless they ended it themselves or the platform
     * submitted it (a workflow has nobody to tell). An administrator's cancellation is not announced as a decision.
     */
    void ended(InstanceRow instance, String status, UUID actor) {
        if (instance.submittedBy() == null || instance.submittedBy().equals(actor)
                || !Set.of("APPROVED", "REJECTED", "RECALLED").contains(status)) {
            return;
        }
        Map<String, String> facts = new LinkedHashMap<>();
        facts.put("process", instance.processApiName());
        facts.put("outcome", status);
        notices.notify(new NoticeRequest(NoticeKind.APPROVAL_DECIDED, instance.submittedBy(),
                instance.objectApiName(), instance.recordId(), facts, "apv-decided:" + instance.id()));
    }

    private void notice(NoticeKind kind, InstanceRow instance, StepRow step, UUID recipient, String key) {
        Map<String, String> facts = new LinkedHashMap<>();
        facts.put("process", instance.processApiName());
        facts.put("step", step.name());
        notices.notify(new NoticeRequest(kind, recipient, instance.objectApiName(), instance.recordId(), facts, key));
    }

    private void mail(MailTemplate template, InstanceRow instance, StepRow step, UUID assignee, boolean quiet) {
        if (!settings.enabled()) {
            return;
        }
        ObjectDefinition object = metadata.object(instance.objectApiName()).orElse(null);
        Optional<User> user = users.findById(assignee);
        if (user.isEmpty() || user.get().status() != UserStatus.ACTIVE
                || quiet && mail.queuedRecently(template, assignee, QUIET_SECONDS)) {
            return;
        }
        mail.enqueue(new MailRequest(template, user.get().email().strip().toLowerCase(Locale.ROOT), assignee, Map.of(
                "organization_id", contexts.require().tenantId().value().toString(),
                "object", instance.objectApiName(),
                "object_label", object == null ? instance.objectApiName() : object.label(),
                "record_id", instance.recordId().toString(), "process_label", instance.processLabel(),
                "step_label", step.label())));
    }
}
