package app.platform.publicapi.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.sharedkernel.ActorId;
import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestOrganizations.Organization;
import com.jayway.jsonpath.JsonPath;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * An application acts as the member who registered it, and stops when that member can no longer act (Sprint 31,
 * ADR-0267, ADR-0269): deactivated, suspended, or no longer a member, its tokens stop at once and it is shown as
 * stopped, with nothing transferred. A password change and "sign out everywhere" end sign-ins, not an integration the
 * person set up: switching the application off does.
 */
@PlatformIntegrationTest
class ClientMemberLeavesIT extends ClientSupport {

    private record Setup(Organization organization, TestBrowser admin, Integrator who, TestBrowser person,
            Registered app, Caller program) {
    }

    private Setup setup() {
        Organization organization = organizationWithClients();
        TestBrowser admin = adminOf(organization);
        Integrator who = integrationMember(organization, admin, "read", "create");
        TestBrowser person = as(organization, who.member());
        Registered app = register(person, ALL_SCOPES);
        Caller program = clientCaller(organization, app);
        assertThat(program.get(OBJECTS).status()).isEqualTo(200);
        return new Setup(organization, admin, who, person, app, program);
    }

    private static void assertShownAsStopped(Setup setup) {
        Response detail = read(setup.admin(), setup.app().id());
        assertThat(JsonPath.<Boolean>read(detail.body(), "$.data.app.running")).isFalse();
        assertThat(JsonPath.<String>read(detail.body(), "$.data.app.stoppedReason")).isEqualTo("member_not_active");
        assertThat(JsonPath.<String>read(detail.body(), "$.data.app.state")).as("still on: nothing was transferred")
                .isEqualTo("ACTIVE");
        assertThat(JsonPath.<Boolean>read(detail.body(), "$.data.app.actsAsYou")).isFalse();
    }

    @Test
    void aDeactivatedMemberStopsTheApplicationAtOnceAndItIsShownAsStopped() {
        Setup s = setup();

        Response deactivated = s.admin().postJson("/api/v1/members/" + s.who().member().membership()
                + "/deactivate", "{}");

        assertThat(deactivated.status()).as(deactivated.body()).isEqualTo(204);
        assertThat(s.program().get(OBJECTS).status()).isEqualTo(401);
        assertInvalidClient(tokenRequest(s.organization().host(), s.app().clientId(), s.app().secret(), null));
        assertShownAsStopped(s);
    }

    @Test
    void aMemberWhoIsLetBackInGetsTheApplicationRunningAgainWithANewToken() {
        Setup s = setup();
        s.admin().postJson("/api/v1/members/" + s.who().member().membership() + "/deactivate", "{}");
        assertThat(s.program().get(OBJECTS).status()).isEqualTo(401);

        Response reactivated = s.admin().postJson("/api/v1/members/" + s.who().member().membership()
                + "/reactivate", "{}");

        assertThat(reactivated.status()).as(reactivated.body()).isEqualTo(204);
        assertThat(tokenRequest(s.organization().host(), s.app().clientId(), s.app().secret(), null).status())
                .isEqualTo(200);
        assertThat(JsonPath.<Boolean>read(read(s.admin(), s.app().id()).body(), "$.data.app.running")).isTrue();
    }

    @Test
    void aSuspendedAccountStopsTheApplicationAtOnce() {
        Setup s = setup();

        users.suspend(s.who().member().person().user().id(), ActorId.SYSTEM);

        assertThat(s.program().get(OBJECTS).status()).isEqualTo(401);
        assertInvalidClient(tokenRequest(s.organization().host(), s.app().clientId(), s.app().secret(), null));
        assertShownAsStopped(s);
    }

    @Test
    void aDeactivatedAccountStopsTheApplicationAtOnce() {
        Setup s = setup();

        users.deactivate(s.who().member().person().user().id(), ActorId.SYSTEM);

        assertThat(s.program().get(OBJECTS).status()).isEqualTo(401);
        assertInvalidClient(tokenRequest(s.organization().host(), s.app().clientId(), s.app().secret(), null));
        assertShownAsStopped(s);
    }

    @Test
    void aMemberWhoLeavesTheOrganizationStopsTheApplicationAtOnce() {
        Setup s = setup();

        Response left = s.person().postJson("/api/v1/organization/leave", "{}");

        assertThat(left.status()).as(left.body()).isIn(200, 204);
        assertThat(s.program().get(OBJECTS).status()).isEqualTo(401);
        assertInvalidClient(tokenRequest(s.organization().host(), s.app().clientId(), s.app().secret(), null));
    }

    @Test
    void aPasswordChangeEndsThePersonsSignInsButNotTheApplicationTheyRegistered() {
        Setup s = setup();
        String fresh = "Pw-" + UUID.randomUUID() + "-" + UUID.randomUUID();
        Caller theirOwn = caller(s.organization().host(), s.person());
        assertThat(theirOwn.get(OBJECTS).status()).isEqualTo(200);

        Response changed = s.person().postJson("/api/v1/auth/password", "{\"currentPassword\":\""
                + s.who().member().person().password() + "\",\"newPassword\":\"" + fresh + "\"}");

        assertThat(changed.status()).as(changed.body()).isEqualTo(204);
        assertThat(theirOwn.get(OBJECTS).status()).as("the person's own token ended").isEqualTo(401);
        assertThat(s.program().get(OBJECTS).status()).as("the application's token did not").isEqualTo(200);
        assertThat(tokenRequest(s.organization().host(), s.app().clientId(), s.app().secret(), null).status())
                .isEqualTo(200);
    }

    @Test
    void signingOutEverywhereEndsThePersonsSignInsButNotTheApplicationTheyRegistered() {
        Setup s = setup();
        Caller theirOwn = caller(s.organization().host(), s.person());
        assertThat(theirOwn.get(OBJECTS).status()).isEqualTo(200);

        Response out = s.person().postJson("/api/v1/auth/sign-out-all", "{}");

        assertThat(out.status()).isEqualTo(204);
        assertThat(theirOwn.get(OBJECTS).status()).as("the person's own token ended").isEqualTo(401);
        assertThat(s.program().get(OBJECTS).status()).as("the application's token did not").isEqualTo(200);
    }

    @Test
    void signingOutEveryoneElseOfTheOrganizationDoesNotStopApplicationsEither() {
        Setup s = setup();
        Caller theirOwn = caller(s.organization().host(), s.person());
        assertThat(theirOwn.get(OBJECTS).status()).isEqualTo(200);

        Response out = s.admin().postJson("/api/v1/organization/sign-out-all", "{}");

        assertThat(out.status()).as(out.body()).isIn(200, 204);
        assertThat(theirOwn.get(OBJECTS).status()).isEqualTo(401);
        assertThat(s.program().get(OBJECTS).status()).isEqualTo(200);
    }

    @Test
    void anAdministratorWhoIsNotTheMemberSeesTheApplicationAndWhoItActsAs() {
        Setup s = setup();

        Response list = s.admin().get(CLIENTS);

        assertThat(JsonPath.<Boolean>read(list.body(), "$.data[0].actsAsYou")).isFalse();
        assertThat(JsonPath.<String>read(list.body(), "$.data[0].actsAs")).isNotBlank();
    }

    @Test
    void switchingTheApplicationOffIsHowAnIntegrationIsEndedBeforeItsMemberLeaves() {
        Setup s = setup();

        assertThat(disable(s.person(), s.app().id()).status()).isEqualTo(200);

        assertThat(s.program().get(OBJECTS).status()).isEqualTo(401);
        assertInvalidClient(tokenRequest(s.organization().host(), s.app().clientId(), s.app().secret(), null));
    }
}
