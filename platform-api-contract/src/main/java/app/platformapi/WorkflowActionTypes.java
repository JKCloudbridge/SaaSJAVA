package app.platformapi;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The closed list of what a workflow action can be (Sprint 22, ADR-0136; the actions that change the world Sprint 23,
 * ADR-0138), with the settings each type takes. This is the schema of an action: the definition is checked against it
 * when it is saved, and the workflow module refuses to start when a type listed here has no executor, or an executor
 * has no type here. Adding a type is one entry here and one executor class there. The browser reads this list from
 * the API (the catalogue) and holds none of it.
 */
public final class WorkflowActionTypes {

    /** Publishes a named platform event that names the record and carries no value. */
    public static final String PUBLISH_EVENT = "PUBLISH_EVENT";

    /** Sets fields of the triggering record. */
    public static final String UPDATE_RECORD = "UPDATE_RECORD";

    /** Creates a record of an object, optionally linked to the triggering record. */
    public static final String CREATE_RECORD = "CREATE_RECORD";

    /** Queues a platform mail to the person the record names. */
    public static final String SEND_EMAIL = "SEND_EMAIL";

    /** Submits the triggering record for approval, as the platform; the workflow does not wait for the decision. */
    public static final String START_APPROVAL = "START_APPROVAL";

    /** The start of the type of every event a workflow publishes. */
    public static final String SIGNAL_PREFIX = "workflow.signal.";

    /** The one platform mail template an action may send in this release (ADR-0138). */
    public static final String TEMPLATE_RECORD_NOTICE = "RECORD_NOTICE";

    /** What an action does with its assignments. */
    public enum Assignments {
        /** The type takes none. */
        NONE,
        /** The fields to set are fields of the triggering record's object. */
        TRIGGERING_OBJECT,
        /** The fields to set are fields of the object named by the setting {@code object}. */
        NAMED_OBJECT
    }

    /** What a setting's text names, so a screen can offer the right choices without knowing the setting. */
    public enum Refers {
        /** Plain text, or one of a closed list. */
        NONE,
        /** The API name of an object. */
        OBJECT,
        /** The API name of a field of the object of the workflow. */
        FIELD,
        /** The API name of a field of the object whose records the action sets (see {@link Type#assignedObject}). */
        ASSIGNED_FIELD,
        /** The API name of an approval process of the object of the workflow (Sprint 24). */
        APPROVAL_PROCESS
    }

    /**
     * One setting of an action type.
     *
     * @param key the name of the setting
     * @param required whether the setting must be given
     * @param shape what a valid value looks like
     * @param help what the setting means, in words
     * @param allowed for a setting that takes one of a closed list: the list; empty otherwise
     * @param refers what the text names
     */
    public record Setting(String key, boolean required, Pattern shape, String help, List<String> allowed,
            Refers refers) {

        /** Copies the list. */
        public Setting {
            allowed = List.copyOf(allowed);
        }

        /** A setting with no closed list that names nothing. */
        public Setting(String key, boolean required, Pattern shape, String help) {
            this(key, required, shape, help, List.of(), Refers.NONE);
        }

        /** A setting with a closed list. */
        public Setting(String key, boolean required, Pattern shape, String help, List<String> allowed) {
            this(key, required, shape, help, allowed, Refers.NONE);
        }

        /** A setting that names an object or a field. */
        public Setting(String key, boolean required, Pattern shape, String help, Refers refers) {
            this(key, required, shape, help, List.of(), refers);
        }
    }

    /**
     * One type of action.
     *
     * @param type the code stored in the definition
     * @param summary what it does, in words
     * @param assignments what it does with assignments
     * @param assignedObject for {@link Assignments#NAMED_OBJECT}: the key of the setting that names the object whose
     *        fields are set; null otherwise
     * @param settings the settings it takes
     */
    public record Type(String type, String summary, Assignments assignments, String assignedObject,
            List<Setting> settings) {

        /** Copies the list. */
        public Type {
            settings = List.copyOf(settings);
        }

        /** A type whose assignments, if any, are not of a named object. */
        public Type(String type, String summary, Assignments assignments, List<Setting> settings) {
            this(type, summary, assignments, null, settings);
        }

        /** A type with no assignments (the shape of Sprint 22). */
        public Type(String type, String summary, List<Setting> settings) {
            this(type, summary, Assignments.NONE, null, settings);
        }
    }

    private static final Pattern NAME = Pattern.compile("^[A-Za-z][A-Za-z0-9_]{0,59}$");

    private static final List<Type> TYPES = List.of(
            new Type(UPDATE_RECORD, "Sets fields of the record that started the workflow, to a value written here or "
                    + "copied from another field of the same record. The change is made as the platform and goes "
                    + "through every rule a person's change does (required fields, unique fields, validation rules, "
                    + "formulas).", Assignments.TRIGGERING_OBJECT, List.of()),
            new Type(CREATE_RECORD, "Creates a record of an object, setting its fields to values written here or "
                    + "copied from the record that started the workflow. The new record belongs to the owner of that "
                    + "record. It goes through every rule a person's new record does.", Assignments.NAMED_OBJECT,
                    "object",
                    List.of(new Setting("object", true, NAME, "The API name of the object to create a record of.",
                                    Refers.OBJECT),
                            new Setting("linkField", false, NAME,
                                    "A lookup or master-detail field of that object that points to the object of "
                                            + "this workflow: it is set to the record that started the workflow.",
                                    Refers.ASSIGNED_FIELD))),
            new Type(SEND_EMAIL, "Queues a mail to the person the record names: a member (a user field) or an address "
                    + "(an e-mail field). The mail says that a record of the object needs attention and links to "
                    + "it. It never contains a value of the record.", Assignments.NONE,
                    List.of(new Setting("recipientField", true, NAME,
                                    "A user field or an e-mail field of the object of this workflow.", Refers.FIELD),
                            new Setting("template", true, Pattern.compile("^" + TEMPLATE_RECORD_NOTICE + "$"),
                                    "Which mail to send.", List.of(TEMPLATE_RECORD_NOTICE)))),
            new Type(START_APPROVAL, "Submits the record that started the workflow for approval under a process of the "
                    + "object. The platform is the submitter. The workflow does not wait for "
                    + "the decision: what follows "
                    + "the decision is another workflow that starts when the approval ends. A record that is already "
                    + "being approved is not submitted twice; the step simply completes.",
                    List.of(new Setting("process", true, NAME,
                            "The API name of the approval process of this object to submit the record under.",
                            Refers.APPROVAL_PROCESS))),
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
