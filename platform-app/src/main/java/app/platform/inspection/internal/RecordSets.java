package app.platform.inspection.internal;

import app.platform.sharedkernel.solution.NumberSeriesDeclaration;
import app.platform.sharedkernel.solution.NumberSeriesDeclarations;
import app.platform.sharedkernel.solution.ObjectRoleDeclarations;
import app.platform.sharedkernel.solution.ObjectRoles;
import app.platform.tenant.TenantContext;
import app.platform.tenant.TenantContexts;
import app.platformapi.ApiException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Which set of records an application, or an object, means (FA13B, ADR-0226). This is the one place the cluster asks
 * "which objects does this application use?"; the answer is the data the solution files declare, read through the
 * shared-kernel contract, so a further application of the kind needs files and no code.
 *
 * <p><strong>Checked at start.</strong> An application that declares parts must name all three of ours (template,
 * fill-in, change request), and the template object must have exactly one number series; a mistake stops the start and
 * names the application, so a build finds it and an organization never does.
 *
 * <p><strong>The browser is never trusted.</strong> A request names an application only to choose among the sets; it
 * proves nothing. {@link #require} answers "not found", in one set of words, for an application that is unknown, one
 * the organization does not have (its plan feature is off) and a request with no member (the platform address), so
 * nothing is learned from the difference. What the member may do inside the set is decided, as always, by the record
 * store and the decision API.
 */
@Component
final class RecordSets {

    /** The names of the parts the cluster needs; they are written in the solution files. */
    static final String TEMPLATE = "template";
    static final String FILL = "fill";
    static final String REQUEST = "changeRequest";

    /** Option: the footer carries the day of the Revision's publish ({@code true}). */
    static final String FOOTER_DATE = "footerDate";
    /** Option: where a template is published to; {@link #PLACES} publishes to the places alone. */
    static final String PUBLISH_TO = "publishTo";
    /** Option: the code of the one department that receives a template published to a place. */
    static final String PUBLISH_DEPARTMENT = "publishDepartment";
    /** The value of {@link #PUBLISH_TO} that means the places alone. */
    static final String PLACES = "places";
    private static final Set<String> OPTION_NAMES = Set.of(FOOTER_DATE, PUBLISH_TO,
            PUBLISH_DEPARTMENT);

    private static final String NO_APPLICATION = "This application does not exist.";

    private final ObjectRoleDeclarations declarations;
    private final TenantContexts contexts;
    /** The distinct sets, each with the applications that show it in the order the grid lists them. */
    private final Map<RecordSet, List<String>> applicationsOf = new LinkedHashMap<>();
    private final Map<String, RecordSet> byApplication = new LinkedHashMap<>();

    RecordSets(ObjectRoleDeclarations declarations, NumberSeriesDeclarations numbers, TenantContexts contexts) {
        this.declarations = declarations;
        this.contexts = contexts;
        for (ObjectRoles roles : declarations.declared()) {
            RecordSet set = setOf(roles, numbers);
            byApplication.put(roles.application(), set);
            applicationsOf.computeIfAbsent(set, key -> new ArrayList<>()).add(roles.application());
        }
        checkDistinct();
    }

    private static RecordSet setOf(ObjectRoles roles, NumberSeriesDeclarations numbers) {
        String where = "application '" + roles.application() + "'";
        String template = roles.object(TEMPLATE);
        String fill = roles.object(FILL);
        String request = roles.object(REQUEST);
        if (template == null || fill == null || request == null) {
            throw new IllegalStateException(where + ": 'roles' must name the parts " + TEMPLATE + ", " + FILL
                    + " and " + REQUEST);
        }
        List<NumberSeriesDeclaration> series = numbers.declared().stream()
                .filter(declared -> declared.object().equals(template)).toList();
        if (series.size() != 1) {
            throw new IllegalStateException(where + ": the template object must have exactly one number series, "
                    + "found " + series.size());
        }
        for (Map.Entry<String, String> option : roles.options().entrySet()) {
            if (!OPTION_NAMES.contains(option.getKey())) {
                throw new IllegalStateException(where + ": 'options' names '" + option.getKey()
                        + "', which the functions do not know");
            }
        }
        String publishTo = roles.options().get(PUBLISH_TO);
        if (publishTo != null && !PLACES.equals(publishTo)) {
            throw new IllegalStateException(where + ": 'publishTo' is 'places' or absent");
        }
        if (PLACES.equals(publishTo) != roles.options().containsKey(PUBLISH_DEPARTMENT)) {
            throw new IllegalStateException(where + ": 'publishTo: places' and 'publishDepartment' go together");
        }
        return new RecordSet(template, fill, request, series.get(0).key(), roles.options());
    }

    /** No object may belong to two sets: that would let two applications claim one object. */
    private void checkDistinct() {
        Set<String> seen = new java.util.HashSet<>();
        for (RecordSet set : applicationsOf.keySet()) {
            for (String object : List.of(set.template(), set.fill(), set.request())) {
                if (!seen.add(object)) {
                    throw new IllegalStateException("the object '" + object + "' belongs to two sets of records");
                }
            }
        }
    }

    /**
     * The set of an application the organization of the request has.
     *
     * @throws ApiException {@code NOT_FOUND} for an unknown application, for one whose plan feature is off and where no
     *         member is involved, all in the same words
     */
    RecordSet require(String application) {
        contexts.current().filter(context -> context.membershipId() != null)
                .orElseThrow(() -> ApiException.notFound(NO_APPLICATION));
        RecordSet set = application == null ? null : byApplication.get(application);
        if (set == null || declarations.available(application).isEmpty()) {
            throw ApiException.notFound(NO_APPLICATION);
        }
        return set;
    }

    /** The set whose template object this is. */
    Optional<RecordSet> ofTemplate(String object) {
        return applicationsOf.keySet().stream().filter(set -> set.template().equals(object)).findFirst();
    }

    /** The set whose fill-in object this is. */
    Optional<RecordSet> ofFill(String object) {
        return applicationsOf.keySet().stream().filter(set -> set.fill().equals(object)).findFirst();
    }

    /** The set whose change-request object this is. */
    Optional<RecordSet> ofRequest(String object) {
        return applicationsOf.keySet().stream().filter(set -> set.request().equals(object)).findFirst();
    }

    /** Every template object of every set, for a hook or a rule that serves them all. */
    List<String> templates() {
        return applicationsOf.keySet().stream().map(RecordSet::template).toList();
    }

    /** Every fill-in object of every set. */
    List<String> fills() {
        return applicationsOf.keySet().stream().map(RecordSet::fill).toList();
    }

    /** Every change-request object of every set. */
    List<String> requests() {
        return applicationsOf.keySet().stream().map(RecordSet::request).toList();
    }

    /**
     * The application a link into the set's pages should name: the first of the set's applications the organization of
     * the current context has, else the first declared (a link to an application the organization lacks leads to the
     * plain "not available" page, as a link always could).
     */
    String applicationFor(RecordSet set) {
        List<String> names = applicationsOf.getOrDefault(set, List.of());
        if (names.isEmpty()) {
            throw new IllegalStateException("a set of records no application shows");
        }
        if (contexts.current().map(TenantContext::membershipId).isEmpty()) {
            return names.get(0);
        }
        return names.stream().filter(name -> declarations.available(name).isPresent()).findFirst()
                .orElse(names.get(0));
    }
}
