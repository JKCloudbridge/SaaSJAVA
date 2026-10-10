package app.platform.metadata.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.identity.Users;
import app.platform.licensing.Plans;
import app.platform.licensing.Subscriptions;
import app.platform.metadata.FieldDefinition;
import app.platform.metadata.Metadata;
import app.platform.metadata.ObjectDefinition;
import app.platform.security.ObjectCatalog;
import app.platform.tenant.TenantContext;
import app.platform.tenant.TenantContexts;
import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestApplication;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestOrganizations;
import app.platform.testsupport.TestOrganizations.Organization;
import app.platform.testsupport.TestPlatform;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * The catalogue cache against a real database and three real application instances (ADR-0061): instance A (the test's
 * own, cache on), instance B (cache on) and instance C (cache off). Every change of a definition is made through
 * instance A's API and must show on the very next question on every instance, and the three instances must give the
 * same answer at every step: the cache is only a speed-up, and a cache that is off changes nothing but the speed.
 */
@PlatformIntegrationTest
class MetadataCacheIT {

    private static final String OBJECTS = "/api/v1/metadata/objects";

    private static TestApplication instanceB;
    private static TestApplication instanceC;

    @LocalServerPort
    private int port;

    @Autowired
    private Users users;

    @Autowired
    private Plans plans;

    @Autowired
    private Subscriptions subscriptions;

    @Autowired
    private Metadata metadataA;

    @Autowired
    private ObjectCatalog catalogueA;

    @Autowired
    private TenantContexts contextsA;

    @Autowired
    private MetadataCache cacheA;

    @BeforeAll
    static void startTheOtherInstances() {
        instanceB = TestApplication.start();
        instanceC = TestApplication.start("platform.metadata.cache.enabled=false");
    }

    @AfterAll
    static void stopTheOtherInstances() {
        instanceC.close();
        instanceB.close();
    }

    /** The custom objects and their fields as one instance sees them, and the catalogue the security module reads. */
    private static Map<String, List<String>> customObjects(TenantContexts contexts, Metadata metadata,
            ObjectCatalog catalogue, Organization organization) {
        return contexts.call(TenantContext.of(organization.id()), () -> {
            Map<String, List<String>> seen = new TreeMap<>();
            for (ObjectDefinition object : metadata.objects()) {
                if (object.isCustom() && !object.apiName().startsWith("Object")) {
                    seen.put(object.apiName(), object.fields().stream().map(FieldDefinition::apiName)
                            .filter(name -> name.endsWith("__c")).toList());
                    assertThat(catalogue.object(object.apiName())).as("the security module sees " + object.apiName())
                            .isPresent();
                }
            }
            return seen;
        });
    }

    private void expect(Organization organization, String step, Map<String, List<String>> expected) {
        for (int round = 0; round < 2; round++) {
            assertThat(customObjects(contextsA, metadataA, catalogueA, organization))
                    .as(step + ": instance A, cache on").isEqualTo(expected);
            assertThat(customObjects(instanceB.bean(TenantContexts.class), instanceB.bean(Metadata.class),
                    instanceB.bean(ObjectCatalog.class), organization)).as(step + ": instance B, cache on")
                    .isEqualTo(expected);
            assertThat(customObjects(instanceC.bean(TenantContexts.class), instanceC.bean(Metadata.class),
                    instanceC.bean(ObjectCatalog.class), organization)).as(step + ": instance C, cache off")
                    .isEqualTo(expected);
        }
    }

    private static void ok(Response response) {
        assertThat(response.status()).as(response.body()).isBetween(200, 204);
    }

    @Test
    void everyChangeShowsOnTheNextQuestionOnEveryInstanceAndTheCacheIsUsed() {
        Organization organization = TestOrganizations.create(users);
        TestPlatform.subscribe(plans, subscriptions, organization.id(), 5, 3);
        TestBrowser admin = TestOrganizations.signedIn(port, organization.host(), organization.admin().person());
        expect(organization, "at the start", Map.of());
        long hitsBefore = cacheA.hitCount();

        ok(admin.postJson(OBJECTS, "{\"name\":\"Garden\",\"label\":\"Garden\",\"pluralLabel\":\"Gardens\"}"));
        expect(organization, "an object was added", Map.of("Garden__c", List.of()));

        ok(admin.postJson(OBJECTS + "/Garden__c/fields",
                "{\"name\":\"size\",\"label\":\"Size\",\"type\":\"NUMBER\"}"));
        expect(organization, "a field was added", Map.of("Garden__c", List.of("size__c")));

        ok(admin.request("PUT", OBJECTS + "/Garden__c", "{\"label\":\"Plot\",\"pluralLabel\":\"Plots\","
                + "\"description\":\"\",\"version\":0}"));
        expect(organization, "an object was renamed", Map.of("Garden__c", List.of("size__c")));
        assertThat(contextsA.call(TenantContext.of(organization.id()),
                () -> metadataA.object("Garden__c").orElseThrow().label())).isEqualTo("Plot");

        ok(admin.request("DELETE", OBJECTS + "/Garden__c/fields/size__c", null));
        expect(organization, "a field was removed", Map.of("Garden__c", List.of()));

        ok(admin.request("DELETE", OBJECTS + "/Garden__c", null));
        expect(organization, "an object was removed", Map.of());

        assertThat(cacheA.hitCount()).as("the cache served answers between the changes").isGreaterThan(hitsBefore);
    }

    @Test
    void anotherOrganizationsCatalogueIsNeverServed() {
        Organization first = TestOrganizations.create(users);
        Organization second = TestOrganizations.create(users);
        TestPlatform.subscribe(plans, subscriptions, first.id(), 5, 3);
        TestPlatform.subscribe(plans, subscriptions, second.id(), 5, 3);
        TestBrowser firstAdmin = TestOrganizations.signedIn(port, first.host(), first.admin().person());

        ok(firstAdmin.postJson(OBJECTS, "{\"name\":\"Hangar\",\"label\":\"Hangar\",\"pluralLabel\":\"Hangars\"}"));

        expect(first, "the organization that made it", Map.of("Hangar__c", List.of()));
        expect(second, "another organization", Map.of());
    }
}
