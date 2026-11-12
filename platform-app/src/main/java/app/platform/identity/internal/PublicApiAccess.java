package app.platform.identity.internal;

import app.platformapi.ApiPaths;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * What a token may reach of the public API (Sprint 30, ADR-0244, ADR-0249): the one place that knows the closed table
 * of public operations and the one place Sprint 31 fills when tokens get scopes. It is the analogue of
 * {@link EmbeddedAccessPolicy} for the interface that is meant for other people's software.
 *
 * <p><strong>Scope is necessary, never sufficient.</strong> Today a token of a signed-in user carries no scope and
 * reaches every operation of the table; tomorrow a token that carries scopes reaches only the operations whose scope
 * it holds. Either way the answer of this class only lets a request go on to the controller: the member's own
 * permissions, field rights and record security are decided there, by the same code as for the internal interface.
 *
 * <p>The table is closed. A request under the public prefix that matches no operation is not refused here (it
 * is answered {@code NOT_FOUND} by the dispatcher like any unknown address); a token of a custom page's script never
 * reaches the prefix at all (its own policy lists only the internal interface). {@code PublicApiBoundaryIT} compares
 * this table with the controllers, so an operation cannot be added to one side alone.
 */
final class PublicApiAccess {

    /**
     * One operation of the public API.
     *
     * @param method the HTTP method
     * @param template the path, with {@code {name}} for a segment that varies
     * @param name the stable name of the operation (the operation identifier of the contract document)
     * @param scope the scope a token needs for it once tokens carry scopes (Sprint 31)
     */
    record Operation(String method, String template, String name, String scope) {

        boolean matches(String requestedMethod, String path) {
            if (!method.equals(requestedMethod)) {
                return false;
            }
            String[] expected = template.split("/");
            String[] actual = path.split("/", -1);
            if (expected.length != actual.length) {
                return false;
            }
            for (int index = 0; index < expected.length; index++) {
                boolean variable = expected[index].startsWith("{");
                if (variable ? actual[index].isEmpty() : !expected[index].equals(actual[index])) {
                    return false;
                }
            }
            return true;
        }
    }

    private static final String OBJECTS = ApiPaths.PUBLIC_OBJECTS;

    /** The table of Sprint 30 (ADR-0244 section 2). Anything not listed is not reachable. */
    static final List<Operation> OPERATIONS = List.of(
            new Operation("GET", ApiPaths.PUBLIC_OPENAPI, "getPublicApiDescription", null),
            new Operation("GET", OBJECTS, "listObjects", "objects.read"),
            new Operation("GET", OBJECTS + "/{object}", "describeObject", "objects.read"),
            new Operation("POST", OBJECTS + "/{object}/records", "createRecord", "records.write"),
            new Operation("POST", OBJECTS + "/{object}/records/query", "queryRecords", "records.read"),
            new Operation("POST", OBJECTS + "/{object}/records/bulk", "bulkRecords", "records.write"),
            new Operation("GET", OBJECTS + "/{object}/records/{id}", "getRecord", "records.read"),
            new Operation("PATCH", OBJECTS + "/{object}/records/{id}", "updateRecord", "records.write"),
            new Operation("DELETE", OBJECTS + "/{object}/records/{id}", "deleteRecord", "records.write"),
            new Operation("POST", OBJECTS + "/{object}/records/{id}/restore", "restoreRecord", "records.write"));

    private PublicApiAccess() {
    }

    /** The operation a request is for, if the table has one. */
    static Optional<Operation> operationOf(String method, String path) {
        if (path == null || oddlyShaped(path)) {
            return Optional.empty();
        }
        // HEAD is answered as GET by the dispatcher; the table lists GET.
        String effective = "HEAD".equals(method) ? "GET" : method;
        return OPERATIONS.stream().filter(operation -> operation.matches(effective, path)).findFirst();
    }

    /**
     * Whether a token may reach the operation.
     *
     * @param operation the operation
     * @param grantedScopes the scopes the token carries, or empty for a token without scopes (a signed-in user's token
     *        today), which reaches every operation of the table
     */
    static boolean permits(Operation operation, Optional<Set<String>> grantedScopes) {
        if (operation.scope() == null || grantedScopes.isEmpty()) {
            return true;
        }
        return grantedScopes.get().contains(operation.scope());
    }

    /** The reasons a reader of the code can find for each operation, for the documentation and for a test. */
    static List<String> names() {
        return OPERATIONS.stream().map(Operation::name).toList();
    }

    /**
     * A path with an escape, a semicolon, a backslash, a dot segment or an empty segment is never an operation (the
     * web firewall refuses most of them already; this is the second line, as for the other policy).
     */
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
