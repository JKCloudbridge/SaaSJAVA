package spike.auth;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import spike.auth.AuthenticationAttempt.ExternalAssertionAttempt;
import spike.auth.AuthenticationOutcome.Authenticated;
import spike.auth.AuthenticationOutcome.Reason;
import spike.auth.AuthenticationOutcome.Rejected;

/**
 * Stand-in for a future external OIDC provider: proves a second, very different provider fits the same
 * abstraction without any change to the adapter. It maps a verified (issuer, subject) to a platform user.
 */
public class FederatedStubProvider implements PlatformAuthenticationProvider {

    private final Map<String, String> linkedAccounts = new ConcurrentHashMap<>();

    public void link(String issuer, String subject, String userId) {
        linkedAccounts.put(issuer + "|" + subject, userId);
    }

    @Override
    public String id() {
        return "federated-stub";
    }

    @Override
    public boolean supports(AuthenticationAttempt attempt) {
        return attempt instanceof ExternalAssertionAttempt;
    }

    @Override
    public AuthenticationOutcome authenticate(AuthenticationAttempt attempt) {
        ExternalAssertionAttempt in = (ExternalAssertionAttempt) attempt;
        String userId = linkedAccounts.get(in.issuer() + "|" + in.subject());
        return userId == null ? new Rejected(Reason.NO_LINKED_ACCOUNT) : new Authenticated(userId, 1);
    }
}
