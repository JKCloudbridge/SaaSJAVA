package app.platform.metadata.internal;

import app.platform.metadata.internal.DefinitionSchemas.Issue;
import app.platform.security.Ability;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/**
 * The rules of layouts, record pages and applications that a JSON schema cannot say (ADR-0070): a field appears once in
 * a layout, a component key is unique in a page, an ability named by a visibility rule exists. (An application has
 * no such rule: its shape is all there is.) They run on a document
 * that already fits its schema, and name the place that is wrong in the same way the schema check does.
 *
 * <p>Whether the things a definition names exist (fields, objects, layouts, record pages, record types) is not decided
 * here but by the dependency graph before every publication (ADR-0065): a group of changes may create them, and the
 * order in which a person drafts them must not matter.
 */
final class DefinitionRules {

    private DefinitionRules() {
    }

    /** The places where the layout breaks a rule; empty when it keeps them. */
    static List<Issue> layout(JsonNode definition) {
        List<Issue> issues = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        JsonNode sections = definition.path("sections");
        for (int s = 0; s < sections.size(); s++) {
            JsonNode fields = sections.get(s).path("fields");
            for (int f = 0; f < fields.size(); f++) {
                String field = fields.get(f).path("field").asString();
                if (!seen.add(field)) {
                    issues.add(new Issue("definition.sections[" + s + "].fields[" + f + "].field",
                            "This field is already in the layout; a field appears once."));
                }
            }
        }
        return issues;
    }

    /** The places where the record page breaks a rule; empty when it keeps them. */
    static List<Issue> recordPage(JsonNode definition) {
        List<Issue> issues = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        int headers = 0;
        JsonNode components = definition.path("components");
        for (int c = 0; c < components.size(); c++) {
            JsonNode component = components.get(c);
            String base = "definition.components[" + c + "]";
            if (!keys.add(component.path("key").asString())) {
                issues.add(new Issue(base + ".key", "This key is used twice in the page; keys are unique."));
            }
            if ("HEADER".equals(component.path("type").asString()) && ++headers > 1) {
                issues.add(new Issue(base + ".type", "A page has at most one header."));
            }
            rule(component.path("visibleWhen"), base + ".visibleWhen", issues);
            JsonNode tabs = component.path("tabs");
            for (int t = 0; t < tabs.size(); t++) {
                JsonNode inside = tabs.get(t).path("components");
                for (int i = 0; i < inside.size(); i++) {
                    String place = base + ".tabs[" + t + "].components[" + i + "]";
                    if (!keys.add(inside.get(i).path("key").asString())) {
                        issues.add(new Issue(place + ".key", "This key is used twice in the page; keys are unique."));
                    }
                    rule(inside.get(i).path("visibleWhen"), place + ".visibleWhen", issues);
                }
            }
        }
        Set<String> actions = new HashSet<>();
        JsonNode listed = definition.path("actions");
        for (int a = 0; a < listed.size(); a++) {
            String base = "definition.actions[" + a + "]";
            if (!actions.add(listed.get(a).path("action").asString())) {
                issues.add(new Issue(base + ".action", "This action is listed twice."));
            }
            rule(listed.get(a).path("visibleWhen"), base + ".visibleWhen", issues);
        }
        return issues;
    }

    /** Every field a visibility rule compares, in the order they appear; empty for a missing rule. */
    static List<String> fieldsOf(JsonNode rule) {
        List<String> fields = new ArrayList<>();
        collect(rule, fields, null);
        return fields;
    }

    private static void rule(JsonNode rule, String path, List<Issue> issues) {
        if (rule.isMissingNode()) {
            return;
        }
        List<String> abilities = new ArrayList<>();
        collect(rule, null, abilities);
        if (abilities.stream().anyMatch(key -> Ability.fromKey(key).isEmpty())) {
            issues.add(new Issue(path, "A rule names an ability that does not exist."));
        }
    }

    private static void collect(JsonNode rule, List<String> fields, List<String> abilities) {
        if (rule == null || rule.isMissingNode()) {
            return;
        }
        if (fields != null && rule.has("field")) {
            fields.add(rule.path("field").asString());
        }
        if (abilities != null && rule.has("ability")) {
            abilities.add(rule.path("ability").asString());
        }
        for (String group : List.of("all", "any")) {
            rule.path(group).forEach(inner -> collect(inner, fields, abilities));
        }
    }
}
