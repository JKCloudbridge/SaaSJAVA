package app.platform.sharedkernel.notice;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** A domain resolves a notice's facts through the caller's ordinary read permissions. A notice grants no access. */
public interface NoticeDestination {

    /** The API name of the object this destination serves; of the first, when it serves several. */
    String object();

    /**
     * Whether the destination serves the object (FA13B, ADR-0226). A destination that serves the objects of several
     * applications of one kind answers for each of them; the default serves {@link #object()} alone.
     */
    default boolean serves(String objectApiName) {
        return object().equals(objectApiName);
    }

    Optional<String> readablePath(UUID recordId, Map<String, String> facts);

    /**
     * The path for a record of the named object. The default ignores the object and asks the one-object form, which is
     * right for a destination that serves a single object; one that serves several overrides this.
     */
    default Optional<String> readablePath(String objectApiName, UUID recordId, Map<String, String> facts) {
        return readablePath(recordId, facts);
    }
}
