package app.platform.integration;

import java.util.List;
import java.util.UUID;

/**
 * What the rest of the platform may ask about the connections of the current organization (Sprint 26, ADR-0209). Two
 * questions and nothing else: which switched-on connections listen for an event, and what a switched-on connection may
 * touch. Both read the state afresh, so switching a connection off stops its use at the next question, and neither
 * names an external system.
 *
 * <p>A connection acts under its own identity, the {@linkplain #subscribersOf identifier} of the connection, never
 * under the rights of the person who set it up. The scope it was given is the whole of its rights: an entry for an
 * object and the things it may do with it. The sprints that send and receive records use these answers before they
 * call the record store, which still applies its own gate; the scope can only narrow, never widen.
 *
 * <p>Calls need the organization's tenant context, as every call that reads tenant data does.
 */
public interface Integrations {

    /** The kinds of thing a connection may be allowed to do with the records of an object. */
    enum ScopeAction {
        /** Read records. */
        READ,
        /** Create records. */
        CREATE,
        /** Change records. */
        UPDATE
    }

    /**
     * The switched-on connections that listen for the event about the object, in no particular order.
     *
     * @param event a platform event such as {@code record.created}
     * @param object the API name of the object the event is about
     */
    List<UUID> subscribersOf(String event, String object);

    /**
     * Whether the connection is switched on and its scope allows the action on the object. Anything unknown, switched
     * off or failing answers {@code false}.
     *
     * @param connection the identifier of the connection
     * @param object the API name of the object
     * @param action what it wants to do
     */
    boolean allows(UUID connection, String object, ScopeAction action);
}
