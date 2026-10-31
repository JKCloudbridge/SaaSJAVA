package app.platform.integration.adapter;

import java.util.Map;

/**
 * The adapter seam: one implementation per kind of external system, a Spring bean in
 * {@code app.platform.integration.adapter..} (ADR-0207). Only the integration module may reference this package
 * (an architecture test), so core modules never name an external system, and adding a connector type touches no core
 * code: write the class, register nothing.
 *
 * <p>An adapter is declarative. It describes itself, says what request proves a connection works and says what answer
 * counts as success. It does not open sockets, read secrets, touch the database or log, so the address rules, the
 * timeouts, the size caps and the credential handling live in one place. Since Sprint 28 it also says how an entity
 * is written as a request and how a payload is read back into an entity (ADR-0223); the calls Sprint 29 needs (other
 * protocols) are added with that sprint, and nothing in the interface is there for something no code can carry out.
 *
 * <p>Implementations must be stateless and thread safe, and must not retain the settings they are handed.
 */
public interface Connector {

    /** The description of the connector type. The same value on every call. */
    ConnectorDescription describe();

    /**
     * The request that proves a connection with these settings works. The settings have been checked against
     * {@link #describe()} and hold a value for every setting, defaults included.
     */
    ProbeRequest probe(Map<String, String> settings);

    /** Whether the answer with this status code shows the connection works. */
    boolean accepts(Map<String, String> settings, int status);

    /**
     * The request that sends the entity (Sprint 28, ADR-0223). For {@link Operation#CREATE} the entity has no
     * identifier yet; for the other two it has one. The settings have been checked against {@link #describe()}.
     *
     * @throws PayloadException when the entity cannot be written for this kind of system
     */
    OutboundRequest send(Map<String, String> settings, Operation operation, CanonicalEntity entity);

    /** Whether the answer with this status code shows the entity was accepted. */
    boolean accepted(Map<String, String> settings, Operation operation, int status);

    /**
     * Reads a payload, a change that came in or the body of the answer to a send, into an entity of this kind. The
     * identifier comes out of the payload the way this kind of system writes it. Values are read as text, numbers or
     * yes and no only; the mapping gives them their types.
     *
     * @param kind the kind of entity the payload is about
     * @param body the bytes of the payload, bounded by the framework
     * @throws PayloadException when the payload is not one this kind of system sends
     */
    CanonicalEntity receive(Map<String, String> settings, String kind, byte[] body);
}
