package app.platform.notification.internal;

import app.platform.identity.AccountTokenPurpose;
import app.platform.identity.AccountTokens;
import app.platform.identity.InvitationMail;
import app.platform.identity.Invitations;
import app.platform.identity.User;
import app.platform.identity.UserStatus;
import app.platform.identity.Users;
import app.platform.notification.internal.MailStore.ClaimedMail;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

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

    private final Users users;
    private final Invitations invitations;
    private final AccountTokens tokens;
    private final MailLinks links;

    MailComposer(Users users, Invitations invitations, AccountTokens tokens, MailLinks links) {
        this.users = users;
        this.invitations = invitations;
        this.tokens = tokens;
        this.links = links;
    }

    Decision decide(ClaimedMail mail) {
        return switch (mail.template()) {
            case SIGN_UP_REQUEST -> signUp(mail);
            case PASSWORD_RESET_REQUEST -> reset(mail);
            case INVITATION -> invitation(mail);
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
        return send(mail, invitation.existingAccount() ? MailTexts.Kind.INVITATION_EXISTING
                : MailTexts.Kind.INVITATION_NEW, values, "link_sent");
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
