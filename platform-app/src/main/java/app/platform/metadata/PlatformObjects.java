package app.platform.metadata;

import java.util.List;
import java.util.Optional;

/**
 * The objects the platform itself defines, the same for every organization and read once at start (ADR-0059,
 * ADR-0181): the standard objects and the objects a shipped solution brings. Unlike {@link Metadata} it knows no
 * organization, answers with no tenant context and never looks at a plan feature, so it can be asked while the
 * application starts (the module that loads the shipped solutions checks the names a bundle uses against it, and the
 * record store lists the objects whose records are kept).
 *
 * <p>Nothing here can be changed while the application runs.
 */
public interface PlatformObjects {

    /** Every object the platform defines, by API name. */
    List<ObjectDefinition> all();

    /** The platform object with the API name (matched exactly). */
    Optional<ObjectDefinition> named(String apiName);
}
