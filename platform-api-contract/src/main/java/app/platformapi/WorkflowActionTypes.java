package app.platformapi;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The closed list of what a workflow action can be (Sprint 22, ADR-0136), with the settings each type takes. This is
 * the schema of an action: the definition is checked against it when it is saved, and the workflow module refuses to
 * start when a type listed here has no executor, or an executor has no type here. Adding a type is one entry here and
 * one executor class there.
 */
public final class WorkflowActionTypes {

    /** Publishes a named platform event that names the record and carries no value. */
    public static final String PUBLISH_EVENT = "PUBLISH_EVENT";

    /** The start of the type of every event a workflow publishes. */
    public static final String SIGNAL_PREFIX = "workflow.signal.";

    /**
     * One setting of an action type.
     *
     * @param key the name of the setting
     * @param required whether the setting must be given
     * @param shape what a valid value looks like
     * @param help what the setting means, in words
     */
    public record Setting(String key, boolean required, Pattern shape, String help) {
    }

    /**
     * One type of action.
     *
     * @param type the code stored in the definition
     * @param summary what it does, in words
     * @param settings the settings it takes
     */
    public record Type(String type, String summary, List<Setting> settings) {

        /** Copies the list. */
        public Type {
            settings = List.copyOf(settings);
        }
    }

    private static final List<Type> TYPES = List.of(
            new Type(PUBLISH_EVENT, "Announces a named event that names the record (never a value of it), for other "
                    + "parts of the platform to react to.",
                    List.of(new Setting("event", true, Pattern.compile("^[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*){0,3}$"),
                            "The name of the event: lower case words joined by dots, for example "
                                    + "order_review.requested. It is published as " + SIGNAL_PREFIX + "<name>."))));

    private WorkflowActionTypes() {
    }

    /** Every type, in a stable order. */
    public static List<Type> all() {
        return TYPES;
    }

    /** The type with the code, or empty for one this release does not know. */
    public static Optional<Type> find(String code) {
        return TYPES.stream().filter(type -> type.type().equals(code)).findFirst();
    }
}
