package app.platform.workflow.internal;

import app.platform.data.BackgroundRecords;
import app.platform.data.DataRecord;
import app.platform.identity.User;
import app.platform.identity.UserStatus;
import app.platform.identity.Users;
import app.platform.metadata.FieldDefinition;
import app.platform.metadata.FieldType;
import app.platform.metadata.Metadata;
import app.platform.metadata.ObjectDefinition;
import app.platform.sharedkernel.directory.ReferenceDirectory;
import app.platform.sharedkernel.mail.MailQueue;
import app.platform.sharedkernel.mail.MailRequest;
import app.platform.sharedkernel.mail.MailTemplate;
import app.platform.tenant.TenantContexts;
import app.platformapi.WorkflowActionTypes;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Queues one platform mail to the person a record names (ADR-0138, ADR-0141). The request is written in the transaction
 * of the step, so it is queued once however often the step is claimed or retried; sending, and the retries of sending,
 * belong to the mail relay (ADR-0024) and nothing here adds a second retry.
 *
 * <p><strong>Facts only.</strong> The request holds the organization, the object (its API name and label) and the
 * record identifier. It never holds a value of the record, not even its title: the mail leaves the platform's control
 * and the queue row would keep the value for as long as the mail is retained. The relay builds the link to the record
 * on the organization's own host when it sends.
 *
 * <p>The recipient is the user a user field names, who must still be an active member of the organization, or the
 * address in an e-mail field. A record with no recipient fails the step with a stable reason that no retry can mend.
 */
@Component
class SendEmailAction implements ActionExecutor {

    private static final Pattern ADDRESS = Pattern.compile("[^@\\s<>\"]+@[^@\\s<>\"]+");
    private static final String USER_OBJECT = "User";

    private final BackgroundRecords records;
    private final Metadata metadata;
    private final Users users;
    private final MailQueue mail;
    private final TenantContexts contexts;
    private final ExecutionStore executions;
    private final WorkflowProperties.Limits limits;
    private final Clock clock;
    private final Map<String, ReferenceDirectory> directories;

    SendEmailAction(BackgroundRecords records, Metadata metadata, Users users, MailQueue mail,
            TenantContexts contexts, ExecutionStore executions, WorkflowProperties properties, Clock clock,
            List<ReferenceDirectory> directories) {
        this.records = records;
        this.metadata = metadata;
        this.users = users;
        this.mail = mail;
        this.contexts = contexts;
        this.executions = executions;
        this.limits = properties.limits();
        this.clock = clock;
        this.directories = directories.stream().collect(java.util.stream.Collectors.toMap(
                ReferenceDirectory::object, directory -> directory, (first, second) -> first));
    }

    @Override
    public String type() {
        return WorkflowActionTypes.SEND_EMAIL;
    }

    @Override
    public void execute(ActionContext context) {
        ObjectDefinition object = metadata.object(context.objectApiName())
                .orElseThrow(() -> StepFailure.permanent("object_unavailable"));
        DataRecord record = records.find(context.objectApiName(), context.recordId())
                .orElseThrow(() -> StepFailure.permanent("record_gone"));
        String fieldName = context.settings().get("recipientField");
        FieldDefinition field = object.field(fieldName)
                .orElseThrow(() -> StepFailure.permanent("recipient_field_gone"));
        Object value = record.fields().get(fieldName);
        if (value == null || value.toString().isBlank()) {
            throw StepFailure.permanent("recipient_missing");
        }
        if (executions.mailStepsSince(clock.instant().minus(Duration.ofHours(1)))
                >= limits.mailsPerOrganizationPerHour()) {
            // Nothing is lost: the step waits for its next attempt, by which time the hour has moved on.
            throw StepFailure.transientFailure("mail_rate_limited");
        }
        String address;
        UUID userId = null;
        if (field.type() == FieldType.EMAIL || field.type() == FieldType.FORMULA) {
            address = value.toString().strip().toLowerCase(Locale.ROOT);
        } else {
            userId = userOf(value);
            User user = users.findById(userId).orElseThrow(() -> StepFailure.permanent("recipient_not_member"));
            ReferenceDirectory members = directories.get(USER_OBJECT);
            if (user.status() != UserStatus.ACTIVE || members == null || !members.exists(userId)) {
                throw StepFailure.permanent("recipient_not_member");
            }
            address = user.email().strip().toLowerCase(Locale.ROOT);
        }
        if (address.length() < 3 || address.length() > 254 || !ADDRESS.matcher(address).matches()) {
            throw StepFailure.permanent("recipient_invalid");
        }
        mail.enqueue(new MailRequest(MailTemplate.RECORD_NOTICE, address, userId, Map.of(
                "organization_id", contexts.require().tenantId().value().toString(),
                "object", object.apiName(), "object_label", object.label(),
                "record_id", context.recordId().toString())));
    }

    private static UUID userOf(Object value) {
        try {
            return UUID.fromString(value.toString());
        } catch (IllegalArgumentException notAnIdentifier) {
            throw StepFailure.permanent("recipient_not_member");
        }
    }
}
