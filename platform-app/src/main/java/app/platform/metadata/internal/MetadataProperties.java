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
     * @param maxLayoutsPerObject page layouts per object (default 50)
     * @param maxRecordPagesPerObject record pages per object (default 20)
     * @param maxApplications applications per organization (default 20)
     * @param maxAssignmentsPerObject layout assignments per object (default 200)
     * @param maxValidationRulesPerObject validation rules per object (default 50, Sprint 13)
     * @param maxFormulaDepth how many formula fields may be stacked, a formula using a formula using a formula
     *        (default 5, Sprint 13)
     * @param maxRecordTypeAssignmentsPerObject record type assignments (a profile allowed a record type) per object
     *        (default 500, Sprint 15A)
     * @param maxSearchableFieldsPerObject fields flagged searchable per object (default 4, Sprint 17)
     * @param maxTabs tabs per organization (default 200, Sprint 18)
     * @param maxCustomPages custom pages per organization (default 50, Sprint 18)
     * @param maxCustomPageAssets uploaded scripts per organization (default 200, Sprint 18)
     * @param maxCustomPageBytes the largest script, in bytes (default 1,000,000, Sprint 18; the database allows up to
     *        5,000,000 whatever this says)
     * @param maxRecordPageAssignmentsPerObject record page assignments per object (default 200, Sprint 20)
     * @param maxWorkflowsPerObject workflows per object (default 50, Sprint 22)
     * @param maxWorkflowVersions definitions one workflow may have been through (default 200, Sprint 22)
     */
    record Limits(@DefaultValue("200") int maxObjects, @DefaultValue("500") int maxFieldsPerObject,
            @DefaultValue("1000") int maxPicklistValues, @DefaultValue("50") int maxRecordTypesPerObject,
            @DefaultValue("200") int maxChangesPerSet, @DefaultValue("50") int maxOpenChangeSets,
            @DefaultValue("50") int maxLayoutsPerObject, @DefaultValue("20") int maxRecordPagesPerObject,
            @DefaultValue("20") int maxApplications, @DefaultValue("200") int maxAssignmentsPerObject,
            @DefaultValue("50") int maxValidationRulesPerObject, @DefaultValue("5") int maxFormulaDepth,
            @DefaultValue("500") int maxRecordTypeAssignmentsPerObject,
            @DefaultValue("4") int maxSearchableFieldsPerObject, @DefaultValue("200") int maxTabs,
            @DefaultValue("50") int maxCustomPages, @DefaultValue("200") int maxCustomPageAssets,
            @DefaultValue("1000000") int maxCustomPageBytes,
            @DefaultValue("200") int maxRecordPageAssignmentsPerObject, @DefaultValue("50") int maxWorkflowsPerObject,
            @DefaultValue("200") int maxWorkflowVersions) {

        Limits {
            if (maxObjects < 1 || maxFieldsPerObject < 1 || maxPicklistValues < 1 || maxRecordTypesPerObject < 1
                    || maxChangesPerSet < 1 || maxOpenChangeSets < 1 || maxLayoutsPerObject < 1
                    || maxRecordPagesPerObject < 1 || maxApplications < 1 || maxAssignmentsPerObject < 1
                    || maxValidationRulesPerObject < 1 || maxFormulaDepth < 1
                    || maxRecordTypeAssignmentsPerObject < 1 || maxSearchableFieldsPerObject < 1 || maxTabs < 1
                    || maxCustomPages < 1 || maxCustomPageAssets < 1 || maxCustomPageBytes < 1
                    || maxCustomPageBytes > 5_000_000 || maxRecordPageAssignmentsPerObject < 1
                    || maxWorkflowsPerObject < 1 || maxWorkflowVersions < 1) {
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
