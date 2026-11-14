package app.platform.inspection.internal;

import app.platform.data.QueryFilter;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One set of the records the cluster's functions work on: the object of the templates, of the fill-ins and of the
 * change requests of an application, and the series that numbers its templates (FA13B, ADR-0226). Every application of
 * the kind has a set of its own, declared as data in its solution file; the code never names an object, it is handed
 * the set the request or the record belongs to. Two applications may share one set (Document Management shows the
 * Quality Management System's records).
 *
 * <p><strong>A narrowed set</strong> (MC1.1, ADR-0259) is the very same objects seen through one more condition:
 * only the records that carry the application's name in their {@code application} field. An application whose
 * documents come from Document Management is such a set. The narrowing <em>chooses</em>; it never grants. It is added
 * to the record store's own question ({@link #narrow}) or checked on a record the store already gave
 * ({@link #admits}); who may read a record is still the record store's and the decision API's answer.
 *
 * @param template the object of the templates (documents)
 * @param fill the object of the fill-ins (records)
 * @param request the object of the review records of change requests
 * @param series the number series that numbers the templates
 * @param options what the application's solution file states about how it behaves (MC1, ADR-0257); see
 *        {@link RecordSets#FOOTER_DATE}, {@link RecordSets#PUBLISH_TO} and {@link RecordSets#PUBLISH_DEPARTMENT}
 * @param application the name an application of this set writes on its records, or null for a set that shows every
 *        record of its objects (never a text an organization typed: a name the solution file declares)
 */
record RecordSet(String template, String fill, String request, String series, Map<String, String> options,
        String application) {

    /** A set that states no options and is not narrowed. */
    RecordSet(String template, String fill, String request, String series) {
        this(template, fill, request, series, Map.of(), null);
    }

    /** A set that states options and is not narrowed. */
    RecordSet(String template, String fill, String request, String series, Map<String, String> options) {
        this(template, fill, request, series, options, null);
    }

    /** Whether the footer of a sheet of this set carries the day the Revision was published. */
    boolean footerDate() {
        return "true".equals(options.get(RecordSets.FOOTER_DATE));
    }

    /** Whether the application asks for one fill-in each month for each plant (MC2A, ADR-0260). */
    boolean monthlyFillIn() {
        return "true".equals(options.get(RecordSets.MONTHLY_FILL_IN));
    }

    /** Whether a template of this set is published to the places alone (each place's one fixed department). */
    boolean placesOnly() {
        return RecordSets.PLACES.equals(options.get(RecordSets.PUBLISH_TO));
    }

    /** The code of the department that receives a template published to a place, or null. */
    String placeDepartment() {
        return options.get(RecordSets.PUBLISH_DEPARTMENT);
    }

    /**
     * The same set without the options that say where a template is published to. The publish of a document is
     * Document Management's, the same whichever application's page it is made from (MC1.1, ADR-0259); the options stay
     * declared in the solution file for the act of an application that publishes a form to its own audience (MC2A).
     */
    RecordSet withoutPublishOptions() {
        java.util.Map<String, String> rest = new java.util.HashMap<>(options);
        rest.remove(RecordSets.PUBLISH_TO);
        rest.remove(RecordSets.PUBLISH_DEPARTMENT);
        return new RecordSet(template, fill, request, series, rest, application);
    }

    /** Whether this set shows only the records of one application. */
    boolean narrowed() {
        return application != null;
    }

    /**
     * The question with the narrowing added: the same question, and the record carries this application's name. A set
     * that is not narrowed returns the question as it is. Use it for the filter of every question put to the records of
     * the set's objects, in the record store.
     */
    QueryFilter narrow(QueryFilter filter) {
        if (application == null) {
            return filter;
        }
        QueryFilter only = QueryFilter.on(RecordSets.APPLICATION_FIELD, QueryFilter.Op.EQ, application);
        return filter == null ? only : QueryFilter.and(List.of(filter, only));
    }

    /** Whether a record the store gave, by its fields, belongs to this set's application (true when not narrowed). */
    boolean admits(Map<String, Object> fields) {
        return application == null || application.equals(fields.get(RecordSets.APPLICATION_FIELD));
    }

    RecordSet {
        Objects.requireNonNull(template, "template");
        Objects.requireNonNull(fill, "fill");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(series, "series");
        options = Map.copyOf(options);
    }

    /** Prints the objects only: they are API names the platform chose, never text an organization typed. */
    @Override
    public String toString() {
        return "RecordSet[" + template + (application == null ? "" : " narrowed") + "]";
    }
}
