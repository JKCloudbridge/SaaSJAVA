package app.platform.sharedkernel.notice;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** A domain resolves a notice's facts through the caller's ordinary read permissions. A notice grants no access. */
public interface NoticeDestination {
    String object();

    Optional<String> readablePath(UUID recordId, Map<String, String> facts);
}
