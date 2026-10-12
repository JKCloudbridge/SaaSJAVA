package app.platform.metadata;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.identity.PlatformRole;
import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestOrganizations.Organization;
import app.platform.testsupport.TestPlatform;
import app.platform.testsupport.TestUsers;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Who may do what with validation rules, formulas, and the check and try-it endpoints (Sprint 13, ADR-0068, ADR-0079):
 * the usual tests of every endpoint (allowed, denied, cross-tenant, forged tenant header, platform administrator
 * refused, unauthenticated) and the split between viewing, trying (view and either manage or publish) and changing
 * (manage and publish).
 */
@PlatformIntegrationTest
class ExpressionAccessIT extends ExpressionTestSupport {

    /** One call to an endpoint. */
    private record Call(String method, String path, String body) {
    }

    private static final String RULE_BODY = ruleRequest("Odd", "quantity__c < 0", "Message");

    /** The endpoints that change a rule or a formula: they need manage and publish. */
    private static final List<Call> CHANGES = List.of(
            new Call("POST", RULES, RULE_BODY),
            new Call("PUT", RULES + "/Odd__c", ruleUpdate("Odd", "quantity__c < 1", "Message", true, 0)),
            new Call("DELETE", RULES + "/Odd__c", null));

    /** The endpoints that try something: they need view and either manage or publish. */
    private static final List<Call> TRIES = List.of(
            new Call("POST", EXPRESSIONS + "/check", check("quantity__c", "FORMULA", null)),
            new Call("POST", EXPRESSIONS + "/test", tryOut("quantity__c", "FORMULA", values())),
            new Call("POST", RULES + "/Odd__c/test", values()),
            new Call("POST", ORDER + "/fields/total__c/test", values()));

    /** The endpoints that only read: they need view. */
    private static final List<Call> READS = List.of(
            new Call("GET", RULES, null),
            new Call("GET", RULES + "/Odd__c", null),
            new Call("GET", EXPRESSIONS + "/functions", null),
            new Call("GET", FORMULA_REPORT, null));

    private static final List<Call> EVERY_ENDPOINT = Stream.of(READS, TRIES, CHANGES).flatMap(List::stream).toList();

    // ---- denied ----

    @Test
    void aMemberWithoutTheAbilitiesIsRefusedOnEveryEndpoint() {
        Organization organization = organization();
        TestBrowser admin = adminWithOrder(organization);
        TestBrowser person = as(organization, memberWith(organization, admin));

        for (Call call : EVERY_ENDPOINT) {
            Response refused = person.request(call.method(), call.path(), call.body());
            assertThat(refused.status()).as(call + " " + refused.body()).isEqualTo(403);
            assertThat(JsonPath.<String>read(refused.body(), "$.error.code")).isEqualTo("FORBIDDEN");
        }
    }

    @Test
    void aViewerReadsButNeitherTriesNorChanges() {
        Organization organization = organization();
        TestBrowser admin = adminWithOrder(organization);
        TestBrowser viewer = as(organization, memberWith(organization, admin, "metadata.view"));

        for (Call call : READS) {
            assertThat(viewer.request(call.method(), call.path(), call.body()).status()).as(call.toString())
                    .isIn(200, 404);
        }
        for (Call call : Stream.concat(TRIES.stream(), CHANGES.stream()).toList()) {
            assertThat(viewer.request(call.method(), call.path(), call.body()).status()).as(call.toString())
                    .isEqualTo(403);
        }
    }

    @Test
    void aMemberWhoMayDraftOrPublishMayTryButNotChangeAtOnce() {
        Organization organization = organization();
        TestBrowser admin = adminWithOrder(organization);
        ruleExists(admin, "Odd", "quantity__c < 0", "Message");
        formulaExists(admin, "total", "quantity__c * 2", "NUMBER");
        TestBrowser drafter = as(organization, memberWith(organization, admin, "metadata.view", "metadata.manage"));
        TestBrowser publisher = as(organization, memberWith(organization, admin, "metadata.view",
                "metadata.publish"));

        for (TestBrowser person : List.of(drafter, publisher)) {
            for (Call call : TRIES) {
                assertThat(person.request(call.method(), call.path(), call.body()).status()).as(call.toString())
                        .isEqualTo(200);
            }
            for (Call call : CHANGES) {
                assertThat(person.request(call.method(), call.path(), call.body()).status()).as(call.toString())
                        .isEqualTo(403);
            }
        }
        assertThat(ruleNames(admin)).containsExactly("Odd__c");
    }

    @Test
    void anAdministratorMayDoEverything() {
        Organization organization = organization();
        TestBrowser admin = adminWithOrder(organization);

        Response created = admin.postJson(RULES, RULE_BODY);
        Response tried = admin.postJson(RULES + "/Odd__c/test", values("quantity__c", "-1"));
        Response checked = admin.postJson(EXPRESSIONS + "/check", check("quantity__c", "FORMULA", null));

        assertThat(created.status()).as(created.body()).isEqualTo(201);
        assertThat(tried.status()).as(tried.body()).isEqualTo(200);
        assertThat(checked.status()).as(checked.body()).isEqualTo(200);
    }

    @Test
    void aRefusedMemberIsRecordedWithTheMissingAbility() {
        Organization organization = organization();
        TestBrowser admin = adminWithOrder(organization);
        TestBrowser viewer = as(organization, memberWith(organization, admin, "metadata.view"));

        viewer.postJson(EXPRESSIONS + "/check", check("quantity__c", "FORMULA", null));
        viewer.postJson(RULES, RULE_BODY);

        String refused = admin.get("/api/v1/audit-events?kind=membership.action.refused").body();
        assertThat(refused).contains("metadata.expression.check").contains("metadata.validationrule.create");
    }

    // ---- cross-tenant, forged header ----

    @Test
    void anotherOrganizationNeverSeesTriesOrChangesTheRulesAndFormulasOfThisOne() {
        Organization mine = organization();
        Organization theirs = organization();
        TestBrowser admin = adminWithOrder(mine);
        TestBrowser other = adminWithOrder(theirs);
        ruleExists(admin, "Odd", "quantity__c < 0", "Message");
        formulaExists(admin, "total", "quantity__c * 2", "NUMBER");

        for (String path : List.of(RULES + "/Odd__c")) {
            assertThat(other.get(path).status()).as(path).isEqualTo(404);
            assertThat(other.request("DELETE", path, null).status()).as(path).isEqualTo(404);
            assertThat(other.request("PUT", path, ruleUpdate("Odd", "TRUE", "M", true, 0)).status()).isEqualTo(404);
        }
        assertThat(other.postJson(RULES + "/Odd__c/test", values()).status()).isEqualTo(404);
        assertThat(other.postJson(ORDER + "/fields/total__c/test", values()).status()).isEqualTo(404);
        assertThat(ruleNames(other)).isEmpty();
        assertThat(JsonPath.<List<String>>read(other.get(FORMULA_REPORT).body(), "$.data[*].fieldApiName")).isEmpty();
        assertThat(ruleNames(admin)).containsExactly("Odd__c");
    }

    @Test
    void aForgedTenantHeaderChangesNothing() {
        Organization mine = organization();
        Organization theirs = organization();
        TestBrowser admin = adminWithOrder(mine);
        TestBrowser other = adminWithOrder(theirs);
        ruleExists(other, "Secret", "quantity__c < 0", "Message");
        String[] forged = {"X-Tenant-Id", theirs.id().value().toString(), "X-Forwarded-Host", theirs.host(),
            "X-Organization", theirs.tenant().slug()};

        Response plain = admin.get(RULES);
        Response withHeaders = admin.get(RULES, forged);
        Response create = admin.request("POST", RULES, ruleRequest("Forged", "quantity__c < 0", "Message"), forged);

        assertThat(withHeaders.body()).isEqualTo(plain.body()).doesNotContain("Secret");
        assertThat(create.status()).isEqualTo(201);
        assertThat(ruleNames(other)).doesNotContain("Forged__c");
        assertThat(ruleNames(admin)).containsExactly("Forged__c");
    }

    // ---- platform administrator, unauthenticated ----

    @Test
    void aPlatformAdministratorIsRefusedOnEveryEndpoint() {
        Organization organization = organization();
        adminWithOrder(organization);
        TestUsers.TestUser person = TestPlatform.person(users, PlatformRole.PLATFORM_ADMIN);
        TestBrowser platformAdmin = TestPlatform.signedIn(port, person);

        for (Call call : EVERY_ENDPOINT) {
            Response refused = platformAdmin.request(call.method(), call.path(), call.body());
            assertThat(refused.status()).as(call + " " + refused.body()).isEqualTo(404);
        }
    }

    @Test
    void anUnauthenticatedCallerIsRefusedOnEveryEndpoint() {
        Organization organization = organization();
        adminWithOrder(organization);
        TestBrowser nobody = new TestBrowser(port, organization.host());

        for (Call call : EVERY_ENDPOINT) {
            assertThat(nobody.request(call.method(), call.path(), call.body()).status()).as(call.toString())
                    .isEqualTo(401);
        }
    }
}
