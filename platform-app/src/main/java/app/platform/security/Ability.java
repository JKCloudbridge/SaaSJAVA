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
                    + "access.");

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
