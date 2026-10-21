package app.platform.solution.internal;

import app.platform.security.ObjectAction;
import app.platform.sharedkernel.solution.ShippedEntryView;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import org.springframework.beans.factory.config.YamlMapFactoryBean;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * Reads the solution files and checks their shape completely (ADR-0181), so that a mistake stops the application from
 * starting instead of being found by an organization. One file per solution, {@code solutions/<key>/solution.yml}.
 *
 * <p>Every message names the solution (its folder, an identifier) and the part ({@code accessPolicies[2]}); none
 * repeats a label, a note or a description, so a mistake in a text never copies the text into a log. An unknown
 * key, a text that is not plain, a repeated key, an entry that names nothing and a right that does not exist are
 * all refused. What can only be checked against the rest of the platform (the objects and the feature exist) is
 * {@link SolutionValidator}'s.
 */
final class SolutionLoader {

    static final String LOCATION = "classpath:solutions/*/solution.yml";

    private static final Pattern KEY = Pattern.compile("[a-z][a-z0-9-]{0,39}");
    private static final Pattern ICON = Pattern.compile("[a-z][a-z0-9-]{0,29}");
    private static final Pattern OBJECT = Pattern.compile("[A-Z][A-Za-z0-9]{0,39}");
    /** A name or a label: letters, digits, spaces and a few marks; nothing that markup or a placeholder could use. */
    private static final Pattern PLAIN_LABEL = Pattern.compile("[\\p{L}\\p{N}][\\p{L}\\p{N} .,&'()/+:-]{0,79}");
    /** A description or a note: the same, with a little more punctuation, longer. */
    private static final Pattern PLAIN_TEXT = Pattern.compile("[\\p{L}\\p{N}][\\p{L}\\p{N} .,;:!?&'()/+-]{0,499}");

    private static final Set<String> BUNDLE_KEYS = Set.of("key", "feature", "label", "removalNote", "application",
            "objects", "accessPolicies");
    private static final Set<String> APPLICATION_KEYS = Set.of("apiName", "label", "iconKey", "entries");
    private static final Set<String> ENTRY_KEYS = Set.of("type", "label", "object", "pageKey", "readsObject");
    private static final Set<String> POLICY_KEYS = Set.of("key", "name", "description", "objects");

    private SolutionLoader() {
    }

    /** Reads every bundle the default location finds. */
    static List<SolutionBundle> load() {
        return load(LOCATION);
    }

    /** The same for the files a pattern finds (the tests point it at deliberately broken files). */
    static List<SolutionBundle> load(String location) {
        try {
            Map<String, SolutionBundle> bundles = new TreeMap<>();
            for (Resource file : new PathMatchingResourcePatternResolver().getResources(location)) {
                SolutionBundle bundle = read(file);
                if (bundles.put(bundle.key(), bundle) != null) {
                    throw new IllegalStateException("solution '" + bundle.key() + "': the key is used by two files");
                }
            }
            return List.copyOf(bundles.values());
        } catch (IOException e) {
            throw new IllegalStateException("The solution files could not be read", e);
        }
    }

    private static SolutionBundle read(Resource file) throws IOException {
        String folder = folderOf(file);
        String where = "solution '" + folder + "'";
        Map<String, Object> root = parse(file, where);
        Node node = new Node(where, root, BUNDLE_KEYS);
        String key = node.pattern("key", KEY, "a lower case key");
        if (!key.equals(folder)) {
            throw new IllegalStateException(where + ": 'key' must be the name of the folder");
        }
        String feature = node.pattern("feature", KEY, "a plan feature key");
        String label = node.pattern("label", PLAIN_LABEL, "a plain label of at most 80 characters");
        String note = node.optional("removalNote") == null ? "" : node.pattern("removalNote", PLAIN_TEXT,
                "plain text of at most 500 characters");
        SolutionBundle.Application application = application(node.child("application", APPLICATION_KEYS));
        List<String> objects = new ArrayList<>();
        for (Object item : node.list("objects")) {
            String name = item instanceof String text ? text : "";
            if (!OBJECT.matcher(name).matches()) {
                throw new IllegalStateException(where + ": 'objects' lists API names (a capital letter, then "
                        + "letters and digits)");
            }
            if (objects.contains(name)) {
                throw new IllegalStateException(where + ": the object '" + name + "' is listed twice");
            }
            objects.add(name);
        }
        List<SolutionBundle.Policy> policies = new ArrayList<>();
        Set<String> policyKeys = new LinkedHashSet<>();
        List<Node> policyNodes = node.children("accessPolicies", POLICY_KEYS);
        for (int i = 0; i < policyNodes.size(); i++) {
            Node policy = policyNodes.get(i);
            String policyKey = policy.pattern("key", KEY, "a lower case key");
            if (!policyKeys.add(policyKey)) {
                throw new IllegalStateException(policy.where() + ": the key '" + policyKey + "' is used twice");
            }
            policies.add(new SolutionBundle.Policy(policyKey,
                    policy.pattern("name", PLAIN_LABEL, "a plain name of at most 80 characters"),
                    policy.pattern("description", PLAIN_TEXT, "plain text of at most 500 characters"),
                    rights(policy)));
        }
        return new SolutionBundle(key, feature, label, note, application, objects, policies);
    }

    private static SolutionBundle.Application application(Node node) {
        String apiName = node.pattern("apiName", KEY, "a lower case name");
        String label = node.pattern("label", PLAIN_LABEL, "a plain label of at most 80 characters");
        String icon = node.optional("iconKey") == null ? "" : node.pattern("iconKey", ICON, "an icon key");
        List<SolutionBundle.Entry> entries = new ArrayList<>();
        List<Node> nodes = node.children("entries", ENTRY_KEYS);
        if (nodes.isEmpty()) {
            throw new IllegalStateException(node.where() + ": 'entries' lists at least one entry");
        }
        for (Node entry : nodes) {
            ShippedEntryView.Kind kind;
            try {
                kind = ShippedEntryView.Kind.valueOf(entry.text("type").toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException(entry.where() + ": 'type' is OBJECT, PLATFORM or HEADING");
            }
            String label2 = entry.optional("label") == null ? "" : entry.pattern("label", PLAIN_LABEL,
                    "a plain label of at most 80 characters");
            String object = entry.optional("object") == null ? "" : entry.pattern("object", OBJECT, "an API name");
            String pageKey = entry.optional("pageKey") == null ? "" : entry.pattern("pageKey", KEY, "a page key");
            String reads = entry.optional("readsObject") == null ? "" : entry.pattern("readsObject", OBJECT,
                    "an API name");
            switch (kind) {
                case OBJECT -> {
                    if (object.isEmpty() || !pageKey.isEmpty()) {
                        throw new IllegalStateException(entry.where() + ": an OBJECT entry names its 'object' and "
                                + "no 'pageKey'");
                    }
                }
                case PLATFORM -> {
                    if (pageKey.isEmpty() || label2.isEmpty() || !object.isEmpty()) {
                        throw new IllegalStateException(entry.where() + ": a PLATFORM entry names a 'pageKey' and "
                                + "a 'label' and no 'object'");
                    }
                }
                case HEADING -> {
                    if (label2.isEmpty() || !object.isEmpty() || !pageKey.isEmpty() || !reads.isEmpty()) {
                        throw new IllegalStateException(entry.where() + ": a HEADING entry has a 'label' and "
                                + "nothing else");
                    }
                }
                default -> throw new IllegalStateException("an entry kind this release does not know");
            }
            entries.add(new SolutionBundle.Entry(kind, label2, object, pageKey, reads));
        }
        return new SolutionBundle.Application(apiName, label, icon, entries);
    }

    private static Map<String, Set<ObjectAction>> rights(Node policy) {
        Map<String, Set<ObjectAction>> rights = new LinkedHashMap<>();
        Object declared = policy.optional("objects");
        if (declared == null) {
            return rights;
        }
        if (!(declared instanceof Map<?, ?> map)) {
            throw new IllegalStateException(policy.where() + ": 'objects' maps an API name to a list of rights");
        }
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String object = String.valueOf(entry.getKey());
            if (!OBJECT.matcher(object).matches()) {
                throw new IllegalStateException(policy.where() + ": 'objects' names an API name that is not shaped "
                        + "like one");
            }
            if (!(entry.getValue() instanceof Collection<?> actions) || actions.isEmpty()) {
                throw new IllegalStateException(policy.where() + ": the rights on '" + object + "' are a list");
            }
            Set<ObjectAction> parsed = EnumSet.noneOf(ObjectAction.class);
            for (Object action : actions) {
                parsed.add(ObjectAction.fromKey(String.valueOf(action)).orElseThrow(() -> new IllegalStateException(
                        policy.where() + ": a right on '" + object + "' is not one the platform knows")));
            }
            rights.put(object, parsed);
        }
        return rights;
    }

    private static String folderOf(Resource file) throws IOException {
        String path = file.getURL().toString();
        String[] parts = path.split("/");
        return parts.length < 2 ? path : parts[parts.length - 2];
    }

    private static Map<String, Object> parse(Resource file, String where) {
        YamlMapFactoryBean yaml = new YamlMapFactoryBean();
        yaml.setResources(file);
        try {
            Map<String, Object> map = yaml.getObject();
            return map == null ? Map.of() : map;
        } catch (RuntimeException e) {
            // A YAML syntax mistake: say which solution, never what the parser quotes of the text.
            throw new IllegalStateException(where + ": the file is not valid YAML", e);
        }
    }

    /** One mapping of a file with the place it came from; refuses keys it was not told about. */
    private static final class Node {

        private final String where;
        private final Map<?, ?> values;

        Node(String where, Object content, Set<String> allowed) {
            this.where = where;
            if (!(content instanceof Map<?, ?> map)) {
                throw new IllegalStateException(where + ": expected a mapping");
            }
            for (Object key : map.keySet()) {
                if (!allowed.contains(String.valueOf(key))) {
                    throw new IllegalStateException(where + ": '" + key + "' is not a key this part knows");
                }
            }
            this.values = map;
        }

        String where() {
            return where;
        }

        Object optional(String key) {
            return values.get(key);
        }

        String text(String key) {
            Object value = values.get(key);
            if (!(value instanceof String text) || text.isBlank()) {
                throw new IllegalStateException(where + ": '" + key + "' is missing or not text");
            }
            return text.strip();
        }

        String pattern(String key, Pattern shape, String expected) {
            String text = text(key);
            if (!shape.matcher(text).matches()) {
                // Names the key and what is expected, never the text that was found.
                throw new IllegalStateException(where + ": '" + key + "' must be " + expected);
            }
            return text;
        }

        Node child(String key, Set<String> allowed) {
            Object value = values.get(key);
            if (value == null) {
                throw new IllegalStateException(where + ": '" + key + "' is missing");
            }
            return new Node(where + ": " + key, value, allowed);
        }

        List<Object> list(String key) {
            Object value = values.get(key);
            if (value == null) {
                return List.of();
            }
            if (!(value instanceof Collection<?> items)) {
                throw new IllegalStateException(where + ": '" + key + "' is a list");
            }
            return new ArrayList<>(items);
        }

        List<Node> children(String key, Set<String> allowed) {
            List<Node> nodes = new ArrayList<>();
            List<Object> items = list(key);
            for (int i = 0; i < items.size(); i++) {
                nodes.add(new Node(where + ": " + key + "[" + i + "]", items.get(i), allowed));
            }
            return nodes;
        }
    }
}
