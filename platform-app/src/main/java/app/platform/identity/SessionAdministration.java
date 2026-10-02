package app.platform.identity;

import app.platform.sharedkernel.TenantId;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Administrative handling of sessions (ADR-0036): list what a person holds, sign a person out everywhere, sign out
 * everyone of one organization. The caller decides who may ask (platform roles, or an organization's administrators for
 * their own organization); every use is audited. Nothing here shows a token, a hash or a secret.
 */
public interface SessionAdministration {

    /**
     * One live sign-in of a person.
     *
     * @param kind {@code SIGN_IN} (the short step that starts a sign-in) or {@code TOKENS} (a signed-in browser or
     *        program)
     * @param organization the short name of the organization host it works on, or null for the platform host
     * @param started when it began
     * @param expires when it ends at the latest
     */
    record SessionView(String kind, String organization, Instant started, Instant expires) {
    }

    /**
     * What the person with this address holds now. An unknown address and a person with nothing give the same empty
     * answer. The look is audited.
     */
    List<SessionView> sessionsOf(String email, UUID actor);

    /** Ends everything the person with this address holds, on every host. Silent for an unknown address. */
    void signOutEverywhere(String email, UUID actor);

    /**
     * Ends everything everybody holds on one organization's host.
     *
     * @param keepUser a person to leave signed in (the administrator who asked), or null
     * @return how many sessions and grants were alive
     */
    int signOutOrganization(TenantId organization, UUID actor, UUID keepUser);
}
