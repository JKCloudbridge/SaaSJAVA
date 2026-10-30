package app.platform.integration.adapter;

import java.time.Duration;
import java.util.Objects;

/**
 * The one request that shows an external system is reachable and accepts the credential. The adapter says what to
 * ask; the framework makes the call, so no adapter can open a connection of its own and the address rules, the
 * timeouts and the size caps cannot be bypassed (ADR-0207, ADR-0210).
 *
 * @param method {@code GET} or {@code HEAD}
 * @param path the path to ask, relative to the connection's address; the framework checks it again
 * @param timeout how long the adapter would like to wait; the platform's own ceiling applies
 */
public record ProbeRequest(Method method, String path, Duration timeout) {

    /** The methods a probe may use. Both are safe: a test never changes anything on the other side. */
    public enum Method {
        GET,
        HEAD
    }

    public ProbeRequest {
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(timeout, "timeout");
    }
}
