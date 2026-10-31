package app.platform.integration.adapter;

import java.util.Objects;

/**
 * The one request that sends an entity. The adapter says what to send and where; the framework makes the call, so an
 * adapter cannot open a connection of its own and the address rules, the timeouts and the size caps cannot be bypassed
 * (ADR-0207, ADR-0223).
 *
 * @param method the method
 * @param path the path below the connection's address, starting with a slash; the framework checks it again
 * @param contentType the media type of the body, or null when there is none
 * @param body the body, or an empty array
 */
public record OutboundRequest(Method method, String path, String contentType, byte[] body) {

    /** The methods a send may use. */
    public enum Method {
        POST,
        PUT,
        PATCH,
        DELETE
    }

    public OutboundRequest {
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(path, "path");
        body = body == null ? new byte[0] : body.clone();
    }

    /** The body, copied. */
    @Override
    public byte[] body() {
        return body.clone();
    }

    /** Prints no body: it is data of the organization. */
    @Override
    public String toString() {
        return "OutboundRequest[" + method + ", " + body.length + " bytes]";
    }
}
