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
 * timeouts, the size caps and the credential handling live in one place. The calls Sprint 28 and 29 need (sending
 * a record, receiving one, other protocols) are added to this interface with those sprints; nothing in it is there
 * for something no code can carry out yet.
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
}
