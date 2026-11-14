package app.platform.notification.internal;

import app.platform.identity.User;
import app.platform.identity.UserStatus;
import app.platform.identity.Users;
import app.platform.metadata.Metadata;
import app.platform.metadata.ObjectDefinition;
import app.platform.notification.internal.PreferenceRules.Channel;
import app.platform.sharedkernel.ActorId;
import app.platform.sharedkernel.directory.ReferenceDirectory;
import app.platform.sharedkernel.mail.MailQueue;
import app.platform.sharedkernel.mail.MailRequest;
import app.platform.sharedkernel.notice.NoticeRequest;
import app.platform.sharedkernel.notice.Notices;
import app.platform.tenant.TenantContexts;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Makes the in-app notices (Sprint 25, ADR-0154). The one door to the notice table: everybody who needs to tell a
 * member something asks through {@link Notices}, in the transaction of the change that caused it and in the
 * organization's own tenant context, so a rolled-back change leaves no notice and no notice is ever written for another
 * organization.
 *
 * <p><strong>Once.</strong> The unique key of the table (recipient and the request's key) is the only line of defence
 * that matters: a retried step, a replayed execution, a redelivered event and two instances racing all make the same
 * request with the same key and get {@code DUPLICATE}.
 *
 * <p><strong>Wanted.</strong> The recipient's choice is read now (ADR-0156): a switch that is off creates nothing. A
 * recipient who is not an active member of the organization gets nothing either. Neither case is an error for the
 * caller, which goes on.
 *
 * <p>The metric carries the kind and the result, both closed lists; never a recipient, a record or a key.
 */
@Component
class DefaultNotices implements Notices {

    private final NoticeStore store;
    private final NotificationPreferences preferences;
    private final List<ReferenceDirectory> directories;
    private final TenantContexts contexts;
    private final MeterRegistry meters;
    private final MailQueue mail;
    private final Users users;
    private final Metadata metadata;

    DefaultNotices(NoticeStore store, NotificationPreferences preferences, List<ReferenceDirectory> directories,
            TenantContexts contexts, MeterRegistry meters, MailQueue mail, Users users, Metadata metadata) {
        this.mail = mail;
        this.users = users;
        this.metadata = metadata;
        this.store = store;
        this.preferences = preferences;
        this.directories = directories;
        this.contexts = contexts;
        this.meters = meters;
    }

    @Override
    public Result notify(NoticeRequest request) {
        contexts.require();
        Result result = decide(request);
        if (result != Result.DUPLICATE) {
            // A person who switched the in-app notice off may still want the mail: that choice is made at send time.
            enqueueMail(request);
        }
        meters.counter("platform.notification.notices", "kind", request.kind().name(), "result",
                result.name().toLowerCase(java.util.Locale.ROOT)).increment();
        return result;
    }

    private Result decide(NoticeRequest request) {
        if (!activeMember(request)) {
            return Result.SUPPRESSED;
        }
        if (!preferences.wants(request.recipientId(), request.kind(), Channel.IN_APP)) {
            return Result.SUPPRESSED;
        }
        boolean made = store.insert(request.recipientId(), request.kind().name(), request.objectApiName(),
                request.recordId(), request.facts(), request.dedupeKey(), ActorId.SYSTEM.value());
        return made ? Result.CREATED : Result.DUPLICATE;
    }

    /**
     * The mail that goes with a kind that has one (MC1.1, ADR-0259), queued in the same transaction as the notice and
     * only when the notice is new, so a repeat tells nobody twice. The person's own switch for the mail channel is
     * decided later, when the relay builds the message, as for every mail of an organization. It carries the notice's
     * facts and the object's name, never a title, a number or a value.
     */
    private void enqueueMail(NoticeRequest request) {
        request.kind().mail().ifPresent(template -> {
            Optional<User> user = users.findById(request.recipientId());
            if (user.isEmpty() || user.get().status() != UserStatus.ACTIVE || request.objectApiName() == null
                    || request.recordId() == null) {
                return;
            }
            Map<String, String> variables = new HashMap<>();
            variables.put("organization_id", contexts.require().tenantId().value().toString());
            variables.put("object", request.objectApiName());
            variables.put("object_label", metadata.object(request.objectApiName()).map(ObjectDefinition::label)
                    .orElse(request.objectApiName()));
            variables.put("record_id", request.recordId().toString());
            request.facts().forEach((name, value) -> {
                if (name.equals("application") || name.equals("entry")) {
                    variables.put(name, value);
                }
            });
            mail.enqueue(new MailRequest(template, user.get().email().strip().toLowerCase(Locale.ROOT),
                    request.recipientId(), variables));
        });
    }

    private boolean activeMember(NoticeRequest request) {
        return directories.stream().filter(directory -> "User".equals(directory.object())).findFirst()
                .map(directory -> directory.exists(request.recipientId())).orElse(false);
    }
}
