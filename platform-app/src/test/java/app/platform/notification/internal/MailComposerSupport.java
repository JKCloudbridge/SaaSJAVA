package app.platform.notification.internal;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import app.platform.identity.AccountTokens;
import app.platform.identity.Invitations;
import app.platform.identity.Users;
import app.platform.metadata.NotificationTemplates;
import app.platform.tenant.TenantContexts;
import app.platform.tenant.Tenants;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Builds a {@link MailComposer} for the unit tests that need no database: the organization's own context is a real
 * {@link TenantContexts}, the "transaction" just runs the work, every person wants every mail, and no administrator has
 * written a text, so the composer behaves as it did before Sprint 25 unless a test says otherwise.
 */
final class MailComposerSupport {

    private MailComposerSupport() {
    }

    static MailComposer composer(Users users, Invitations invitations, AccountTokens tokens, MailLinks links,
            Tenants tenants) {
        return composer(users, invitations, tokens, links, tenants, mock(NotificationTemplates.class));
    }

    static MailComposer composer(Users users, Invitations invitations, AccountTokens tokens, MailLinks links,
            Tenants tenants, NotificationTemplates templates) {
        NotificationPreferences preferences = mock(NotificationPreferences.class);
        when(preferences.wantsMail(any(), any())).thenReturn(true);
        return composer(users, invitations, tokens, links, tenants, templates, preferences);
    }

    static MailComposer composer(Users users, Invitations invitations, AccountTokens tokens, MailLinks links,
            Tenants tenants, NotificationTemplates templates, NotificationPreferences preferences) {
        return new MailComposer(users, invitations, tokens, links, tenants, new TenantContexts(), RUNS_THE_WORK,
                preferences, templates);
    }

    /** A transaction template that runs the work and nothing else. */
    private static final TransactionTemplate RUNS_THE_WORK = new TransactionTemplate() {
        private static final long serialVersionUID = 1L;

        @Override
        public <T> T execute(TransactionCallback<T> action) {
            return action.doInTransaction(null);
        }
    };
}
