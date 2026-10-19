package app.platform.notification.internal;

import app.platform.notification.internal.PreferenceRules.Channel;
import app.platform.sharedkernel.audit.AuditOutcome;
import app.platform.sharedkernel.audit.AuditRecord;
import app.platform.sharedkernel.audit.AuditRecorder;
import app.platform.sharedkernel.notice.NoticeKind;
import app.platform.sharedkernel.notice.NoticeKind.Category;
import app.platform.tenant.TenantContexts;
import app.platformapi.ApiException;
import app.platformapi.NotificationPreferencesView;
import app.platformapi.PreferenceChoiceRequest;
import app.platformapi.PreferenceChoiceView;
import app.platformapi.UpdateNotificationPreferencesRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A person's choices of which notices and mails they want (Sprint 25, ADR-0156). It answers for the organization of the
 * thread's tenant context, in the caller's transaction. The choice is read where the decision is made: at creation for
 * an in-app notice (a notice that is off is never made) and at send time for a mail (the relay already decides then).
 *
 * <p>The floor is not stored anywhere: {@link PreferenceRules} applies it on every read, and the database refuses a row
 * that would switch the account category off.
 */
@Service
class NotificationPreferences {

    private static final Map<Category, String[]> WORDS = Map.of(
            Category.APPROVALS, new String[] {"Approvals",
                "Tasks, reminders and the end of approvals you take part in."},
            Category.WORKFLOWS, new String[] {"Workflow notices",
                "Notices that a workflow of your organization sends you."},
            Category.ACCOUNT, new String[] {"Your account and organization",
                "Security of your account and the standing of your organization. These always reach you."});

    private final PreferenceStore store;
    private final TenantContexts contexts;
    private final AuditRecorder audit;

    NotificationPreferences(PreferenceStore store, TenantContexts contexts, AuditRecorder audit) {
        this.store = store;
        this.contexts = contexts;
        this.audit = audit;
    }

    /** Whether the person wants a notice of this kind on this channel. */
    boolean wants(UUID user, NoticeKind kind, Channel channel) {
        if (PreferenceRules.locked(kind)) {
            return true;
        }
        return PreferenceRules.wanted(kind, store.choice(user, kind.category().name(), channel.name()));
    }

    /** Whether the person wants a mail of this category. */
    boolean wantsMail(UUID user, Category category) {
        if (PreferenceRules.locked(category)) {
            return true;
        }
        return PreferenceRules.wanted(category, store.choice(user, category.name(), Channel.MAIL.name()));
    }

    @Transactional(readOnly = true)
    NotificationPreferencesView view(UUID user) {
        Map<String, Boolean> explicit = store.choicesOf(user);
        List<PreferenceChoiceView> choices = new ArrayList<>();
        for (Category category : Category.values()) {
            for (Channel channel : Channel.values()) {
                Boolean stored = explicit.get(category.name() + "/" + channel.name());
                String[] words = WORDS.get(category);
                choices.add(new PreferenceChoiceView(category.name(), words[0], words[1],
                        channel.name(), channel.label(), PreferenceRules.wanted(category, stored),
                        PreferenceRules.locked(category)));
            }
        }
        return new NotificationPreferencesView(choices);
    }

    @Transactional
    NotificationPreferencesView update(UUID user, UpdateNotificationPreferencesRequest request) {
        int changed = 0;
        for (int i = 0; i < request.choices().size(); i++) {
            PreferenceChoiceRequest choice = request.choices().get(i);
            String place = "choices[" + i + "]";
            Category category = category(choice.category(), place);
            Channel channel = PreferenceRules.Channel.fromCode(choice.channel()).orElseThrow(
                    () -> ApiException.validation(place + ".channel", "Choose IN_APP or MAIL."));
            if (PreferenceRules.locked(category) && !choice.enabled()) {
                throw ApiException.validation(place + ".enabled", "This always reaches you; it cannot be switched "
                        + "off.");
            }
            store.set(user, category.name(), channel.name(), choice.enabled());
            changed++;
        }
        audit.record(AuditRecord.of("notification.preferences.changed", AuditOutcome.SUCCESS).forUser(user)
                .inTenant(contexts.require().tenantId()).with("switches", Integer.toString(changed)));
        return view(user);
    }

    private static Category category(String code, String place) {
        for (Category category : Category.values()) {
            if (category.name().equals(code)) {
                return category;
            }
        }
        throw ApiException.validation(place + ".category", "Choose APPROVALS, WORKFLOWS or ACCOUNT.");
    }
}
