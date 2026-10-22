package app.platform.solution.internal;

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
 * @param application the application the solution shows in the member's menu
 * @param objects the API names of the objects the solution brings, which the metadata module reads from the
 *        solution's {@code objects} folder
 * @param sheetOwners the objects, among {@code objects}, whose records may own a sheet (FA3, ADR-0186)
 * @param policies the access policies the solution ships
 */
record SolutionBundle(String key, String feature, String label, String removalNote, Application application,
        List<String> objects, List<String> sheetOwners, List<Policy> policies) {

    /** The prefix of the identity of a shipped policy: {@code <solution key>.<policy key>}. */
    static final String OWNER = "solution";

    SolutionBundle {
        objects = List.copyOf(objects);
        sheetOwners = List.copyOf(sheetOwners);
        policies = List.copyOf(policies);
    }

    /** A solution whose objects own no sheet. */
    SolutionBundle(String key, String feature, String label, String removalNote, Application application,
            List<String> objects, List<Policy> policies) {
        this(key, feature, label, removalNote, application, objects, List.of(), policies);
    }

    /**
     * The application.
     *
     * @param apiName its permanent name, which never ends in {@code __c} (an organization's own do)
     * @param label what people read
     * @param iconKey the icon, or empty
     * @param entries the navigation in order
     */
    record Application(String apiName, String label, String iconKey, List<Entry> entries) {

        Application {
            entries = List.copyOf(entries);
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
     */
    record Entry(ShippedEntryView.Kind kind, String label, String object, String pageKey, String readsObject) {
    }

    /**
     * One shipped access policy.
     *
     * @param key its key inside the solution
     * @param name the name people read
     * @param description what it is for
     * @param objects the rights it gives on objects
     */
    record Policy(String key, String name, String description, Map<String, Set<ObjectAction>> objects) {

        Policy {
            objects = Map.copyOf(objects);
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
