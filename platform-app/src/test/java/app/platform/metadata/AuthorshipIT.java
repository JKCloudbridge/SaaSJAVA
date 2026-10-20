package app.platform.metadata;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestOrganizations.Member;
import app.platform.testsupport.TestOrganizations.Organization;
import app.platform.testsupport.TestUsers;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Who made and who last changed a definition (UA3, ADR-0169) through the real application: the audit columns every
 * definition row carries are read back, named for active members only, and kept to the organization of the host.
 */
@PlatformIntegrationTest
class AuthorshipIT extends DefinitionsSupport {

    private static String authorship(TestBrowser browser, String object) {
        Response response = browser.get(OBJECTS + "/" + object + "/authorship");
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        return response.body();
    }

    @Test
    void everyDefinitionOfTheOrganizationIsListedWithWhoMadeItAndWhen() {
        TestBrowser admin = adminWithDealAndContact(organization());
        layoutExists(admin, "Deal__c", "Details", DEAL_LAYOUT);

        String body = authorship(admin, "Deal__c");

        assertThat(JsonPath.<List<String>>read(body, "$.data[*].kind")).contains("FIELD", "LAYOUT");
        assertThat(JsonPath.<List<String>>read(body, "$.data[?(@.kind=='LAYOUT')].apiName"))
                .containsExactly("Details__c");
        assertThat(JsonPath.<List<String>>read(body, "$.data[?(@.kind=='LAYOUT')].createdBy").get(0)).isNotBlank();
        assertThat(JsonPath.<List<String>>read(body, "$.data[?(@.kind=='LAYOUT')].createdAt").get(0)).isNotBlank();
        assertThat(JsonPath.<List<String>>read(body, "$.data[?(@.kind=='LAYOUT')].updatedBy").get(0)).isNotBlank();
    }

    @Test
    void theNameIsTheMemberWhoDidItNotTheOneWhoAskedAboutIt() {
        Organization organization = organization();
        TestBrowser admin = adminWithDealAndContact(organization);
        layoutExists(admin, "Deal__c", "ByAdmin", DEAL_LAYOUT);
        Member maker = memberWith(organization, admin, TestUsers.create(users, "Member A"), "metadata.view",
                "metadata.manage", "metadata.publish");
        TestBrowser person = as(organization, maker);
        Response made = createLayout(person, "Deal__c", "ByMember", DEAL_LAYOUT);
        assertThat(made.status()).as(made.body()).isEqualTo(201);

        String body = authorship(admin, "Deal__c");

        String byAdmin = JsonPath.<List<String>>read(body, "$.data[?(@.apiName=='ByAdmin__c')].createdBy").get(0);
        String byMember = JsonPath.<List<String>>read(body, "$.data[?(@.apiName=='ByMember__c')].createdBy").get(0);
        assertThat(byAdmin).isEqualTo("Test User");
        assertThat(byMember).isEqualTo("Member A");
    }

    @Test
    void theAnswerCarriesNoIdentifierOrAddress() {
        TestBrowser admin = adminWithDealAndContact(organization());

        String body = authorship(admin, "Deal__c");

        assertThat(body).doesNotContain("@").doesNotContainPattern("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-");
    }

    @Test
    void anObjectOfAnotherOrganizationDoesNotExistForThisOne() {
        TestBrowser admin = adminWithDealAndContact(organization());
        TestBrowser other = adminOf(organization());

        Response response = other.get(OBJECTS + "/Deal__c/authorship");

        assertThat(response.status()).isEqualTo(404);
        assertThat(admin.get(OBJECTS + "/Deal__c/authorship").status()).isEqualTo(200);
    }

    @Test
    void aStandardObjectAnswersWithoutAnEntryForWhatThePlatformDefines() {
        TestBrowser admin = adminOf(organization());

        String body = authorship(admin, "Account");

        assertThat(JsonPath.<List<String>>read(body, "$.data[?(@.kind=='OBJECT')].apiName")).isEmpty();
    }
}
