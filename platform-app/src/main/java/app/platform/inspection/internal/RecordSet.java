package app.platform.inspection.internal;

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
 */
record RecordSet(String template, String fill, String request, String series) {

    RecordSet {
        Objects.requireNonNull(template, "template");
        Objects.requireNonNull(fill, "fill");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(series, "series");
    }

    /** Prints the objects only: they are API names the platform chose, never text an organization typed. */
    @Override
    public String toString() {
        return "RecordSet[" + template + "]";
    }
}
