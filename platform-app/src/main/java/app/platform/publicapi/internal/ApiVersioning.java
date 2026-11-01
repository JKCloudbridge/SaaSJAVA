package app.platform.publicapi.internal;

import app.platformapi.ApiHeaders;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The version the public API serves and the one registry of what is deprecated in it (Sprint 30, ADR-0247). Pure.
 *
 * <p><strong>The policy.</strong> The major version is in the address ({@code v1}). Inside a version a change only
 * adds:
 * a new optional property of a response, a new optional parameter, a new operation, a new value of an enumeration that
 * a client is told to expect (error codes, field types, comparisons). Nothing is removed, renamed, retyped or made
 * required. A change that cannot be made that way is a new version beside the old one.
 *
 * <p>An operation is retired in two steps. It is first <em>deprecated</em>: it goes on working, the document marks it
 * {@code deprecated}, and every answer of it carries {@code Deprecation} (the day it was announced),
 * {@code Sunset} (the first day it may be gone) and a {@code Link} to its successor. It may be removed only after the
 * sunset day, and the sunset day may be no earlier than {@link #MINIMUM_NOTICE_MONTHS} months after the announcement:
 * the registry refuses to start with a shorter notice, which is what makes the promise to customers a tested fact.
 */
final class ApiVersioning {

    /** The major version served, as the {@code API-Version} header says it. */
    static final String CURRENT = "v1";

    /** The shortest notice between announcing a deprecation and the sunset day. */
    static final int MINIMUM_NOTICE_MONTHS = 12;

    private static final DateTimeFormatter HTTP_DATE =
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.ENGLISH);

    /**
     * One deprecated operation.
     *
     * @param method the HTTP method
     * @param template the path of the operation, with {@code {name}} for a segment that varies
     * @param announcedOn the day the deprecation was announced to customers
     * @param sunsetOn the first day the operation may be removed
     * @param successor the address of what replaces it (a path or a document address)
     */
    record Deprecation(String method, String template, LocalDate announcedOn, LocalDate sunsetOn, String successor) {

        Deprecation {
            if (method == null || template == null || announcedOn == null || sunsetOn == null || successor == null) {
                throw new IllegalArgumentException("a deprecation names the operation, both days and the successor");
            }
            if (sunsetOn.isBefore(announcedOn.plusMonths(MINIMUM_NOTICE_MONTHS))) {
                throw new IllegalArgumentException("a deprecation gives at least " + MINIMUM_NOTICE_MONTHS
                        + " months between the announcement and the sunset");
            }
        }

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

    private final List<Deprecation> deprecations;

    ApiVersioning(List<Deprecation> deprecations) {
        this.deprecations = List.copyOf(deprecations);
    }

    /** What is deprecated in the version that ships: nothing yet. A deprecation is added here, with its dates. */
    static ApiVersioning shipped() {
        return new ApiVersioning(List.of());
    }

    /** The deprecations, for the document and for a test. */
    List<Deprecation> deprecations() {
        return deprecations;
    }

    /**
     * The headers to put on every answer of the operation: the version always, and for a deprecated operation the
     * three that announce it.
     *
     * @param method the HTTP method of the request
     * @param path the path of the request
     */
    Map<String, String> headersFor(String method, String path) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put(ApiHeaders.API_VERSION, CURRENT);
        String effective = "HEAD".equals(method) ? "GET" : method;
        deprecations.stream().filter(deprecation -> deprecation.matches(effective, path)).findFirst()
                .ifPresent(deprecation -> {
                    // RFC 9745: the announcement as a date item, seconds since the epoch; RFC 8594: the sunset as
                    // an HTTP date; RFC 8288: the successor as a link relation.
                    long announced = deprecation.announcedOn().atStartOfDay(ZoneOffset.UTC).toEpochSecond();
                    headers.put(ApiHeaders.DEPRECATION, "@" + announced);
                    ZonedDateTime sunset = deprecation.sunsetOn().atStartOfDay(ZoneOffset.UTC);
                    headers.put(ApiHeaders.SUNSET, HTTP_DATE.format(sunset));
                    headers.put("Link", "<" + deprecation.successor() + ">; rel=\"successor-version\"");
                });
        return headers;
    }
}
