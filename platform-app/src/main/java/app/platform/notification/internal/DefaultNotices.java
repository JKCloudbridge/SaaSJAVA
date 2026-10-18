package app.platform.notification.internal;

import app.platform.notification.internal.PreferenceRules.Channel;
import app.platform.sharedkernel.ActorId;
import app.platform.sharedkernel.directory.ReferenceDirectory;
import app.platform.sharedkernel.notice.NoticeRequest;
import app.platform.sharedkernel.notice.Notices;
import app.platform.tenant.TenantContexts;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
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

    DefaultNotices(NoticeStore store, NotificationPreferences preferences, List<ReferenceDirectory> directories,
            TenantContexts contexts, MeterRegistry meters) {
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

    private boolean activeMember(NoticeRequest request) {
        return directories.stream().filter(directory -> "User".equals(directory.object())).findFirst()
                .map(directory -> directory.exists(request.recipientId())).orElse(false);
    }
}
