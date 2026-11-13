package app.platform.sharedkernel.solution;

import java.util.Map;
import java.util.Objects;

/**
 * The objects that play the parts of one application, as a shipped solution declares them (FA13B, ADR-0226). A solution
 * that makes several applications of one kind (the same functions over separate records) names, for each part, the
 * object whose records play it; the module that owns those functions asks which objects an application means instead of
 * naming them. The part names are the owner's words: this record and the module that loads solutions only carry them.
 *
 * @param application the permanent name of the application
 * @param feature the plan feature an organization needs for the application to exist for it
 * @param roles the object (API name) that plays each part, by the part's name
 * @param options settings of the application the solution file states, by name (MC1, ADR-0257); the module that owns
 *        the functions knows the names and checks them. Plain data: no code
 */
public record ObjectRoles(String application, String feature, Map<String, String> roles,
        Map<String, String> options) {

    /** An application without options. */
    public ObjectRoles(String application, String feature, Map<String, String> roles) {
        this(application, feature, roles, Map.of());
    }

    /** Checks and copies what is given. */
    public ObjectRoles {
        Objects.requireNonNull(application, "application");
        Objects.requireNonNull(feature, "feature");
        roles = Map.copyOf(roles);
        options = Map.copyOf(options);
    }

    /** The option, or the fallback. */
    public String option(String name, String fallback) {
        return options.getOrDefault(name, fallback);
    }

    /** The object that plays the part, or null. */
    public String object(String role) {
        return roles.get(role);
    }
}
