package app.platform.publicapi.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.licensing.Entitlements;
import app.platform.sharedkernel.ActorId;
import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestOrganizations.Organization;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The plan feature {@code integrations} gates registering and using connected applications, apart from permission and
 * licence (Sprint 31, ADR-0267, ADR-0270): the three mechanisms are asked separately and never merged. With the feature
 * off the management refuses, the token endpoint refuses and live tokens stop working; a person's own token at the
 * public door (Sprint 30) is not part of the feature; switching the feature on again brings the application back.
 */
@PlatformIntegrationTest
class ClientEntitlementIT extends ClientSupport {

    @Autowired
    private Entitlements entitlements;

    @Test
    void aPlanWithoutTheFeatureCannotRegisterAnApplication() {
        Organization organization = organizationWithoutTheFeature();

        Response refused = adminOf(organization).postJson(CLIENTS, registerBody("no-plan", ALL_SCOPES, null, null));

        assertThat(refused.status()).isEqualTo(409);
        assertThat(code(refused)).isEqualTo("CONFLICT");
        assertThat(refused.body()).contains("not part of this organization's plan");
    }

    @Test
    void theAbilityIsAskedBeforeThePlanSoAMemberWithoutItIsForbiddenNotToldAboutThePlan() {
        Organization organization = organizationWithoutTheFeature();
        TestBrowser admin = adminOf(organization);
        TestBrowser nobody = as(organization, memberWithAbilitiesAndMatrix(organization, admin, null).member());

        assertThat(nobody.get(CLIENTS).status()).isEqualTo(403);
        assertThat(admin.get(CLIENTS).status()).as("while a member who may is told about the plan").isEqualTo(409);
    }

    @Test
    void anOverrideThatSwitchesTheFeatureOnLetsAnOrganizationOffThePlanRegisterAndUseAnApplication() {
        Organization organization = organizationWithoutTheFeature();
        entitlements.override(organization.id(), FEATURE, true, ActorId.SYSTEM);
        TestBrowser admin = adminOf(organization);

        Registered app = register(admin, ALL_SCOPES);

        assertThat(tokenRequest(organization.host(), app.clientId(), app.secret(), null).status()).isEqualTo(200);
        assertThat(clientCaller(organization, app).get(OBJECTS).status()).isEqualTo(200);
    }

    @Test
    void losingTheFeatureStopsTheApplicationAtOnceAndGettingItBackBringsItBack() {
        Organization organization = organizationWithClients();
        TestBrowser admin = adminOf(organization);
        Registered app = register(admin, ALL_SCOPES);
        Caller program = clientCaller(organization, app);
        Caller person = administrator(organization);
        assertThat(program.get(OBJECTS).status()).isEqualTo(200);

        entitlements.override(organization.id(), FEATURE, false, ActorId.SYSTEM);

        assertThat(program.get(OBJECTS).status()).as("the live token").isEqualTo(401);
        assertInvalidClient(tokenRequest(organization.host(), app.clientId(), app.secret(), null));
        assertThat(admin.get(CLIENTS).status()).as("the screens").isEqualTo(409);
        assertThat(admin.postJson(CLIENTS + "/" + app.id() + "/disable", "{}").status()).isEqualTo(409);
        assertThat(person.get(OBJECTS).status()).as("a person's own token at the public door is not the feature")
                .isEqualTo(200);

        entitlements.override(organization.id(), FEATURE, null, ActorId.SYSTEM);

        assertThat(program.get(OBJECTS).status()).as("the token was not ended, only refused").isEqualTo(200);
        assertThat(tokenRequest(organization.host(), app.clientId(), app.secret(), null).status()).isEqualTo(200);
        assertThat(admin.get(CLIENTS).status()).isEqualTo(200);
    }

    @Test
    void theFeatureOfOneOrganizationDoesNotReachAnother() {
        Organization with = organizationWithClients();
        Organization without = organizationWithoutTheFeature();
        Registered app = register(adminOf(with), ALL_SCOPES);

        assertThat(adminOf(without).get(CLIENTS).status()).isEqualTo(409);
        assertThat(tokenRequest(with.host(), app.clientId(), app.secret(), null).status()).isEqualTo(200);
        assertInvalidClient(tokenRequest(without.host(), app.clientId(), app.secret(), null));
    }
}
