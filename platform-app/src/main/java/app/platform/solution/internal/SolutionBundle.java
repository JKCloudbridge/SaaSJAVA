package app.platform.solution.internal;

import app.platform.security.FieldAction;
import app.platform.security.ObjectAction;
import app.platform.sharedkernel.solution.ShippedEntryView;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One shipped solution as its file declares it, already checked for shape (ADR-0181). Immutable. The labels and the
 * note are text the platform chose, but they are still never written to a log: the printed form is the key only.
 *
 * @param key the solution's key and the name of its folder
 * @param feature the plan feature an organization needs for the solution to exist for it
 * @param label what people read
 * @param removalNote what the platform administrator reads before the feature is taken away
 * @param applications the applications the solution shows in the member's menu, at least one (FA9, ADR-0203: a
 *        bundle may list several; the first is the solution's main one)
 * @param objects the API names of the objects the solution brings, which the metadata module reads from the
 *        solution's {@code objects} folder
 * @param sheetOwners the objects, among {@code objects}, whose records may own a sheet (FA3, ADR-0186)
 * @param policies the access policies the solution ships
 */
record SolutionBundle(String key, String feature, String label, String removalNote, List<Application> applications,
        List<String> objects, List<String> sheetOwners, List<Policy> policies) {

    /** The prefix of the identity of a shipped policy: {@code <solution key>.<policy key>}. */
    static final String OWNER = "solution";

    SolutionBundle {
        applications = List.copyOf(applications);
        objects = List.copyOf(objects);
        sheetOwners = List.copyOf(sheetOwners);
        policies = List.copyOf(policies);
    }

    /** A solution with one application. */
    SolutionBundle(String key, String feature, String label, String removalNote, Application application,
            List<String> objects, List<String> sheetOwners, List<Policy> policies) {
        this(key, feature, label, removalNote, List.of(application), objects, sheetOwners, policies);
    }

    /** A solution with one application whose objects own no sheet. */
    SolutionBundle(String key, String feature, String label, String removalNote, Application application,
            List<String> objects, List<Policy> policies) {
        this(key, feature, label, removalNote, List.of(application), objects, List.of(), policies);
    }

    /** The solution's main application: the first one. */
    Application application() {
        return applications.get(0);
    }

    /**
     * The application.
     *
     * @param apiName its permanent name, which never ends in {@code __c} (an organization's own do)
     * @param label what people read
     * @param iconKey the icon, or empty
     * @param group the plain word that gathers it with others in the grid, or empty
     * @param entries the navigation in order
     */
    record Application(String apiName, String label, String iconKey, String group, List<Entry> entries) {

        Application {
            entries = List.copyOf(entries);
        }

        /** An application that belongs to no group. */
        Application(String apiName, String label, String iconKey, List<Entry> entries) {
            this(apiName, label, iconKey, "", entries);
        }
    }

    /**
     * One entry of the application's navigation.
     *
     * @param kind what it opens
     * @param label what people read; empty for an object entry that takes the object's plural label
     * @param object for an object entry, the object
     * @param pageKey for a platform entry, the key of the page the web application registered
     * @param readsObject the object whose read right shows the entry, or empty
     * @param placeholder true for a function that is only announced: it has an identity and a name and no page yet
     */
    record Entry(ShippedEntryView.Kind kind, String label, String object, String pageKey, String readsObject,
            boolean placeholder) {

        /** An entry that is not a placeholder. */
        Entry(ShippedEntryView.Kind kind, String label, String object, String pageKey, String readsObject) {
            this(kind, label, object, pageKey, readsObject, false);
        }
    }

    /**
     * One shipped access policy.
     *
     * @param key its key inside the solution
     * @param name the name people read
     * @param description what it is for
     * @param objects the rights it gives on objects
     * @param abilities the keys of the abilities it gives (FA4, ADR-0189)
     * @param fields the rights it gives on fields, by field key: the object, a dot, the field (FA4, ADR-0189)
     */
    record Policy(String key, String name, String description, Map<String, Set<ObjectAction>> objects,
            Set<String> abilities, Map<String, Set<FieldAction>> fields) {

        Policy {
            objects = Map.copyOf(objects);
            abilities = Set.copyOf(abilities);
            fields = Map.copyOf(fields);
        }

        /** A policy that gives rights on objects and abilities and none on fields. */
        Policy(String key, String name, String description, Map<String, Set<ObjectAction>> objects,
                Set<String> abilities) {
            this(key, name, description, objects, abilities, Map.of());
        }

        /** A policy that gives rights on objects and no ability and none on fields. */
        Policy(String key, String name, String description, Map<String, Set<ObjectAction>> objects) {
            this(key, name, description, objects, Set.of(), Map.of());
        }

        /** The identity the database keeps for the policy. */
        String managedKey(String solutionKey) {
            return solutionKey + "." + key;
        }
    }

    /** Prints the key only. */
    @Override
    public String toString() {
        return "SolutionBundle[" + key + "]";
    }
}
