package app.platform.data;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestOrganizations;
import app.platform.testsupport.TestOrganizations.Member;
import app.platform.testsupport.TestOrganizations.Organization;
import com.jayway.jsonpath.JsonPath;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * A lookup to a user (Sprint 15A, ADR-0099): the record store asks the identity module, through the reference directory
 * of the shared kernel, whether the identifier is a person with an active membership of this organization. Every other
 * answer is one answer: a person of another organization, a deactivated member, an identifier that names nobody. The
 * directory tells the record store nothing else, and a link that is not changed is not asked about again.
 */
@PlatformIntegrationTest
class UserLinksIT extends PipelineSupport {

    private static final String MISSING = "The linked record does not exist.";
    private static final String TICKETS = "/api/v1/objects/Ticket__c/records";

    private TestBrowser tickets(Organization organization) {
        TestBrowser admin = adminOf(organization);
        makeObject(admin, "Ticket", "Tickets");
        Response owner = admin.postJson(METADATA_OBJECTS + "/Ticket__c/fields",
                "{\"name\":\"owner\",\"label\":\"owner\",\"type\":\"LOOKUP\","
                        + "\"settings\":{\"targetObject\":\"User\"}}");
        assertThat(owner.status()).as(owner.body()).isEqualTo(201);
        Response title = admin.postJson(METADATA_OBJECTS + "/Ticket__c/fields", "{\"name\":\"title\","
                + "\"label\":\"title\",\"type\":\"TEXT\"}");
        assertThat(title.status()).as(title.body()).isEqualTo(201);
        return admin;
    }

    private static String userId(Member member) {
        return member.person().user().id().toString();
    }

    private static Response ticket(TestBrowser browser, String owner) {
        return createIn(browser, TICKETS, "{\"owner__c\":\"" + owner + "\"}");
    }

    @Test
    void aLinkToAnActiveMemberOfTheOrganizationIsAcceptedAndReadsBack() {
        Organization organization = organization();
        TestBrowser admin = tickets(organization);
        Member colleague = TestOrganizations.join(users, organization.tenant(), false);

        Response ofColleague = ticket(admin, userId(colleague));
        Response ofAdmin = ticket(admin, userId(organization.admin()));

        assertThat(ofColleague.status()).as(ofColleague.body()).isEqualTo(201);
        assertThat(ofAdmin.status()).as(ofAdmin.body()).isEqualTo(201);
        assertThat(JsonPath.<String>read(admin.get(TICKETS + "/" + idOf(ofColleague)).body(),
                "$.data.fields.owner__c")).isEqualTo(userId(colleague));
    }

    @Test
    void everyOtherLinkToAUserGetsTheOneAnswerAndRevealsNothing() {
        Organization mine = organization();
        Organization theirs = organization();
        TestBrowser admin = tickets(mine);
        Member leaver = TestOrganizations.join(users, mine.tenant(), false);
        Response deactivated = admin.postJson("/api/v1/members/" + leaver.membership() + "/deactivate", "{}");
        assertThat(deactivated.status()).as(deactivated.body()).isEqualTo(204);

        Response stranger = ticket(admin, userId(theirs.admin()));
        Response nobody = ticket(admin, UUID.randomUUID().toString());
        Response gone = ticket(admin, userId(leaver));
        Response notAnIdentifier = ticket(admin, "not-an-id");

        for (Response refused : new Response[] {stranger, nobody, gone}) {
            assertThat(refused.status()).as(refused.body()).isEqualTo(400);
            assertThat(problemAt(refused, "owner__c")).isEqualTo(MISSING);
            assertThat(placesOf(refused)).containsExactly("owner__c");
        }
        assertThat(stranger.body()).as("the answer says nothing about the other organization")
                .doesNotContain(userId(theirs.admin()));
        assertThat(problemAt(notAnIdentifier, "owner__c")).isEqualTo("Give the identifier of the linked record.");
    }

    @Test
    void aLinkThatIsNotChangedIsNotAskedAboutAgainSoALeaverDoesNotBlockTheRecord() {
        Organization organization = organization();
        TestBrowser admin = tickets(organization);
        Member leaver = TestOrganizations.join(users, organization.tenant(), false);
        String id = idOf(ticket(admin, userId(leaver)));
        admin.postJson("/api/v1/members/" + leaver.membership() + "/deactivate", "{}");

        Response saved = patchIn(admin, TICKETS, id, 0, "{\"title__c\":\"still saves\"}");
        Response relinked = patchIn(admin, TICKETS, id, 1, "{\"owner__c\":\"" + userId(leaver) + "\"}");
        Response other = patchIn(admin, TICKETS, id, 2, "{\"owner__c\":\"" + userId(organization.admin()) + "\"}");

        assertThat(saved.status()).as(saved.body()).isEqualTo(200);
        assertThat(relinked.status()).as("the same value again is not a change").isEqualTo(200);
        assertThat(other.status()).as(other.body()).isEqualTo(200);
    }

    @Test
    void aLinkToAnotherKindOfPlatformObjectIsStillNotAvailable() {
        Organization organization = organization();
        TestBrowser admin = adminOf(organization);
        makeObject(admin, "Ticket", "Tickets");
        Response role = admin.postJson(METADATA_OBJECTS + "/Ticket__c/fields",
                "{\"name\":\"role\",\"label\":\"role\",\"type\":\"LOOKUP\",\"settings\":{\"targetObject\":\"Role\"}}");
        assertThat(role.status()).as(role.body()).isEqualTo(201);

        Response refused = createIn(admin, TICKETS, "{\"role__c\":\"" + UUID.randomUUID() + "\"}");

        assertThat(refused.status()).isEqualTo(400);
        assertThat(problemAt(refused, "role__c")).isEqualTo("Links to this kind of object are not available yet.");
    }
}
