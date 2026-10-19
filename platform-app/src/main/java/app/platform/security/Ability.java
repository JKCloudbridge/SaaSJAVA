package app.platform.security;

import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Something a person may be allowed to do in an organization (ADR-0039). The catalogue is fixed by the code, so that
 * every ability has exactly one place that checks it; organizations only choose which abilities go into profiles,
 * access policies and individual grants.
 *
 * <p>Before objects exist the abilities are the administrative actions an organization's administrators had since
 * Sprint 5; Sprint 8 adds object and field permissions next to them and Milestone 4 adds record visibility, without
 * changing how abilities are stored or combined. The stored form is the {@linkplain #key() key}: a key that a later
 * release no longer knows is ignored when permissions are computed, never an error, so removing an ability from this
 * list cannot break an organization.
 *
 * <p>{@link #ACCESS_MANAGE} is special: whoever holds it can give every other ability to anybody, so the database
 * guarantees an organization always has an active member who holds it (ADR-0044).
 */
public enum Ability {

    /** See the members of the organization with their profile, role and licence. */
    MEMBERS_VIEW("members.view", "See members",
            "See the list of members with their profile, role, access policies and licence."),

    /** Create new members and manage their invitations. */
    MEMBERS_INVITE("members.invite", "Invite members",
            "Create a new member: choose their profile and role, send the link, send it again or withdraw it."),

    /** Deactivate members and let them back in. */
    MEMBERS_DEACTIVATE("members.deactivate", "Deactivate members",
            "Deactivate a member and let a deactivated member back in."),

    /** See the licence pools and give or take back licences. */
    LICENCES_MANAGE("licences.manage", "Manage licences",
            "See how many licences the organization has and give or take back a member's licence."),

    /** Sign the other members out. */
    SESSIONS_MANAGE("sessions.manage", "Sign people out",
            "Sign everyone else out of the organization."),

    /** Decide on the platform's requests to look into the organization. */
    SUPPORT_ACCESS_MANAGE("support-access.manage", "Manage support access",
            "Approve, deny or end the platform support team's request to look into the organization."),

    /** Manage profiles, access policies, roles and who holds them. */
    ACCESS_MANAGE("access.manage", "Manage access",
            "Create and change profiles, access policies and roles, give them to members and give abilities directly."),

    /** Read the audit trail of the organization. */
    AUDIT_VIEW("audit.view", "View the audit trail",
            "See who changed what in the organization and when: access changes, invitations, sign-ins and support "
                    + "access."),

    /** See the objects and fields of the organization (standard and custom). */
    METADATA_VIEW("metadata.view", "View objects and fields",
            "See the objects and fields of the organization with their types, settings and picklist values."),

    /** Create, change and remove the organization's own objects and fields. */
    METADATA_MANAGE("metadata.manage", "Manage objects and fields",
            "Create, change and remove the organization's own objects and fields, and add its own fields to "
                    + "standard objects. Needs the ability to view objects and fields as well."),

    /**
     * Put draft changes of objects, fields and record types live and roll the latest publication back. A change made
     * at once also needs it, because it is a publication of one change (ADR-0068).
     */
    METADATA_PUBLISH("metadata.publish", "Publish metadata changes",
            "Publish a change set, make a single change live at once and roll the latest publication back. "
                    + "Needs the ability to view objects and fields as well; a change made at once needs the "
                    + "ability to manage them too."),

    /**
     * Set how the records of each object are shared: the organization-wide defaults, the hierarchy switch and the
     * sharing rules (Sprint 17, ADR-0116). Sharing one record is decided by owning it or being allowed to change it.
     */
    SHARING_MANAGE("sharing.manage", "Manage sharing",
            "Set whether each object is private or public, whether managers read what their subordinates own, and "
                    + "create, change and remove sharing rules."),

    /**
     * Save a list view for everyone in the organization who may read the object, and change or remove the shared views
     * that other members made (Sprint 19, ADR-0130). Saving a personal view and changing one's own needs no ability.
     */
    VIEWS_SHARE("views.share", "Share list views",
            "Save a list view for everyone who may read the object, and change or remove the shared list views that "
                    + "other members made."),

    /** See the workflows of the organization and the history of their executions (Sprint 22, ADR-0136). */
    WORKFLOWS_VIEW("workflows.view", "View workflows",
            "See the workflows of the organization and the history of what they ran, with the reason an execution "
                    + "was skipped or failed."),

    /**
     * Create, change, switch on or off and remove workflows (Sprint 22). Making a workflow live also needs the ability
     * to publish metadata changes, as every definition does (ADR-0068).
     */
    WORKFLOWS_MANAGE("workflows.manage", "Manage workflows",
            "Create, change, switch on or off and remove workflows, try a condition and actions on sample values, and "
                    + "cancel an execution that has not started. Making a change live needs the ability to publish "
                    + "metadata changes as well."),

    /**
     * Run a dead-lettered execution again, one or many, and put back an event the platform gave up on (Sprint 23,
     * ADR-0141, ADR-0143). Separate from managing workflows: replaying makes the platform act again on records.
     */
    WORKFLOWS_REPLAY("workflows.replay", "Replay workflow executions",
            "Run an execution that ended as a dead letter again from the step that failed, one or many at once, and "
                    + "put back an event the platform gave up on so that workflows see it."),

    /** See the approval processes of the organization (Sprint 24, ADR-0152). */
    APPROVALS_VIEW("approvals.view", "View approval processes",
            "See the approval processes of the organization: their steps, who is asked and what an outcome sets."),

    /**
     * Create, change, switch on or off and remove approval processes, try them on a record, make delegations for other
     * people, assign a person to a step nobody can decide and apply an outcome again (Sprint 24, ADR-0152). Making a
     * definition live also needs the ability to publish metadata changes, as every definition does (ADR-0068).
     */
    APPROVALS_MANAGE("approvals.manage", "Manage approvals",
            "Create, change, switch on or off and remove approval processes, try one on a record, make a delegation "
                    + "for somebody else, assign a person to a step that nobody can decide and apply an outcome again. "
                    + "Making a change live needs the ability to publish metadata changes as well."),

    /** See every approval request and its history for the records the member can read (Sprint 24, ADR-0152). */
    APPROVALS_VIEW_ALL("approvals.view-all", "View every approval",
            "See every approval request, its steps and its history, for the records you can read. Without it you see "
                    + "the requests you submitted or were asked to decide."),

    /**
     * Change a record that is locked by a pending approval, recall or cancel a request that is not yours (Sprint 24,
     * ADR-0148). Every use is audited with the reason.
     */
    APPROVALS_OVERRIDE_LOCK("approvals.override-lock", "Override the lock of an approval",
            "Change a record while it is being approved, and recall or cancel an approval request that is not yours. "
                    + "Every use is recorded in the audit trail and in the history of the approval."),

    /**
     * See the health of automation: what waits, what failed, what is overdue (Sprint 25, ADR-0163). Counts only; the
     * lists behind them need the abilities of workflows and approvals.
     */
    AUTOMATION_VIEW("automation.view", "View the health of automation",
            "See how many workflow executions wait or failed, how many events and mails are stuck, and how many "
                    + "approvals are overdue or have nobody to decide them.");

    private final String key;
    private final String title;
    private final String description;

    Ability(String key, String title, String description) {
        this.key = key;
        this.title = title;
        this.description = description;
    }

    /** The stable key stored in the database and sent through the API, for example {@code members.invite}. */
    public String key() {
        return key;
    }

    /** The name for people. */
    public String title() {
        return title;
    }

    /** What the ability allows, in plain words. */
    public String description() {
        return description;
    }

    /** The ability with the key, or empty for a key this release does not know. */
    public static Optional<Ability> fromKey(String key) {
        if (key == null) {
            return Optional.empty();
        }
        String wanted = key.strip().toLowerCase(Locale.ROOT);
        return Arrays.stream(values()).filter(ability -> ability.key.equals(wanted)).findFirst();
    }

    /** Every ability the platform knows. */
    public static Set<Ability> all() {
        return EnumSet.allOf(Ability.class);
    }

    /** The abilities among the keys; keys this release does not know are left out. */
    public static Set<Ability> knownAmong(Collection<String> keys) {
        Set<Ability> result = EnumSet.noneOf(Ability.class);
        keys.forEach(key -> fromKey(key).ifPresent(result::add));
        return result;
    }

    /** The keys of the abilities, sorted, for storage and for the API. */
    public static java.util.List<String> keysOf(Collection<Ability> abilities) {
        Set<String> sorted = new TreeSet<>();
        abilities.forEach(ability -> sorted.add(ability.key));
        return java.util.List.copyOf(sorted);
    }
}
