package app.platform.metadata.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings of the metadata module.
 *
 * @param limits how much an organization may define (a licence or plan will decide this later; until then these are the
 *        bounds that keep one organization from filling the catalogue)
 * @param cache the catalogue cache (ADR-0061)
 */
@ConfigurationProperties("platform.metadata")
record MetadataProperties(@DefaultValue Limits limits, @DefaultValue Cache cache) {

    /**
     * The most an organization may define.
     *
     * @param maxObjects custom objects per organization (default 200)
     * @param maxFieldsPerObject custom fields per object (default 500)
     * @param maxPicklistValues values in one picklist (default 1000)
     * @param maxRecordTypesPerObject record types per object (default 50)
     * @param maxChangesPerSet changes in one change set (default 200)
     * @param maxOpenChangeSets change sets that are still drafts (default 50)
     */
    record Limits(@DefaultValue("200") int maxObjects, @DefaultValue("500") int maxFieldsPerObject,
            @DefaultValue("1000") int maxPicklistValues, @DefaultValue("50") int maxRecordTypesPerObject,
            @DefaultValue("200") int maxChangesPerSet, @DefaultValue("50") int maxOpenChangeSets) {

        Limits {
            if (maxObjects < 1 || maxFieldsPerObject < 1 || maxPicklistValues < 1 || maxRecordTypesPerObject < 1
                    || maxChangesPerSet < 1 || maxOpenChangeSets < 1) {
                throw new IllegalArgumentException("platform.metadata.limits.* must be at least 1");
            }
        }
    }

    /**
     * The catalogue cache. It is a speed-up only: with {@code enabled} false every question is answered from the
     * database and nothing else changes (ADR-0061).
     *
     * @param enabled the kill switch (default on)
     * @param maxOrganizations the most organizations one instance keeps a catalogue for; the least recently used one
     *        goes first (default 1000)
     */
    record Cache(@DefaultValue("true") boolean enabled, @DefaultValue("1000") int maxOrganizations) {

        Cache {
            if (maxOrganizations < 1) {
                throw new IllegalArgumentException("platform.metadata.cache.max-organizations must be at least 1");
            }
        }
    }
}
