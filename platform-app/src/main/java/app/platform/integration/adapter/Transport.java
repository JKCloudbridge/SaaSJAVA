package app.platform.integration.adapter;

/**
 * How a connector type reaches its external system (Sprint 29A, ADR-0237). The framework owns every transport, so an
 * adapter only says which one it needs: it still opens no socket, reads no secret and touches no database.
 *
 * <p>Every transport answers the way the web client does (a status, or a stable reason there is none), which is why
 * classification, retries, the breaker, the limits, the log and the links apply to all of them unchanged.
 */
public enum Transport {

    /** A web service over HTTPS: the address is an {@code https://} one. */
    HTTP("https"),

    /** File transfer over SSH: the address is an {@code sftp://} one and the server's key is pinned. */
    SFTP("sftp");

    private final String scheme;

    Transport(String scheme) {
        this.scheme = scheme;
    }

    /** The scheme an address of this transport must start with. */
    public String scheme() {
        return scheme;
    }
}
