package app.platform.solution.internal;

import app.platform.metadata.ObjectDefinition;
import app.platform.metadata.PlatformObjects;
import app.platform.security.ObjectAction;
import app.platform.sharedkernel.solution.ShippedEntryView;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Checks the bundles against the rest of the platform (ADR-0181), the part of the checking {@link SolutionLoader}
 * cannot do from one file: the solution's feature exists, every object it names is a platform object that needs
 * exactly that feature, every shipped object belongs to one solution, no key or name is used by two solutions, and an
 * entry or a right names an object the solution brings. Messages name the solution and the part, never a text.
 *
 * <p>Pure: it takes the facts as arguments, so a test can hand it any combination.
 */
final class SolutionValidator {

    private SolutionValidator() {
    }

    /**
     * Throws {@link IllegalStateException} naming the first mistake.
     *
     * @param bundles the bundles read from the files
     * @param platform the objects the platform defines
     * @param features the plan feature keys that exist; null to leave the feature check to a later step (the
     *        database is not reachable yet)
     */
    static void validate(List<SolutionBundle> bundles, PlatformObjects platform, Set<String> features) {
        Set<String> applicationNames = new HashSet<>();
        Set<String> featuresUsed = new HashSet<>();
        Map<String, String> ownerOfObject = new HashMap<>();
        for (SolutionBundle bundle : bundles) {
            String where = "solution '" + bundle.key() + "'";
            if (features != null && !features.contains(bundle.feature())) {
                throw new IllegalStateException(where + ": 'feature' is not a plan feature that exists");
            }
            if (!featuresUsed.add(bundle.feature())) {
                throw new IllegalStateException(where + ": the feature is the feature of another solution too");
            }
            String applicationName = bundle.application().apiName();
            if (applicationName.endsWith("__c")) {
                throw new IllegalStateException(where + ": the application name must not end like an organization's "
                        + "own");
            }
            if (!applicationNames.add(applicationName)) {
                throw new IllegalStateException(where + ": the application name is used by another solution");
            }
            for (String object : bundle.objects()) {
                ObjectDefinition definition = platform.named(object).orElseThrow(() -> new IllegalStateException(
                        where + ": the object '" + object + "' is not defined by the platform (its file belongs in "
                                + "solutions/" + bundle.key() + "/objects)"));
                if (!bundle.feature().equals(definition.requiresFeature())) {
                    throw new IllegalStateException(where + ": the object '" + object + "' does not require the "
                            + "solution's feature");
                }
                String other = ownerOfObject.put(object, bundle.key());
                if (other != null) {
                    throw new IllegalStateException(where + ": the object '" + object + "' is also brought by '"
                            + other + "'");
                }
            }
            checkEntries(bundle, where);
            checkPolicies(bundle, platform, where);
        }
        for (ObjectDefinition object : platform.all()) {
            if (object.requiresFeature() != null && !ownerOfObject.containsKey(object.apiName())) {
                throw new IllegalStateException("the object '" + object.apiName() + "' requires a plan feature and "
                        + "no solution brings it");
            }
        }
    }

    private static void checkEntries(SolutionBundle bundle, String where) {
        Set<String> objects = Set.copyOf(bundle.objects());
        int index = 0;
        for (SolutionBundle.Entry entry : bundle.application().entries()) {
            String part = where + ": application.entries[" + index++ + "]";
            if (entry.kind() == ShippedEntryView.Kind.OBJECT && !objects.contains(entry.object())) {
                throw new IllegalStateException(part + " names an object the solution does not bring");
            }
            if (!entry.readsObject().isEmpty() && !objects.contains(entry.readsObject())) {
                throw new IllegalStateException(part + ": 'readsObject' names an object the solution does not bring");
            }
        }
        boolean leads = bundle.application().entries().stream()
                .anyMatch(entry -> entry.kind() != ShippedEntryView.Kind.HEADING);
        if (!leads) {
            throw new IllegalStateException(where + ": the application has only headings, which lead nowhere");
        }
    }

    private static void checkPolicies(SolutionBundle bundle, PlatformObjects platform, String where) {
        Set<String> names = new HashSet<>();
        Set<String> objects = Set.copyOf(bundle.objects());
        for (SolutionBundle.Policy policy : bundle.policies()) {
            String part = where + ": accessPolicies '" + policy.key() + "'";
            if (!names.add(policy.name().toLowerCase(java.util.Locale.ROOT))) {
                throw new IllegalStateException(part + ": the name is used by another policy of the solution");
            }
            for (Map.Entry<String, Set<ObjectAction>> right : policy.objects().entrySet()) {
                if (!objects.contains(right.getKey())) {
                    throw new IllegalStateException(part + ": a right names '" + right.getKey() + "', an object "
                            + "the solution does not bring");
                }
                boolean kept = platform.named(right.getKey()).map(ObjectDefinition::retainRecords).orElse(false);
                if (kept && right.getValue().contains(ObjectAction.DELETE)) {
                    throw new IllegalStateException(part + ": the delete right on '" + right.getKey() + "', whose "
                            + "records are kept");
                }
            }
        }
    }
}
