package app.platform.security.internal;

import app.platform.security.Admission;
import app.platform.security.RecordAccess;
import app.platform.security.ShareLevel;
import app.platformapi.QueryFilterNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * The rules of record-level security as one pure function (Sprint 17, ADR-0116, ADR-0118): what a member may read,
 * edit and delete, from facts the caller has already read. No database, no cache, no clock: a unit test and a property
 * test run it with generated facts, and the integration tests hold the statements of the data module to the same
 * meaning.
 *
 * <p>The rules, in the order they apply:
 * <ol>
 *   <li>A member who may not read the object reaches no record. A member with "modify all" reaches every record in
 *       every way.</li>
 *   <li><strong>Read.</strong> Every record when the member has "view all" or the object's default is not Private.
 *       Otherwise the records the member owns, the records owned by the people below them in the hierarchy (when the
 *       object grants access using the hierarchy), the records that a rule gives to the member (any level), and the
 *       records shared with the member or with a group they are in.</li>
 *   <li><strong>Edit.</strong> Every record when the default is Public Read/Write. Otherwise the member's own records,
 *       the records a rule gives at the read-and-write level and the records shared at that level. The hierarchy gives
 *       no edit right.</li>
 *   <li><strong>Delete.</strong> Every record when the default is Public Read/Write, otherwise only the member's own.
 *       A rule or a share never gives the right to delete.</li>
 * </ol>
 * The three results are contained in each other by construction (delete in edit in read).
 */
final class RecordAccessResolver {

    private RecordAccessResolver() {
    }

    /** One active sharing rule that reaches the member, with the people or the condition it names. */
    record Rule(UUID id, ShareLevel level, Set<UUID> owners, QueryFilterNode criterion) {

        /** Copies the set. */
        Rule {
            owners = owners == null ? Set.of() : Set.copyOf(owners);
        }

        /** A rule that gives the records of these owners. */
        static Rule ofOwners(UUID id, ShareLevel level, Set<UUID> owners) {
            return new Rule(id, level, owners, null);
        }

        /** A rule that gives the records that meet the condition. */
        static Rule ofCriterion(UUID id, ShareLevel level, QueryFilterNode criterion) {
            return new Rule(id, level, Set.of(), criterion);
        }
    }

    /**
     * What the resolution needs to know.
     *
     * @param object the object key
     * @param canRead whether the member may read the object at all
     * @param viewAll whether the member holds "view all" on the object
     * @param modifyAll whether the member holds "modify all" on the object
     * @param defaultAccess the organization-wide default of the object
     * @param hierarchy whether the object grants access using the hierarchy
     * @param user the member's user (the owner of their records)
     * @param grantees the user and every public group the member is in: whom a share is addressed to
     * @param subordinates the users below the member in the role tree
     * @param rules the active rules that reach the member
     * @param maxOwners the bound on one set of owners
     */
    record Facts(String object, boolean canRead, boolean viewAll, boolean modifyAll, SharingDefault defaultAccess,
            boolean hierarchy, UUID user, Set<UUID> grantees, Set<UUID> subordinates, List<Rule> rules,
            int maxOwners) {

        /** Copies what is given. */
        Facts {
            grantees = Set.copyOf(grantees);
            subordinates = Set.copyOf(subordinates);
            rules = List.copyOf(rules);
        }
    }

    /** Resolves the three admissions of the facts. */
    static RecordAccess resolve(Facts facts) {
        if (!facts.canRead()) {
            return RecordAccess.nothing(facts.object());
        }
        if (facts.modifyAll()) {
            return RecordAccess.everything(facts.object());
        }
        boolean readsAll = facts.viewAll() || facts.defaultAccess() != SharingDefault.PRIVATE;
        boolean writesAll = facts.defaultAccess() == SharingDefault.PUBLIC_READ_WRITE;
        boolean truncated = false;

        Admission read = Admission.everything();
        if (!readsAll) {
            Bounded owners = bounded(readOwners(facts), facts.user(), facts.maxOwners());
            truncated |= owners.cut();
            read = Admission.anyOf(parts(owners.owners(), facts, ShareLevel.READ, ShareLevel.READ));
        }
        Admission edit = Admission.everything();
        if (!writesAll) {
            Bounded owners = bounded(editOwners(facts), facts.user(), facts.maxOwners());
            truncated |= owners.cut();
            edit = Admission.anyOf(parts(owners.owners(), facts, ShareLevel.READ_WRITE, ShareLevel.READ_WRITE));
        }
        Admission delete = writesAll ? Admission.everything()
                : new Admission.OwnedBy(Set.of(facts.user()));
        return new RecordAccess(facts.object(), read, edit, delete, truncated);
    }

    /** The user, the people below them when the object says so, and the owners behind every rule that reaches them. */
    private static Set<UUID> readOwners(Facts facts) {
        Set<UUID> owners = new TreeSet<>();
        owners.add(facts.user());
        if (facts.hierarchy()) {
            owners.addAll(facts.subordinates());
        }
        facts.rules().forEach(rule -> owners.addAll(rule.owners()));
        return owners;
    }

    /** The user and the owners behind the rules that give the read-and-write level. */
    private static Set<UUID> editOwners(Facts facts) {
        Set<UUID> owners = new TreeSet<>();
        owners.add(facts.user());
        facts.rules().stream().filter(rule -> rule.level().covers(ShareLevel.READ_WRITE))
                .forEach(rule -> owners.addAll(rule.owners()));
        return owners;
    }

    /** The parts of one admission: the owners, the shares, the conditions of the rules of at least the level. */
    private static List<Admission> parts(Set<UUID> owners, Facts facts, ShareLevel ruleLevel, ShareLevel shareLevel) {
        List<Admission> parts = new ArrayList<>();
        if (!owners.isEmpty()) {
            parts.add(new Admission.OwnedBy(owners));
        }
        parts.add(new Admission.SharedWith(facts.grantees(), shareLevel));
        for (Rule rule : facts.rules()) {
            if (rule.criterion() != null && rule.level().covers(ruleLevel)) {
                parts.add(new Admission.Meets(rule.id(), rule.criterion()));
            }
        }
        return parts;
    }

    /** A set of owners after the bound, and whether it was cut. */
    record Bounded(Set<UUID> owners, boolean cut) {
    }

    /**
     * Keeps at most {@code max} owners: the member's own identifier always, then the others in identifier order. A cut
     * set admits fewer records than the rules say and never more (ADR-0115, point 3).
     */
    static Bounded bounded(Set<UUID> owners, UUID own, int max) {
        if (owners.size() <= max) {
            return new Bounded(owners, false);
        }
        Set<UUID> kept = new TreeSet<>();
        kept.add(own);
        for (UUID owner : new TreeSet<>(owners)) {
            if (kept.size() >= max) {
                break;
            }
            kept.add(owner);
        }
        return new Bounded(kept, true);
    }
}
