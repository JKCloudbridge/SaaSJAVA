package app.platform.identity.internal;

import app.platformapi.ApiPaths;
import java.util.List;
import java.util.Set;

/**
 * What the access token of a custom page's script may reach (Sprint 18, ADR-0127): the data interface and what a page
 * needs to draw itself, and nothing else. The script is the organization's own code running with the permissions of
 * whoever looks at the page, so the token is cut down to the part of the API that is about data: reading the
 * organization's definitions, and working with records, files, searches and imports of objects. Everything that changes
 * how the organization is set up, who may do what, who the members are and how a person signs in is out of reach, even
 * for a viewer who holds those abilities.
 *
 * <p>The list is the one place to change when a page needs more; every entry says why it is there. A request that does
 * not match any rule is refused, which is the safe direction for a path added later. Paths are matched as the server
 * received them; a path with an escape, a semicolon, a backslash, a dot segment or an empty segment is refused outright
 * (the web firewall already refuses most of them, this is the second line).
 */
final class EmbeddedAccessPolicy {

    /** How a rule's path is matched: that path only, what is below it, or both. */
    private enum Match { EXACT, BELOW, EXACT_OR_BELOW }

    /** One thing a token may do: the methods, and the path, matched as {@link Match} says. */
    private record Rule(Set<String> methods, String path, Match match, String why) {

        boolean permits(String method, String requested) {
            if (!methods.contains(method)) {
                return false;
            }
            boolean exact = requested.equals(path);
            boolean below = requested.startsWith(path + "/");
            return switch (match) {
                case EXACT -> exact;
                case BELOW -> below;
                case EXACT_OR_BELOW -> exact || below;
            };
        }
    }

    private static final Set<String> READ = Set.of("GET", "HEAD");
    private static final Set<String> ANY = Set.of("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE");

    private static final List<Rule> RULES = List.of(
            new Rule(READ, ApiPaths.TENANT_CURRENT, Match.EXACT, "the name of the organization the page is shown in"),
            new Rule(READ, ApiPaths.AUTH_ME, Match.EXACT,
                    "who is looking at the page, for a greeting or a filter by owner"),
            new Rule(READ, ApiPaths.MY_APPLICATIONS, Match.EXACT, "the applications and tabs of the member"),
            new Rule(READ, ApiPaths.DATA_CATALOGUE, Match.EXACT, "which objects and fields exist for the member"),
            new Rule(READ, ApiPaths.DATA_ACCESS_MINE, Match.EXACT, "what the member may do with each object"),
            new Rule(READ, ApiPaths.METADATA_OBJECTS, Match.EXACT_OR_BELOW,
                    "an object's definition, fields, layouts, record pages and record types, to build a screen from"),
            new Rule(READ, ApiPaths.METADATA_FIELD_TYPES, Match.EXACT, "the field types, to draw an input"),
            new Rule(ANY, ApiPaths.OBJECTS, Match.BELOW,
                    "the records of an object, their search, files, shares, imports and recycle bin, and what a screen "
                            + "needs to draw them (the form description, the names behind links, the saved list "
                            + "views): the data interface, every call decided by the viewer's own permissions"));

    private EmbeddedAccessPolicy() {
    }

    /**
     * Whether a request with this method and path may be made with the token of a custom page's script.
     *
     * @param method the HTTP method
     * @param path the path of the request as received (without the query)
     */
    static boolean permits(String method, String path) {
        if (path == null || path.isEmpty() || !path.startsWith(ApiPaths.V1 + "/") || oddlyShaped(path)) {
            return false;
        }
        return RULES.stream().anyMatch(rule -> rule.permits(method, path));
    }

    /** The reasons the rules are there, for the documentation and for a test that every rule explains itself. */
    static List<String> reasons() {
        return RULES.stream().map(Rule::why).toList();
    }

    private static boolean oddlyShaped(String path) {
        if (path.indexOf('%') >= 0 || path.indexOf(';') >= 0 || path.indexOf('\\') >= 0 || path.contains("//")) {
            return true;
        }
        for (String segment : path.split("/")) {
            if (segment.equals(".") || segment.equals("..")) {
                return true;
            }
        }
        return false;
    }
}
