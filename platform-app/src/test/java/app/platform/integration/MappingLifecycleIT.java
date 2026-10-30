package app.platform.integration;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestOrganizations.Member;
import app.platform.testsupport.TestOrganizations.Organization;
import app.platform.testsupport.TestSignIn;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;

/**
 * The life of a mapping and who may touch it (Sprint 28, ADR-0217): a draft is checked against the object and does
 * nothing until it is published; what is published never changes; a newer version replaces it and the old one stays in
 * the history; a live mapping keeps its fields from being removed; every endpoint answers allowed, denied, another
 * organization, a platform person and a stranger.
 */
@PlatformIntegrationTest
@Import(TestSecrets.class)
class MappingLifecycleIT extends MappingSupport {

    private FakeService service;

    @BeforeEach
    void startService() throws Exception {
        service = new FakeService();
    }

    @AfterEach
    void stopService() {
        service.close();
    }

    private static Map<String, Object> simple() {
        return definitionOf("EXTERNAL_WINS", field("title", TITLE_FIELD, "BOTH", "TEXT"));
    }

    @Test
    void aDraftIsCheckedAgainstTheObjectAndTheRefusalNamesPlacesNotValues() throws Exception {
        Linked linked = linked(service, "EXTERNAL_WINS");
        Map<String, Object> wrong = definitionOf("EXTERNAL_WINS", field("title", "no_such_field__c", "BOTH", "TEXT"),
                field("title", NOTE_FIELD, "BOTH", "TEXT"));

        Response refused = saveDraft(linked.admin(), linked.connection(), draftOf("item", wrong, 0));

        assertThat(refused.status()).as(refused.body()).isEqualTo(400);
        assertThat(refused.body()).contains("definition.fields[0].platform").contains("definition.fields[1].external")
                .doesNotContain("no_such_field__c");
    }

    @Test
    void aDraftIsStaleWhenSomeoneElseSavedAndNothingGoesLiveUntilItIsPublished() throws Exception {
        Linked linked = linked(service, "EXTERNAL_WINS");
        Response first = saveDraft(linked.admin(), linked.connection(), draftOf("item", simple(), 0));
        assertThat(first.status()).as(first.body()).isEqualTo(200);
        long version = draftVersion(first);

        Response stale = saveDraft(linked.admin(), linked.connection(), draftOf("item", simple(), version + 5));
        Response fresh = saveDraft(linked.admin(), linked.connection(), draftOf("item", simple(), version));

        assertThat(stale.status()).isEqualTo(409);
        assertThat(fresh.status()).as(fresh.body()).isEqualTo(200);
        assertThat(JsonPath.<Integer>read(fresh.body(), "$.data.live.number")).isEqualTo(1);
        assertThat(JsonPath.<Integer>read(fresh.body(), "$.data.draft.number")).isEqualTo(2);
    }

    @Test
    void publishingReplacesTheLiveVersionAndKeepsTheOldOneInTheHistoryUnchanged() throws Exception {
        Linked linked = linked(service, "EXTERNAL_WINS");
        Response before = linked.admin().get(mappingsOf(linked.connection()) + "/" + OBJECT);
        String firstHash = JsonPath.read(before.body(), "$.data.live.hash");
        Response draft = saveDraft(linked.admin(), linked.connection(), draftOf("item", definitionOf("PLATFORM_WINS",
                field("title", TITLE_FIELD, "BOTH", "TEXT"), field("note", NOTE_FIELD, "OUTBOUND", "TEXT")), 0));
        assertThat(publish(linked.admin(), linked.connection(), draftVersion(draft)).status()).isEqualTo(200);

        Response after = linked.admin().get(mappingsOf(linked.connection()) + "/" + OBJECT);

        assertThat(JsonPath.<Integer>read(after.body(), "$.data.live.number")).isEqualTo(2);
        assertThat(JsonPath.<String>read(after.body(), "$.data.live.definition.conflict")).isEqualTo("PLATFORM_WINS");
        assertThat(JsonPath.<Object>read(after.body(), "$.data.draft")).isNull();
        assertThat(JsonPath.<List<String>>read(after.body(), "$.data.history[*].status")).containsExactly("SUPERSEDED");
        assertThat(JsonPath.<String>read(after.body(), "$.data.history[0].hash")).isEqualTo(firstHash);
        assertThat(JsonPath.<String>read(after.body(), "$.data.history[0].definition.conflict"))
                .isEqualTo("EXTERNAL_WINS");
    }

    @Test
    void aLiveMappingKeepsItsFieldsFromBeingRemovedUntilItIsStopped() throws Exception {
        Linked linked = linked(service, "EXTERNAL_WINS");
        String field = "/api/v1/metadata/objects/" + OBJECT + "/fields/" + NOTE_FIELD;

        Response blocked = linked.admin().request("DELETE", field, "");
        assertThat(blocked.status()).as(blocked.body()).isEqualTo(409);
        assertThat(blocked.body()).contains("mapping");

        Response stopped = linked.admin().postJson(mappingsOf(linked.connection()) + "/" + OBJECT + "/stop", "{}");
        assertThat(stopped.status()).as(stopped.body()).isEqualTo(200);
        assertThat(JsonPath.<Object>read(stopped.body(), "$.data.live")).isNull();
        Response afterStop = linked.admin().request("DELETE", field, "");
        assertThat(afterStop.body()).as("no longer a mapping that stops it").doesNotContain("mapping to an external");
    }

    @Test
    void aMappingThatTheScopeOfTheConnectionDoesNotAllowCannotBePublished() throws Exception {
        Linked linked = linked(service, "EXTERNAL_WINS");
        assertThat(disable(linked.admin(), linked.connection()).status()).isEqualTo(200);
        var body = definition("partner-link", service.url());
        body.put("authKind", "BEARER_TOKEN");
        body.put("credentialName", "partner-token");
        body.put("settings", Map.of("entityPath", "/api", "inboundCredential", "partner-signing"));
        body.put("subscriptions", List.of());
        body.put("scope", List.of(Map.of("object", OBJECT, "read", false, "create", false, "update", false)));
        body.put("version", versionOf(linked.admin(), "partner-link"));
        assertThat(change(linked.admin(), "partner-link", body).status()).isEqualTo(200);
        Response draft = saveDraft(linked.admin(), linked.connection(), draftOf("item", simple(), 0));

        Response refused = publish(linked.admin(), linked.connection(), draftVersion(draft));

        assertThat(refused.status()).as(refused.body()).isEqualTo(409);
        assertThat(refused.body()).contains("scope");
    }

    @Test
    void theDryRunWorksOnASampleAndStoresNothing() throws Exception {
        Linked linked = linked(service, "EXTERNAL_WINS");
        String path = mappingsOf(linked.connection()) + "/" + OBJECT + "/dry-run";

        Response out = linked.admin().postJson(path, json(Map.of("entity", "item", "definition", simple(),
                "direction", "OUTBOUND", "values", Map.of(TITLE_FIELD, "sample-out"))));
        Response in = linked.admin().postJson(path, json(Map.of("entity", "item", "definition", simple(),
                "direction", "INBOUND", "values", Map.of("title", "sample-in"))));
        Response broken = linked.admin().postJson(path, json(Map.of("entity", "item", "definition", simple(),
                "direction", "OUTBOUND", "values", Map.of("no_such__c", "x"))));

        assertThat(JsonPath.<String>read(out.body(), "$.data.result.title")).isEqualTo("sample-out");
        assertThat(JsonPath.<String>read(in.body(), "$.data.result." + TITLE_FIELD)).isEqualTo("sample-in");
        assertThat(JsonPath.<Map<String, Object>>read(broken.body(), "$.data.problems")).isNotEmpty();
        assertThat(recordsTitled(linked.admin(), "sample-in")).isEmpty();
        assertThat(service.seen()).as("a dry run calls nobody").hasSizeLessThanOrEqualTo(1);
    }

    // ---- who may call what ----

    @Test
    void everyMappingEndpointIsDecidedByTheAbilitiesAndNeverCrossesOrganizations() throws Exception {
        Linked linked = linked(service, "EXTERNAL_WINS");
        Organization organization = linked.organization();
        Member reader = memberWithAbilities(organization, linked.admin(), "integrations.view", "mappings.view");
        Member nothing = memberWithAbilities(organization, linked.admin(), "integrations.view");
        TestBrowser readOnly = as(organization, reader);
        TestBrowser blind = as(organization, nothing);
        String one = mappingsOf(linked.connection()) + "/" + OBJECT;
        String sample = json(Map.of("entity", "item", "definition", simple(), "direction", "OUTBOUND", "values",
                Map.of()));

        assertThat(readOnly.get(mappingsOf(linked.connection())).status()).isEqualTo(200);
        assertThat(readOnly.get(one).status()).isEqualTo(200);
        assertThat(readOnly.get(ROOT + "/" + linked.connection() + "/deliveries").status()).isEqualTo(200);
        assertThat(readOnly.request("PUT", one, json(draftOf("item", simple(), 0))).status()).isEqualTo(403);
        assertThat(readOnly.postJson(one + "/publish", "{\"version\":0}").status()).isEqualTo(403);
        assertThat(readOnly.postJson(one + "/stop", "{}").status()).isEqualTo(403);
        assertThat(readOnly.postJson(one + "/dry-run", sample).status()).isEqualTo(403);
        assertThat(readOnly.request("DELETE", one + "/draft?version=0", "").status()).isEqualTo(403);

        assertThat(blind.get(mappingsOf(linked.connection())).status()).isEqualTo(403);
        assertThat(blind.get(one).status()).isEqualTo(403);
        assertThat(blind.get(ROOT + "/" + linked.connection() + "/deliveries").status()).isEqualTo(403);

        TestBrowser stranger = adminOf(organization());
        assertThat(stranger.get(mappingsOf(linked.connection())).status()).isEqualTo(404);
        assertThat(stranger.get(one).status()).isEqualTo(404);
        assertThat(stranger.request("PUT", one, json(draftOf("item", simple(), 0))).status()).isEqualTo(404);
        assertThat(stranger.get(ROOT + "/" + linked.connection() + "/deliveries").status()).isEqualTo(404);

        TestBrowser anonymous = new TestBrowser(port, organization.host());
        assertThat(anonymous.get(mappingsOf(linked.connection())).status()).isEqualTo(401);
        assertThat(anonymous.request("PUT", one, json(draftOf("item", simple(), 0))).status()).isEqualTo(401);

        TestBrowser platform = new TestBrowser(port, TestSignIn.PLATFORM_HOST);
        assertThat(platform.get(mappingsOf(linked.connection())).status()).isIn(401, 404);
    }

    @Test
    void anOrganizationWithoutThePlanFeatureCannotUseMappings() throws Exception {
        Organization without = organizationWithoutTheFeature();
        TestBrowser admin = adminOf(without);

        Response refused = admin.get(ROOT + "/any-link/mappings");

        assertThat(refused.status()).isIn(404, 409);
    }
}
