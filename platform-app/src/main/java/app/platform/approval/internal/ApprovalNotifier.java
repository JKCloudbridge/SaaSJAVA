package app.platform.approval.internal;

import app.platform.identity.User;
import app.platform.identity.UserStatus;
import app.platform.identity.Users;
import app.platform.metadata.Metadata;
import app.platform.metadata.ObjectDefinition;
import app.platform.sharedkernel.mail.MailQueue;
import app.platform.sharedkernel.mail.MailRequest;
import app.platform.sharedkernel.mail.MailTemplate;
import app.platform.tenant.TenantContexts;
import java.util.Collection;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Tells the people asked that they have a task (ADR-0152): one closed mail template, facts only. The request holds the
 * organization, the object (its API name and label), the record identifier and the label of the process; it never holds
 * a title or a value of the record, because the queue row would keep it for as long as the mail is retained and the
 * mail
 * leaves the platform's control. The relay builds the link to the inbox when it sends; a decision is never made from a
 * link.
 *
 * <p>The request is written in the transaction that created the task, so it is queued exactly once however often the
 * step is claimed or retried; sending and the retries of sending belong to the mail relay (ADR-0024). A person who has
 * been mailed in the last minute is not mailed again (the inbox shows every task).
 */
@Component
class ApprovalNotifier {

    private static final long QUIET_SECONDS = 60;

    private final MailQueue mail;
    private final Users users;
    private final Metadata metadata;
    private final TenantContexts contexts;
    private final ApprovalProperties.Mail settings;

    ApprovalNotifier(MailQueue mail, Users users, Metadata metadata, TenantContexts contexts,
            ApprovalProperties properties) {
        this.mail = mail;
        this.users = users;
        this.metadata = metadata;
        this.contexts = contexts;
        this.settings = properties.mail();
    }

    /** Queues the mail for each person who has a new task. */
    void tasksCreated(ApprovalStore.InstanceRow instance, Collection<UUID> assignees) {
        if (!settings.enabled() || assignees.isEmpty()) {
            return;
        }
        ObjectDefinition object = metadata.object(instance.objectApiName()).orElse(null);
        String organization = contexts.require().tenantId().value().toString();
        for (UUID assignee : assignees) {
            Optional<User> user = users.findById(assignee);
            if (user.isEmpty() || user.get().status() != UserStatus.ACTIVE
                    || mail.queuedRecently(MailTemplate.APPROVAL_REQUEST, assignee, QUIET_SECONDS)) {
                continue;
            }
            mail.enqueue(new MailRequest(MailTemplate.APPROVAL_REQUEST,
                    user.get().email().strip().toLowerCase(Locale.ROOT), assignee, Map.of(
                            "organization_id", organization, "object", instance.objectApiName(),
                            "object_label", object == null ? instance.objectApiName() : object.label(),
                            "record_id", instance.recordId().toString(),
                            "process_label", instance.processLabel())));
        }
    }
}
