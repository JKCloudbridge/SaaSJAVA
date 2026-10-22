package app.platform.data;

import java.util.Map;
import java.util.Optional;

/**
 * The numbers of the series the platform ships (FA2A, ADR-0176, ADR-0184): a number written in a closed format from
 * values of the record, with a counter for each scope, for example {@code WI/PRD/03}. It generalises the auto-number
 * of a field, which stays as it is.
 *
 * <p><strong>Code only.</strong> No endpoint and no screen takes a number: the engine that decides when a record gets
 * one (an approval step, a later sprint) calls this from inside its own transaction. The number is then written to the
 * record with the platform's own write ({@link BackgroundWrites}), because the field is one only the platform writes.
 *
 * <p>The counter of a scope is one row taken with one statement and held until the surrounding transaction ends, so two
 * instances numbering one scope at once queue and get two different numbers, and scopes do not wait for each other. A
 * number taken in a transaction that rolls back is never issued, so the next taker gets it; a number that was issued is
 * never issued again, and gaps are allowed. A number longer than its width is not cut. The unique field that holds the
 * number is the database's backstop.
 *
 * <p>Nothing a record holds (a code, a number) is written to a log, an audit record, an event or a message of
 * {@link NumberSeriesException}.
 */
public interface NumberSeries {

    /** Whether a series with this key is declared. */
    boolean declared(String seriesKey);

    /**
     * The fixed part of the number in front of the serial, for example {@code WI/PRD/}: all a preview may show, because
     * showing the serial would mean taking it, and with it the lock of the counter.
     *
     * @param seriesKey the series
     * @param values the values of the record by field API name, in their stored shape
     * @return the prefix, or empty when the record does not give a part the format needs, or has no format
     * @throws NumberSeriesException {@link NumberSeriesException.Reason#UNKNOWN_SERIES} for a key that is not declared
     */
    Optional<String> prefix(String seriesKey, Map<String, Object> values);

    /**
     * The fields the record's format reads that the record does not give as a short code, in the order the format reads
     * them: what an author still has to fill in before the record can be numbered (FA4, ADR-0188).
     *
     * @param seriesKey the series
     * @param values the values of the record by field API name, in their stored shape
     * @return the API names of the fields still needed (empty when the record can be numbered), or empty when the
     *         record has no format at all (its selector has no entry); both are told apart by {@link #declared} and
     *         {@link #prefix}: the first answer is present and empty
     * @throws NumberSeriesException {@link NumberSeriesException.Reason#UNKNOWN_SERIES} for a key that is not declared
     */
    Optional<java.util.List<String>> missingParts(String seriesKey, Map<String, Object> values);

    /**
     * The whole number when the record's format has no counter (it is made of the record's own values, FA4, ADR-0188),
     * so that a clash with a number already given can be found before anything is taken.
     *
     * @param seriesKey the series
     * @param values the values of the record by field API name, in their stored shape
     * @return the number, or empty when the format has a counter, has no format for the record, or a part is missing
     *         or not a code
     * @throws NumberSeriesException {@link NumberSeriesException.Reason#UNKNOWN_SERIES} for a key that is not declared
     */
    Optional<String> known(String seriesKey, Map<String, Object> values);

    /**
     * Takes the next number of the scope the record belongs to. Must run inside a transaction; the counter stays locked
     * until it ends.
     *
     * @param seriesKey the series
     * @param values the values of the record by field API name, in their stored shape
     * @return the number, written in the format of the record's selector
     * @throws NumberSeriesException when the series is unknown, the record has no format, or a part is missing or not a
     * code */
    String next(String seriesKey, Map<String, Object> values);
}
