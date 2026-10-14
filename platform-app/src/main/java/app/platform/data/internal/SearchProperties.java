package app.platform.data.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings of the search over the records of an object (Sprint 17, ADR-0124).
 *
 * @param maxCandidates the most records the rarest word of a search may have for the search to be answered (default
 *        50000): a search whose every word is that common is refused with the advice to type more, so no search costs
 *        more than a bounded amount
 * @param maxQueryWords the most words of a search that are looked at (default 5)
 * @param fillBatchSize how many records one batch of the fill of a newly searchable field looks at (default 500)
 */
@ConfigurationProperties("platform.data.search")
record SearchProperties(@DefaultValue("50000") int maxCandidates, @DefaultValue("5") int maxQueryWords,
        @DefaultValue("500") int fillBatchSize) {

    SearchProperties {
        if (maxCandidates < 1 || maxCandidates > 1_000_000 || maxQueryWords < 1 || maxQueryWords > 10
                || fillBatchSize < 1 || fillBatchSize > 5_000) {
            throw new IllegalArgumentException("platform.data.search: a setting is outside its range");
        }
    }
}
