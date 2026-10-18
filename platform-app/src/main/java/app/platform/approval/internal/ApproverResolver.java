package app.platform.approval.internal;

import app.platform.approval.internal.ApproverResolution.Delegation;
import app.platform.approval.internal.ApproverResolution.Person;
import app.platform.data.DataRecord;
import app.platform.metadata.ApprovalProcessDefinition;
import app.platform.metadata.ApprovalProcessDefinition.Approver;
import app.platform.security.ApproverDirectory;
import app.platform.security.GroupMembers;
import app.platform.sharedkernel.directory.ReferenceLabels;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Gathers the facts that {@link ApproverResolution} decides on (ADR-0146): who each rule of a step names, whether they
 * are
 * active members, whether they hold the licence their profile needs, and which delegations are in force. It reads
 * through
 * the contracts of the other modules (the approver directory and the groups of {@code security}, the names of users of
 * the
 * shared kernel) and never a table of another module.
 *
 * <p>The answer is a snapshot taken when the step becomes active (decision 3 of ADR-0146): a later change of a group, a
 * role, a profile or a field does not change a step already active.
 */
@Component
// final: the constructor refuses to build with no "User" reference-label contract; a class that cannot be
// subclassed closes the finalizer-attack path SpotBugs warns about for a throwing constructor (CT_CONSTRUCTOR_THROW).
final class ApproverResolver {

    private static final String USER_OBJECT = "User";

    /**
     * What a step is resolved for.
     *
     * @param record the record the request is about, as it is now (the owner and the user fields are read from it)
     * @param submitter the person who submitted, or null when the platform did
     * @param allowSelf whether the submitter may be asked too
     * @param fallback the fallback approver of the process, or null
     * @param processObject the object of the process (a delegation may be for one process)
     * @param processApiName the process
     */
    record Context(DataRecord record, UUID submitter, boolean allowSelf, Approver fallback, String processObject,
            String processApiName) {
    }

    private final ApproverDirectory directory;
    private final GroupMembers groups;
    private final ReferenceLabels users;
    private final ApprovalStore store;
    private final Clock clock;

    ApproverResolver(ApproverDirectory directory, GroupMembers groups, List<ReferenceLabels> labels,
            ApprovalStore store, Clock clock) {
        this.directory = directory;
        this.groups = groups;
        this.users = labels.stream().filter(found -> USER_OBJECT.equals(found.object())).findFirst().orElseThrow(
                () -> new IllegalStateException("The organization's users cannot be named"));
        this.store = store;
        this.clock = clock;
    }

    /** Who a step asks for the record. */
    ApproverResolution.Resolved resolve(ApprovalProcessDefinition.Step step, Context context) {
        List<List<UUID>> named = new ArrayList<>();
        for (Approver rule : step.approvers()) {
            named.add(named(rule, context.record()));
        }
        List<UUID> fallbackNamed = context.fallback() == null ? List.of() : named(context.fallback(),
                context.record());
        Set<UUID> everybody = new LinkedHashSet<>();
        named.forEach(everybody::addAll);
        everybody.addAll(fallbackNamed);
        List<Delegation> delegations = delegationsOf(everybody, context);
        Set<UUID> delegates = delegations.stream().map(Delegation::delegate)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<UUID> everyone = new LinkedHashSet<>(everybody);
        everyone.addAll(delegates);
        Map<UUID, Person> standing = standing(everyone);
        List<List<Person>> rules = named.stream().map(ids -> persons(ids, standing)).toList();
        return ApproverResolution.resolve(rules, persons(fallbackNamed, standing), context.submitter(),
                context.allowSelf(), delegations, standing::get);
    }

    /** The standing of one person: active member, licensed. Null when they are not a member at all. */
    Person standingOf(UUID userId) {
        return standing(List.of(userId)).get(userId);
    }

    /**
     * Active members whose name contains the text, who hold the licence their profile needs: the people who can be
     * asked
     * to decide. Bounded, and the text must match (the same rule as the lookup of users), so it cannot list a
     * directory.
     */
    List<ApproverDirectory.Candidate> eligible(String text, int limit, Map<UUID, String> namesInto) {
        List<ReferenceLabels.Option> found = users.options(text == null ? "" : text, limit);
        if (found.isEmpty()) {
            return List.of();
        }
        found.forEach(option -> namesInto.put(option.id(), option.label()));
        return directory.ofUsers(found.stream().map(ReferenceLabels.Option::id).toList()).stream()
                .filter(ApproverDirectory.Candidate::licensed).toList();
    }

    /** The profiles, roles and public groups that a rule can name. */
    ApproverDirectory directory() {
        return directory;
    }

    /** The names of these people who are active members (a person who is not has no name). */
    Map<UUID, String> names(Collection<UUID> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        return users.labels(userIds);
    }

    // ---- what each rule names ----

    private List<UUID> named(Approver rule, DataRecord record) {
        String value = rule.value();
        return switch (rule.kind()) {
            case USER -> uuid(value) == null ? List.of() : List.of(uuid(value));
            case PROFILE -> userIds(directory.holdersOfProfile(uuid(value)));
            case ROLE -> userIds(directory.holdersOfRole(uuid(value)));
            case GROUP -> {
                UUID group = uuid(value);
                yield group == null ? List.of() : userIds(directory.ofMemberships(groups.membersOf(group)));
            }
            case OWNER -> record == null || record.ownerId() == null ? List.of() : List.of(record.ownerId());
            case RECORD_USER -> {
                Object stored = record == null || value == null ? null : record.fields().get(value);
                UUID user = stored == null ? null : uuid(stored.toString());
                yield user == null ? List.of() : List.of(user);
            }
            case OWNER_ROLE_MANAGER -> record == null || record.ownerId() == null ? List.of()
                    : userIds(directory.parentRoleHolders(record.ownerId()));
        };
    }

    private static List<UUID> userIds(List<ApproverDirectory.Candidate> candidates) {
        return candidates.stream().map(ApproverDirectory.Candidate::userId).toList();
    }

    // ---- standing ----

    private Map<UUID, Person> standing(Collection<UUID> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, String> active = users.labels(userIds);
        Map<UUID, Boolean> licensed = new HashMap<>();
        directory.ofUsers(userIds).forEach(candidate -> licensed.put(candidate.userId(), candidate.licensed()));
        Map<UUID, Person> standing = new HashMap<>();
        for (UUID id : userIds) {
            standing.put(id, new Person(id, active.containsKey(id), licensed.getOrDefault(id, false)));
        }
        return standing;
    }

    private static List<Person> persons(List<UUID> ids, Map<UUID, Person> standing) {
        return ids.stream().map(id -> standing.getOrDefault(id, new Person(id, false, false))).toList();
    }

    // ---- delegations ----

    /** The delegations in force today, in their own zone, for these people and this process. */
    private List<Delegation> delegationsOf(Set<UUID> delegators, Context context) {
        if (delegators.isEmpty()) {
            return List.of();
        }
        LocalDate today = LocalDate.now(clock);
        List<Delegation> inForce = new ArrayList<>();
        for (ApprovalStore.DelegationRow row : store.delegationsOf(delegators, today.minusDays(1),
                today.plusDays(1))) {
            boolean forThis = row.processApiName() == null || (row.processObject().equals(context.processObject())
                    && row.processApiName().equals(context.processApiName()));
            if (forThis && inForce(row)) {
                inForce.add(new Delegation(row.delegatorId(), row.delegateId()));
            }
        }
        return inForce;
    }

    /** Whether the delegation is in force today: the first and the last day both count, in its own zone. */
    boolean inForce(ApprovalStore.DelegationRow row) {
        LocalDate today;
        try {
            today = LocalDate.now(clock.withZone(ZoneId.of(row.timeZone())));
        } catch (DateTimeException unknownZone) {
            return false;
        }
        return !today.isBefore(row.startsOn()) && !today.isAfter(row.endsOn());
    }

    private static UUID uuid(String text) {
        try {
            return text == null ? null : UUID.fromString(text);
        } catch (IllegalArgumentException notAnIdentifier) {
            return null;
        }
    }
}
