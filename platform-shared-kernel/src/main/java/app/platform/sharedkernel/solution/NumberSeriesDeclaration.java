package app.platform.sharedkernel.solution;

import java.util.Map;
import java.util.Objects;

/**
 * One number series as a shipped solution declares it (FA2A, ADR-0176, ADR-0184).
 *
 * <p>The formats are written in a closed language: literal text from {@code [A-Za-z0-9/_-]}, {@code {field:<apiName>}}
 * for the stored value of a short-code picklist or text field of the record, and exactly one {@code {seq:N}} for the
 * counter, padded with zeros to N digits. The record store checks them when the application starts.
 *
 * @param key the permanent name of the series (lower case letters, digits and hyphens)
 * @param object the API name of the object whose records are numbered
 * @param field the API name of the field that holds the number: a unique text field only the platform writes
 * @param selector the API name of the picklist whose value chooses the format, or an empty text when the series has one
 *        format
 * @param formats the formats, by value of the selector (a series with no selector has the one format under the empty
 *        text). A value of the selector that has no format here cannot be numbered
 */
public record NumberSeriesDeclaration(String key, String object, String field, String selector,
        Map<String, String> formats) {

    /** Copies what is given, keeping the order. */
    public NumberSeriesDeclaration {
        Objects.requireNonNull(key);
        Objects.requireNonNull(object);
        Objects.requireNonNull(field);
        selector = selector == null ? "" : selector;
        formats = Map.copyOf(formats);
    }

    /** Prints the key only: the formats are the platform's but the rest of a log line should never need them. */
    @Override
    public String toString() {
        return "NumberSeriesDeclaration[" + key + "]";
    }
}
