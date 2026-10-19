package app.platform.notification.internal;

import app.platform.identity.AccountTokenPurpose;
import app.platform.identity.AccountTokens;
import app.platform.identity.InvitationMail;
import app.platform.identity.Invitations;
import app.platform.identity.User;
import app.platform.identity.UserStatus;
import app.platform.identity.Users;
import app.platform.metadata.NotificationTemplate;
import app.platform.metadata.NotificationTemplates;
import app.platform.notification.internal.MailStore.ClaimedMail;
import app.platform.sharedkernel.TenantId;
import app.platform.sharedkernel.notice.NoticeKind;
import app.platform.sharedkernel.notice.NoticeText;
import app.platform.tenant.Tenant;
import app.platform.tenant.TenantContext;
import app.platform.tenant.TenantContexts;
import app.platform.tenant.TenantStatus;
import app.platform.tenant.Tenants;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Decides, at send time, what a queued mail turns into (ADR-0023, ADR-0024): a message, or nothing. The decision is
 * made here, after the request that queued it has long been answered, so what the account state is makes no difference
 * to what the caller of the API saw or how long it took.
 *
 * <ul>
 *   <li>A sign-up request for an address without an account becomes a message with a one-time link (the token is
 *       created now, so the queue never held a secret); for an address with an active account, a notice saying so; for
 *       any other state, nothing.</li>
 *   <li>A reset request becomes a message with a link only when the account is active; otherwise nothing.</li>
 *   <li>An invitation becomes a message with a one-time link when it is still open, the address is not a member
 *       yet and its account (if any) is active: the words differ for a new account and for an existing one. Nothing
 *       otherwise (Sprint 5, ADR-0028).</li>
 *   <li>The notices (password changed, account locked) are sent as queued.</li>
 *   <li>The mails of an organization (a record that needs attention, a decision, a reminder) are sent unless the
 *       recipient switched that category off for mail (Sprint 25, ADR-0156), in the words the administrator wrote for
 *       the kind or, when none were written, the platform's own (ADR-0155). The choice and the text are read in the
 *       organization's own tenant context, now.</li>
 *   <li>The notices about the end of a trial go to the people who administer the organization and cannot be switched
 *       off (ADR-0162).</li>
 * </ul>
 */
@Component
class MailComposer {

    /** What to do with a queued mail. */
    sealed interface Decision {

        /** Send this message; {@code outcome} is the short code kept in the queue row. */
        record Send(OutgoingMessage message, String outcome) implements Decision {
        }

        /** Send nothing; {@code outcome} says why, for the queue row and the audit record. */
        record Suppress(String outcome) implements Decision {
        }
    }

    /** What the organization's own context says about one mail: whether the person wants it, and the words to use. */
    private record Policy(boolean wanted, Optional<NotificationTemplate> custom) {
    }

    private static final Pattern OBJECT_NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,59}");
    private static final Pattern DAYS = Pattern.compile("[0-9]{1,3}");

    private final Users users;
    private final Invitations invitations;
    private final AccountTokens tokens;
    private final MailLinks links;
    private final Tenants tenants;
    private final TenantContexts contexts;
    private final TransactionTemplate transaction;
    private final NotificationPreferences preferences;
    private final NotificationTemplates templates;

    MailComposer(Users users, Invitations invitations, AccountTokens tokens, MailLinks links, Tenants tenants,
            TenantContexts contexts, TransactionTemplate transaction, NotificationPreferences preferences,
            NotificationTemplates templates) {
        this.tenants = tenants;
        this.users = users;
        this.invitations = invitations;
        this.tokens = tokens;
        this.links = links;
        this.contexts = contexts;
        this.transaction = transaction;
        this.preferences = preferences;
        this.templates = templates;
    }

    Decision decide(ClaimedMail mail) {
        return switch (mail.template()) {
            case SIGN_UP_REQUEST -> signUp(mail);
            case PASSWORD_RESET_REQUEST -> reset(mail);
            case INVITATION -> invitation(mail);
            case RECORD_NOTICE -> recordNotice(mail);
            case APPROVAL_REQUEST -> approvalRequest(mail, NoticeKind.APPROVAL_TASK, MailTexts.Kind.APPROVAL_REQUEST,
                    "approval_request_sent");
            case APPROVAL_REMINDER -> approvalRequest(mail, NoticeKind.APPROVAL_REMINDER,
                    MailTexts.Kind.APPROVAL_REMINDER, "approval_reminder_sent");
            case TRIAL_NOTICE -> trialNotice(mail);
            case PASSWORD_CHANGED -> notice(mail, MailTexts.Kind.PASSWORD_CHANGED, "notice_sent");
            case ACCOUNT_LOCKED -> notice(mail, MailTexts.Kind.ACCOUNT_LOCKED, "notice_sent");
        };
    }

    private Decision signUp(ClaimedMail mail) {
        Optional<User> existing = users.findByEmail(mail.email());
        if (existing.isEmpty()) {
            String token = tokens.issue(AccountTokenPurpose.SIGN_UP, mail.email(), null);
            Map<String, String> values = Map.of("link", links.signUpComplete(token),
                    "lifetime", describe(tokens.lifetime(AccountTokenPurpose.SIGN_UP)));
            return send(mail, MailTexts.Kind.SIGN_UP_LINK, values, "link_sent");
        }
        if (existing.get().status() == UserStatus.ACTIVE) {
            return send(mail, MailTexts.Kind.ACCOUNT_EXISTS,
                    Map.of("signInLink", links.signIn(), "forgotLink", links.forgotPassword()), "account_exists");
        }
        return new Decision.Suppress("account_not_active");
    }

    private Decision reset(ClaimedMail mail) {
        Optional<User> existing = users.findByEmail(mail.email());
        if (existing.isEmpty()) {
            return new Decision.Suppress("no_account");
        }
        User user = existing.get();
        if (user.status() != UserStatus.ACTIVE) {
            return new Decision.Suppress("account_not_active");
        }
        String token = tokens.issue(AccountTokenPurpose.PASSWORD_RESET, mail.email(), user.id());
        Map<String, String> values = Map.of("link", links.passwordReset(token),
                "lifetime", describe(tokens.lifetime(AccountTokenPurpose.PASSWORD_RESET)));
        return send(mail, MailTexts.Kind.PASSWORD_RESET_LINK, values, "link_sent");
    }

    private Decision invitation(ClaimedMail mail) {
        UUID tenantId = uuid(mail.variables().get("organization_id"));
        UUID invitationId = uuid(mail.variables().get("invitation_id"));
        if (tenantId == null || invitationId == null) {
            return new Decision.Suppress("invitation_unknown");
        }
        Optional<InvitationMail> open = invitations.forMail(tenantId, invitationId);
        if (open.isEmpty() || !open.get().email().equals(mail.email())) {
            return new Decision.Suppress("invitation_not_open");
        }
        InvitationMail invitation = open.get();
        String token = tokens.issueInvitation(mail.email(), tenantId, invitationId);
        Map<String, String> values = Map.of("link", links.invitation(token),
                "lifetime", describe(tokens.lifetime(AccountTokenPurpose.INVITATION)),
                "organization", MailTexts.safeName(invitation.organizationName()));
        MailTexts.Kind kind;
        if (invitation.firstAdministrator()) {
            kind = invitation.existingAccount() ? MailTexts.Kind.FIRST_ADMINISTRATOR_EXISTING
                    : MailTexts.Kind.FIRST_ADMINISTRATOR_NEW;
        } else {
            kind = invitation.existingAccount() ? MailTexts.Kind.INVITATION_EXISTING : MailTexts.Kind.INVITATION_NEW;
        }
        return send(mail, kind, values, "link_sent");
    }

    /**
     * A workflow asked that a record needs this person's attention (ADR-0138). The request names the organization, the
     * object and the record; the link is built now, to the organization's own host. Nothing of the record is in the
     * queue or in the message. Nothing is sent when the organization is not open any more or when the person switched
     * workflow mails off (ADR-0156).
     */
    private Decision recordNotice(ClaimedMail mail) {
        UUID tenantId = uuid(mail.variables().get("organization_id"));
        UUID recordId = uuid(mail.variables().get("record_id"));
        String object = mail.variables().get("object");
        if (tenantId == null || recordId == null || object == null || !OBJECT_NAME.matcher(object).matches()) {
            return new Decision.Suppress("notice_unknown");
        }
        return organizationMail(mail, tenantId, NoticeKind.WORKFLOW_NOTICE, MailTexts.Kind.RECORD_NOTICE,
                "notice_sent", tenant -> links.record(tenant.slug().value(), object, recordId));
    }

    /**
     * A record waits for the recipient's decision, or has waited too long (Sprint 24, ADR-0152; Sprint 25, ADR-0159):
     * the organization, the object and the label of the process, all passed through {@code safeName}, and the link to
     * the inbox. No title and no value of the record.
     */
    private Decision approvalRequest(ClaimedMail mail, NoticeKind kind, MailTexts.Kind words, String outcome) {
        UUID tenantId = uuid(mail.variables().get("organization_id"));
        if (tenantId == null) {
            return new Decision.Suppress("notice_unknown");
        }
        return organizationMail(mail, tenantId, kind, words, outcome, tenant -> links.approvals(tenant.slug().value()));
    }

    /**
     * The shared part of the mails of an organization: the organization must be open, the recipient must want this
     * category by mail, and the words are the administrator's when they wrote some. The two reads happen in the
     * organization's own tenant context and a transaction of their own.
     */
    private Decision organizationMail(ClaimedMail mail, UUID tenantId, NoticeKind kind, MailTexts.Kind words,
            String outcome, Function<Tenant, String> link) {
        Tenant tenant = tenants.findById(new TenantId(tenantId)).orElse(null);
        if (tenant == null || tenant.status() != TenantStatus.ACTIVE) {
            return new Decision.Suppress("organization_not_open");
        }
        Policy policy = contexts.call(TenantContext.of(tenant.id()), () -> transaction.execute(status -> new Policy(
                mail.userId() == null || preferences.wantsMail(mail.userId(), kind.category()),
                templates.find(kind))));
        if (policy == null || !policy.wanted()) {
            return new Decision.Suppress("preference_off");
        }
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("organization", tenant.displayName());
        labels.put("object", mail.variables().getOrDefault("object_label", ""));
        labels.put("process", mail.variables().getOrDefault("process_label", ""));
        labels.put("step", mail.variables().getOrDefault("step_label", ""));
        labels.put("workflow", mail.variables().getOrDefault("workflow_label", ""));
        String address = link.apply(tenant);
        if (policy.custom().isPresent()) {
            String body = NoticeText.render(policy.custom().get().body(), labels);
            OutgoingMessage message = new OutgoingMessage(mail.email(), words.subject(),
                    MailTexts.customText(body, address), MailTexts.customHtml(body, address));
            return new Decision.Send(message, outcome);
        }
        Map<String, String> values = Map.of("organization", MailTexts.safeName(tenant.displayName()),
                "objectLabel", MailTexts.safeName(labels.get("object")),
                "process", MailTexts.safeName(labels.get("process")), "link", address);
        return send(mail, words, values, outcome);
    }

    /**
     * The trial of an organization is ending or over (Sprint 25, ADR-0162). The request names the organization and the
     * stage; the organization may be suspended already, so only its existence is checked. It goes to administrators
     * and is not subject to a preference.
     */
    private Decision trialNotice(ClaimedMail mail) {
        UUID tenantId = uuid(mail.variables().get("organization_id"));
        String stage = mail.variables().get("stage");
        if (tenantId == null || stage == null) {
            return new Decision.Suppress("notice_unknown");
        }
        Tenant tenant = tenants.findById(new TenantId(tenantId)).orElse(null);
        if (tenant == null || tenant.status() == TenantStatus.DEACTIVATED) {
            return new Decision.Suppress("organization_not_open");
        }
        String days = mail.variables().getOrDefault("days", "");
        if ("suspended".equals(stage)) {
            return send(mail, MailTexts.Kind.TRIAL_SUSPENDED,
                    Map.of("organization", MailTexts.safeName(tenant.displayName())), "trial_notice_sent");
        }
        String left = DAYS.matcher(days).matches() ? days + (days.equals("1") ? " day" : " days") : "a few days";
        return send(mail, MailTexts.Kind.TRIAL_ENDING, Map.of("organization",
                MailTexts.safeName(tenant.displayName()), "days", left), "trial_notice_sent");
    }

    private static UUID uuid(String text) {
        try {
            return text == null ? null : UUID.fromString(text);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private Decision notice(ClaimedMail mail, MailTexts.Kind kind, String outcome) {
        return send(mail, kind, Map.of("forgotLink", links.forgotPassword()), outcome);
    }

    private static Decision send(ClaimedMail mail, MailTexts.Kind kind, Map<String, String> values, String outcome) {
        OutgoingMessage message = new OutgoingMessage(mail.email(), kind.subject(), MailTexts.text(kind, values),
                MailTexts.html(kind, values));
        return new Decision.Send(message, outcome);
    }

    /** "7 days", "24 hours" or "60 minutes": the form a person reads. */
    static String describe(Duration life) {
        long minutes = life.toMinutes();
        if (minutes >= 2 * 1440 && minutes % 1440 == 0) {
            return minutes / 1440 + " days";
        }
        if (minutes >= 60 && minutes % 60 == 0) {
            long hours = minutes / 60;
            return hours + (hours == 1 ? " hour" : " hours");
        }
        return minutes + (minutes == 1 ? " minute" : " minutes");
    }
}
