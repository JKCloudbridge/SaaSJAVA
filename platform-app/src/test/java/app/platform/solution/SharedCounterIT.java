package app.platform.solution;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.data.NumberSeries;
import app.platform.tenant.TenantContext;
import app.platform.tenant.TenantContexts;
import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestOrganizations.Organization;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The series of Management Core draws its counters from the series of the Document counter (MC1, ADR-0257): a form and
 * a document of the same combination never get the same number, and the count goes on from whoever took the last one.
 * Real PostgreSQL; the series are the shipped ones.
 */
@PlatformIntegrationTest
class SharedCounterIT extends SolutionSupport {

    @Autowired
    private NumberSeries series;

    @Autowired
    private TenantContexts contexts;

    @Autowired
    private PlatformTransactionManager transactions;

    private <T> T in(Organization organization, Supplier<T> work) {
        return contexts.call(TenantContext.of(organization.id()),
                () -> new TransactionTemplate(transactions).execute(status -> work.get()));
    }

    private String next(Organization organization, String key, String category, String partA) {
        return in(organization, () -> series.next(key, Map.of("category", category, "partA", partA)));
    }

    @Test
    void aFormAndADocumentOfTheSameCombinationShareOneCountWhicheverTookTheLastNumber() {
        Organization organization = organizationWithFeature();

        assertThat(next(organization, "document", "WI", "PRD")).isEqualTo("WI/PRD/01");
        assertThat(next(organization, "management-document", "WI", "PRD")).isEqualTo("WI/PRD/02");
        assertThat(next(organization, "document", "WI", "PRD")).isEqualTo("WI/PRD/03");
        assertThat(next(organization, "management-document", "WI", "PRD")).isEqualTo("WI/PRD/04");
    }

    @Test
    void anotherCombinationAndAnotherOrganizationCountOnTheirOwn() {
        Organization one = organizationWithFeature();
        Organization other = organizationWithFeature();

        assertThat(next(one, "management-document", "SOP", "PRD")).isEqualTo("SOP/PRD/01");
        assertThat(next(one, "management-document", "SOP", "QLT")).isEqualTo("SOP/QLT/01");
        assertThat(next(other, "document", "SOP", "PRD")).as("another organization has its own counters")
                .isEqualTo("SOP/PRD/01");
    }
}
