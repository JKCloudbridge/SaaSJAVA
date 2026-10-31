package app.platform.solution.internal;

import app.platform.metadata.FieldDefinition;
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
 * entry or a right names an object the solution brings or, as FA13A allows, uses from another solution that lists it
 * back (ADR-0225). Messages name the solution and the part, never a text.
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
            for (SolutionBundle.Application application : bundle.applications()) {
                String applicationName = application.apiName();
                if (applicationName.endsWith("__c")) {
                    throw new IllegalStateException(where + ": the application name must not end like an "
                            + "organization's own");
                }
                if (!applicationNames.add(applicationName)) {
                    throw new IllegalStateException(where + ": the application name is used by another solution or "
                            + "twice in this one");
                }
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
            for (String owner : bundle.sheetOwners()) {
                if (!bundle.objects().contains(owner)) {
                    throw new IllegalStateException(where + ": the sheet owner '" + owner + "' is an object the "
                            + "solution does not bring");
                }
            }
        }
        // The second pass needs every bundle's objects, so it runs when the first has seen them all (FA13A).
        Map<String, SolutionBundle> byFeature = new HashMap<>();
        bundles.forEach(bundle -> byFeature.put(bundle.feature(), bundle));
        for (SolutionBundle bundle : bundles) {
            String where = "solution '" + bundle.key() + "'";
            checkUses(bundle, platform, ownerOfObject, where);
            checkEntries(bundle, where);
            checkPolicies(bundle, platform, byFeature, where);
        }
        checkSharing(byFeature, platform);
        checkRoles(bundles);
        for (ObjectDefinition object : platform.all()) {
            if (object.requiresFeature() != null && !ownerOfObject.containsKey(object.apiName())) {
                throw new IllegalStateException("the object '" + object.apiName() + "' requires a plan feature and "
                        + "no solution brings it");
            }
        }
    }

    /** The page key every shipped application opens on: its Home (FA9, ADR-0203). */
    static final String HOME = "home";

    /**
     * What a solution uses that another brings (FA13A, ADR-0225): the object is brought by another solution, the
     * platform defines it, and its file says the solution's feature gives it too.
     */
    private static void checkUses(SolutionBundle bundle, PlatformObjects platform, Map<String, String> ownerOfObject,
            String where) {
        for (String object : bundle.uses()) {
            String owner = ownerOfObject.get(object);
            if (owner == null || owner.equals(bundle.key())) {
                throw new IllegalStateException(where + ": 'uses' names '" + object + "', which no other "
                        + "solution brings");
            }
            ObjectDefinition definition = platform.named(object).orElseThrow();
            if (!definition.alsoAvailableWith().contains(bundle.feature())) {
                throw new IllegalStateException(where + ": uses the object '" + object + "', whose file does not "
                        + "list the solution's feature under 'alsoAvailableWith'");
            }
        }
    }

    /** The other direction: a feature an object lists is the feature of a solution that uses the object. */
    private static void checkSharing(Map<String, SolutionBundle> byFeature, PlatformObjects platform) {
        for (ObjectDefinition object : platform.all()) {
            for (String feature : object.alsoAvailableWith()) {
                SolutionBundle bundle = byFeature.get(feature);
                if (bundle == null || !bundle.uses().contains(object.apiName())) {
                    throw new IllegalStateException("the object '" + object.apiName() + "' is also available with "
                            + "a feature of a solution that does not use it");
                }
            }
        }
    }

    /**
     * The parts of the applications (FA13B, ADR-0226). Two solutions may declare the very same parts (the second shows
     * the first one's records, which it lists under {@code uses}); an object may not play a part in one and another
     * part, or the same part among other objects, in the other. That would make two applications claim one object.
     */
    static void checkRoles(List<SolutionBundle> bundles) {
        record Claim(String solution, String role, Map<String, String> combination) {
        }
        Map<String, Claim> claimed = new HashMap<>();
        for (SolutionBundle bundle : bundles) {
            for (Map.Entry<String, String> part : bundle.roles().entrySet()) {
                Claim claim = new Claim(bundle.key(), part.getKey(), bundle.roles());
                Claim earlier = claimed.putIfAbsent(part.getValue(), claim);
                if (earlier != null && (!earlier.role().equals(claim.role())
                        || !earlier.combination().equals(claim.combination()))) {
                    throw new IllegalStateException("solution '" + bundle.key() + "': the object '" + part.getValue()
                            + "' is claimed by solution '" + earlier.solution() + "' in another part or with other "
                            + "objects");
                }
            }
        }
    }

    private static Set<String> reachable(SolutionBundle bundle) {
        Set<String> objects = new HashSet<>(bundle.objects());
        objects.addAll(bundle.uses());
        return objects;
    }

    private static void checkEntries(SolutionBundle bundle, String where) {
        Set<String> objects = reachable(bundle);
        for (SolutionBundle.Application application : bundle.applications()) {
            checkEntries(application, objects, where + ": application '" + application.apiName() + "'");
        }
    }

    private static void checkEntries(SolutionBundle.Application application, Set<String> objects, String where) {
        int index = 0;
        Set<String> identities = new HashSet<>();
        for (SolutionBundle.Entry entry : application.entries()) {
            String part = where + ": entries[" + index++ + "]";
            if (entry.kind() == ShippedEntryView.Kind.OBJECT && !objects.contains(entry.object())) {
                throw new IllegalStateException(part + " names an object the solution does not bring");
            }
            if (!entry.readsObject().isEmpty() && !objects.contains(entry.readsObject())) {
                throw new IllegalStateException(part + ": 'readsObject' names an object the solution does not bring");
            }
            if (entry.placeholder() && (!entry.object().isEmpty() || !entry.readsObject().isEmpty())) {
                throw new IllegalStateException(part + ": a placeholder names no object");
            }
            // The address of an entry is made from this identity, so two entries may not share it.
            String identity = switch (entry.kind()) {
                case OBJECT -> "o-" + entry.object();
                case PLATFORM -> "s-" + entry.pageKey();
                case HEADING -> null;
                default -> throw new IllegalStateException("an entry kind this release does not know");
            };
            if (identity != null && !identities.add(identity)) {
                throw new IllegalStateException(part + ": the identity is used by another entry of the application");
            }
        }
        boolean leads = application.entries().stream()
                .anyMatch(entry -> entry.kind() != ShippedEntryView.Kind.HEADING);
        if (!leads) {
            throw new IllegalStateException(where + ": the application has only headings, which lead nowhere");
        }
        SolutionBundle.Entry first = application.entries().get(0);
        if (first.kind() != ShippedEntryView.Kind.PLATFORM || !HOME.equals(first.pageKey())) {
            throw new IllegalStateException(where + ": the first entry is the Home page (a PLATFORM entry with the "
                    + "page key '" + HOME + "')");
        }
    }

    private static void checkPolicies(SolutionBundle bundle, PlatformObjects platform,
            Map<String, SolutionBundle> byFeature, String where) {
        Set<String> names = new HashSet<>();
        Set<String> objects = reachable(bundle);
        for (SolutionBundle.Policy policy : bundle.policies()) {
            String part = where + ": accessPolicies '" + policy.key() + "'";
            for (String feature : policy.alsoAvailableWith()) {
                SolutionBundle other = byFeature.get(feature);
                if (other == null || other == bundle) {
                    throw new IllegalStateException(part + ": 'alsoAvailableWith' names a feature that is not "
                            + "another solution's");
                }
                // A policy must never give a right on an object the organization lacks when only that feature is on.
                for (String object : policy.objects().keySet()) {
                    if (!reachable(other).contains(object)) {
                        throw new IllegalStateException(part + ": is also available with a feature whose solution "
                                + "does not have '" + object + "', on which the policy gives a right");
                    }
                }
            }
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
            for (String key : policy.fields().keySet()) {
                int dot = key.indexOf('.');
                String object = key.substring(0, dot);
                String field = key.substring(dot + 1);
                if (!policy.objects().containsKey(object)) {
                    throw new IllegalStateException(part + ": a right on a field of '" + object + "', on which the "
                            + "policy gives no right (a field right works only with the right on its object)");
                }
                FieldDefinition found = platform.named(object).flatMap(definition -> definition.field(field))
                        .orElseThrow(() -> new IllegalStateException(part + ": a right names the field '" + key
                                + "', which the object does not have"));
                if (found.retired()) {
                    throw new IllegalStateException(part + ": a right names the field '" + key + "', which is no "
                            + "longer in use");
                }
            }
        }
    }
}
