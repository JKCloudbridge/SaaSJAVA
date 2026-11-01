package app.platform.integration.internal;

import java.time.Duration;

/**
 * The seam between the flows that exchange files and the protocol that carries them (Sprint 29A, ADR-0237). The sender,
 * the poller and the tester ask for a {@linkplain FileVisit visit}; the one implementation speaks the secure shell
 * protocol, and a test can put a fake behind the same interface.
 */
interface FileTransport {

    /**
     * Where to go and how to know it is the right place.
     *
     * @param endpoint the checked address
     * @param hostKey the pinned fingerprint of the server's key
     * @param timeout how long the whole visit may take, from the first lookup to the end
     */
    record Target(EndpointRules.Endpoint endpoint, String hostKey, Duration timeout) {
    }

    /**
     * Opens a visit: resolves and checks the address, opens the connection, checks the server's key against the pin,
     * signs in. The caller closes it, and wipes the login when it is done.
     *
     * @throws TransportFailure with a stable reason when any of that fails
     */
    FileVisit open(Target target, CredentialLogins.Login login);
}
