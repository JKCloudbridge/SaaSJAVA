package app.platform.solution;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.identity.Users;
import app.platform.licensing.Entitlements;
import app.platform.licensing.Plans;
import app.platform.licensing.Subscriptions;
import app.platform.sharedkernel.ActorId;
import app.platform.sharedkernel.TenantId;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestOrganizations;
import app.platform.testsupport.TestOrganizations.Member;
import app.platform.testsupport.TestOrganizations.Organization;
import app.platform.testsupport.TestPlatform;
import app.platform.testsupport.tenancy.TenantFixtures;
import com.jayway.jsonpath.JsonPath;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * What the integration tests of the shipped solution share (Functional track FA1): an organization with its
 * administrator on a plan that has no feature, short ways to give it the plan feature of the Audit maintenance
 * solution and take it away again (the platform administrator's switch for that organization), and members who hold
 * one of the policies the solution ships. The words of the solution are in these tests because they test the solution
 * that ships; the generic code under test has none of them.
 */
public abstract class SolutionSupport {

    protected static final String FEATURE = "inspection";
    protected static final String APPLICATION = "inspection";
    protected static final String OBJECT = "InspectionTemplate";
    protected static final String MY_APPLICATIONS = "/api/v1/applications";
    protected static final String POLICIES = "/api/v1/access-policies";
    protected static final String METADATA_OBJECTS = "/api/v1/metadata/objects";
    protected static final String RECORDS = "/api/v1/objects/" + OBJECT + "/records";
    protected static final String MEMBERS = "/api/v1/members";
    protected static final List<String> SHIPPED_POLICIES = List.of("Author", "Approver", "Reader", "Filler",
            "Department setup");

    @LocalServerPort
    protected int port;

    @Autowired
    protected Users users;

    @Autowired
    protected Plans plans;

    @Autowired
    protected Subscriptions subscriptions;

    @Autowired
    protected Entitlements entitlements;

    /** An organization on a plan that has no feature at all. */
    protected Organization organization() {
        Organization organization = TestOrganizations.create(users);
        TestPlatform.subscribe(plans, subscriptions, organization.id(), 8, 3);
        return organization;
    }

    /** An organization whose plan has the feature. */
    protected Organization organizationWithFeature() {
        Organization organization = TestOrganizations.create(users);
        TestPlatform.subscribe(plans, subscriptions, organization.id(), 8, 3, FEATURE);
        return organization;
    }

    protected TestBrowser adminOf(Organization organization) {
        return TestOrganizations.signedIn(port, organization.host(), organization.admin().person());
    }

    protected TestBrowser as(Organization organization, Member member) {
        return TestOrganizations.signedIn(port, organization.host(), member.person());
    }

    /** The platform administrator switches the feature on for the organization, over its plan. */
    protected void switchOn(Organization organization) {
        entitlements.override(organization.id(), FEATURE, true, ActorId.SYSTEM);
    }

    /** The platform administrator switches the feature off for the organization, over its plan. */
    protected void switchOff(Organization organization) {
        entitlements.override(organization.id(), FEATURE, false, ActorId.SYSTEM);
    }

    /** A new member with the default profile and no policy. */
    protected Member plainMember(Organization organization) {
        return TestOrganizations.join(users, organization.tenant(), false);
    }

    /** The identifier of the shipped policy with the name, as the administrator's list shows it. */
    protected static String policyId(TestBrowser admin, String name) {
        Response list = admin.get(POLICIES);
        assertThat(list.status()).as(list.body()).isEqualTo(200);
        List<String> ids = JsonPath.read(list.body(), "$.data[?(@.name=='" + name + "')].id");
        assertThat(ids).as("the policy " + name).hasSize(1);
        return ids.get(0);
    }

    /** Gives the member the shipped policy. */
    protected static void give(TestBrowser admin, Member member, String policyId) {
        Response given = admin.postJson(MEMBERS + "/" + member.membership() + "/policies",
                "{\"policyId\":\"" + policyId + "\"}");
        assertThat(given.status()).as(given.body()).isIn(200, 204);
    }

    /**
     * A member who holds the shipped policy of the name. The first request of the administrator is what makes the
     * policies exist in the organization, as the first request of any member would.
     */
    protected Member memberHolding(Organization organization, TestBrowser admin, String policyName) {
        assertThat(admin.get(MY_APPLICATIONS).status()).isEqualTo(200);
        Member member = plainMember(organization);
        give(admin, member, policyId(admin, policyName));
        return member;
    }

    /** The API names of the applications the browser's member is shown. */
    protected static List<String> applicationsOf(TestBrowser browser) {
        Response mine = browser.get(MY_APPLICATIONS);
        assertThat(mine.status()).as(mine.body()).isEqualTo(200);
        return JsonPath.read(mine.body(), "$.data[*].apiName");
    }

    /** How many rows of the tables an organization's own applications and pages live in the organization has. */
    protected static long applicationRows(TenantId tenant) {
        try {
            return TenantFixtures.asTenant(tenant, connection -> {
                long total = 0;
                for (String table : List.of("application", "tab", "custom_page", "custom_page_asset", "page_layout",
                        "record_page", "layout_assignment", "record_page_assignment")) {
                    try (var statement = connection.prepareStatement("select count(*) from " + table);
                            var rs = statement.executeQuery()) {
                        rs.next();
                        total += rs.getLong(1);
                    }
                }
                return total;
            });
        } catch (SQLException e) {
            throw new IllegalStateException("Could not count the rows of an organization", e);
        }
    }

    protected static String newName() {
        return "name-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
