package app.platform.inspection.internal;

import java.util.Map;
import java.util.Objects;

/**
 * One set of the records the cluster's functions work on: the object of the templates, of the fill-ins and of the
 * change requests of an application, and the series that numbers its templates (FA13B, ADR-0226). Every application of
 * the kind has a set of its own, declared as data in its solution file; the code never names an object, it is handed
 * the set the request or the record belongs to. Two applications may share one set (Document Management shows the
 * Quality Management System's records).
 *
 * @param template the object of the templates (documents)
 * @param fill the object of the fill-ins (records)
 * @param request the object of the review records of change requests
 * @param series the number series that numbers the templates
 * @param options what the application's solution file states about how it behaves (MC1, ADR-0257); see
 *        {@link RecordSets#FOOTER_DATE}, {@link RecordSets#PUBLISH_TO} and {@link RecordSets#PUBLISH_DEPARTMENT}
 */
record RecordSet(String template, String fill, String request, String series, Map<String, String> options) {

    /** A set that states no options. */
    RecordSet(String template, String fill, String request, String series) {
        this(template, fill, request, series, Map.of());
    }

    /** Whether the footer of a sheet of this set carries the day the Revision was published. */
    boolean footerDate() {
        return "true".equals(options.get(RecordSets.FOOTER_DATE));
    }

    /** Whether a template of this set is published to the places alone (each place's one fixed department). */
    boolean placesOnly() {
        return RecordSets.PLACES.equals(options.get(RecordSets.PUBLISH_TO));
    }

    /** The code of the department that receives a template published to a place, or null. */
    String placeDepartment() {
        return options.get(RecordSets.PUBLISH_DEPARTMENT);
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
        return "RecordSet[" + template + "]";
    }
}
