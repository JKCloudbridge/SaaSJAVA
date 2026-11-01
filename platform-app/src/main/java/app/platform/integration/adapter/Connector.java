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

    /**
     * The name of the header that carries the key which makes a send safe to repeat (Sprint 29, ADR-0228), or null when
     * this kind of system has no such thing. The framework fills it with a key that is the same for every attempt of
     * one send and different for every other send, so a service that honours it makes an entity once however many
     * times an answer was lost.
     */
    default String idempotencyHeader(Map<String, String> settings) {
        return null;
    }

    /**
     * Whether the answer to a send says "accepted, ask later", and if so the path to ask about it (Sprint 29,
     * ADR-0233). Empty for a system that answers at once, which is the default.
     *
     * @param status the status code of the answer
     * @param body the bytes of the answer, bounded by the framework
     */
    default java.util.Optional<String> remoteJob(Map<String, String> settings, Operation operation, int status,
            byte[] body) {
        return java.util.Optional.empty();
    }

    /** The question that asks how a job stands: a read of the path {@link #remoteJob} gave. */
    default ProbeRequest jobRequest(Map<String, String> settings, String jobPath) {
        throw new UnsupportedOperationException("This kind of system has no long-running operations");
    }

    /** What the answer to the question about a job says. */
    default JobState jobState(Map<String, String> settings, int status, byte[] body) {
        return JobState.FAILED;
    }

    /**
     * How this kind of system is reached (Sprint 29A, ADR-0237). The web is the default; the framework carries out the
     * others and asks the adapter only to describe and to read or write the content.
     */
    default Transport transport() {
        return Transport.HTTP;
    }

    /**
     * What is wrong with the settings as a whole, once each one has passed its own check: a fixed sentence by the key
     * of the setting it belongs to. Empty when all is well. Never repeats a value.
     */
    default Map<String, String> settingProblems(Map<String, String> settings) {
        return Map.of();
    }
}
