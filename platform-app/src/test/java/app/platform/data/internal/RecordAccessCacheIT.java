package app.platform.data.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.security.RecordAccess;
import app.platform.security.RecordSecurity;
import app.platform.tenant.TenantContext;
import app.platform.tenant.TenantContexts;
import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestApplication;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestOrganizations.Organization;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The record access of a member against three real application instances (ADR-0115, ADR-0053): instance A (the test's
 * own, cache on), instance B (cache on) and instance C (cache off). How records are shared is changed through
 * instance A's API; the very next question on every instance gives the answer an instance without a cache gives.
 */
@PlatformIntegrationTest
class RecordAccessCacheIT extends RecordSecuritySupport {

    private static TestApplication instanceB;
    private static TestApplication instanceC;

    @Autowired
    private RecordSecurity securityA;

    @Autowired
    private TenantContexts contextsA;

    @BeforeAll
    static void startTheOtherInstances() {
        instanceB = TestApplication.start();
        instanceC = TestApplication.start("platform.security.cache.enabled=false");
    }

    @AfterAll
    static void stopTheOtherInstances() {
        instanceC.close();
        instanceB.close();
    }

    private static RecordAccess ask(TenantContexts contexts, RecordSecurity security, Organization organization,
            UUID user, UUID membership) {
        return contexts.call(new TenantContext(organization.id(), user, membership),
                () -> security.accessTo(membership, "Item__c"));
    }

    /** The answers of the three instances, which must all be the same, and the answer. */
    private RecordAccess agreed(Organization organization, Person person) {
        UUID user = person.member().person().user().id();
        UUID membership = person.member().membership();
        RecordAccess a = ask(contextsA, securityA, organization, user, membership);
        RecordAccess b = ask(instanceB.bean(TenantContexts.class), instanceB.bean(RecordSecurity.class), organization,
                user, membership);
        RecordAccess c = ask(instanceC.bean(TenantContexts.class), instanceC.bean(RecordSecurity.class), organization,
                user, membership);
        assertThat(b).as("instance B").isEqualTo(a);
        assertThat(c).as("instance C, without a cache").isEqualTo(a);
        return a;
    }

    private String ownerRuleBody(Items items, Person owner, String group) {
        String role = role(items.admin(), "Owners", null);
        putInRole(items.admin(), owner, role);
        return "{\"object\":\"Item__c\",\"name\":\"by owner\",\"kind\":\"OWNER\",\"sourceType\":\"ROLE\","
                + "\"sourceId\":\"" + role + "\",\"targetType\":\"GROUP\",\"targetId\":\"" + group
                + "\",\"accessLevel\":\"READ\"}";
    }

    @Test
    void aChangeOfTheDefaultTheHierarchyOrARuleShowsAtTheNextQuestionOnEveryInstance() {
        Items items = items();
        Person owner = worker(items.organization(), items.admin());
        Person other = worker(items.organization(), items.admin());
        RecordAccess everything = agreed(items.organization(), other);
        assertThat(everything.read()).isNotNull();

        share(items.admin(), "PRIVATE", false);
        RecordAccess privateAccess = agreed(items.organization(), other);
        share(items.admin(), "PRIVATE", true);
        RecordAccess hierarchy = agreed(items.organization(), other);
        String group = group(items.admin(), "readers", other);
        String rule = rule(items.admin(), ownerRuleBody(items, owner, group));
        RecordAccess withRule = agreed(items.organization(), other);

        assertThat(privateAccess).isNotEqualTo(everything);
        assertThat(withRule).as("the rule " + rule + " is seen at once").isNotEqualTo(privateAccess);
        assertThat(hierarchy).isNotNull();
    }

    @Test
    void takingTheRuleAwayIsSeenAtOnceToo() {
        Items items = items();
        Person owner = worker(items.organization(), items.admin());
        Person other = worker(items.organization(), items.admin());
        share(items.admin(), "PRIVATE", false);
        String group = group(items.admin(), "readers", other);
        String rule = rule(items.admin(), ownerRuleBody(items, owner, group));
        RecordAccess with = agreed(items.organization(), other);

        Response removed = items.admin().request("DELETE", SHARING + "/rules/" + rule, null);
        RecordAccess without = agreed(items.organization(), other);

        assertThat(removed.status()).as(removed.body()).isEqualTo(204);
        assertThat(without).isNotEqualTo(with);
    }
}
