package app.platform.publicapi.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestApplication;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestOrganizations.Organization;
import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The life of the secrets and the tokens of a connected application (Sprint 31, ADR-0268, ADR-0269): a rotation with
 * and without an overlap, ending one secret, switching an application off and removing it. Everything that takes
 * something away ends the matching tokens in the same transaction, and a second instance of the application sees every
 * change at once because nothing is cached.
 */
@PlatformIntegrationTest
class ClientSecretLifecycleIT extends ClientSupport {

    private int listObjects(Caller program) {
        return program.get(OBJECTS).status();
    }

    @Test
    void aRotationWithoutAnOverlapEndsTheOldSecretAtOnceButLetsTheTokensItMadeLiveOn() {
        Organization organization = organizationWithClients();
        TestBrowser admin = adminOf(organization);
        Registered app = register(admin, ALL_SCOPES);
        Caller before = clientCaller(organization, app);

        Response rotation = rotate(admin, app.id(), 0, false);

        assertThat(rotation.status()).as(rotation.body()).isEqualTo(201);
        assertInvalidClient(tokenRequest(organization.host(), app.clientId(), app.secret(), null));
        assertThat(listObjects(before)).as("a token already issued lives until it ends").isEqualTo(200);
        assertThat(tokenRequest(organization.host(), app.clientId(), newSecretOf(rotation), null).status())
                .isEqualTo(200);
    }

    @Test
    void aRotationThatSaysToEndTheTokensNowEndsThemInTheSameTransaction() {
        Organization organization = organizationWithClients();
        TestBrowser admin = adminOf(organization);
        Registered app = register(admin, ALL_SCOPES);
        Caller before = clientCaller(organization, app);
        assertThat(listObjects(before)).isEqualTo(200);

        rotate(admin, app.id(), 0, true);

        assertThat(listObjects(before)).isEqualTo(401);
    }

    @Test
    void aRotationWithAnOverlapKeepsBothSecretsWorkingUntilTheOverlapEnds() throws InterruptedException {
        Organization organization = organizationWithClients();
        TestBrowser admin = adminOf(organization);
        Registered app = register(admin, ALL_SCOPES);

        Response rotation = rotate(admin, app.id(), 2, false);

        assertThat(tokenRequest(organization.host(), app.clientId(), app.secret(), null).status())
                .as("the old secret still works in its overlap").isEqualTo(200);
        assertThat(tokenRequest(organization.host(), app.clientId(), newSecretOf(rotation), null).status())
                .isEqualTo(200);
        List<String> states = JsonPath.read(read(admin, app.id()).body(), "$.data.secrets[*].state");
        assertThat(states).containsExactlyInAnyOrder("ACTIVE", "ENDING");

        // The overlap ends: a secret's end can only be moved earlier, which is how the clock is brought forward.
        ownerRuns("update oauth_client_secret set expires_at = now() + interval '1 second', "
                + "version = version + 1 where client_row_id = '" + app.id() + "' and expires_at is not null");
        Thread.sleep(1500);

        assertInvalidClient(tokenRequest(organization.host(), app.clientId(), app.secret(), null));
        assertThat(tokenRequest(organization.host(), app.clientId(), newSecretOf(rotation), null).status())
                .isEqualTo(200);
        assertThat(JsonPath.<List<String>>read(read(admin, app.id()).body(), "$.data.secrets[*].state"))
                .containsExactlyInAnyOrder("ACTIVE", "ENDED");
    }

    @Test
    void anOverlapLongerThanADayIsRefusedInTheApiWords() {
        Organization organization = organizationWithClients();
        TestBrowser admin = adminOf(organization);
        Registered app = register(admin, ALL_SCOPES);

        Response refused = rotate(admin, app.id(), 25, false);

        assertThat(refused.status()).isEqualTo(400);
        assertThat(tokenRequest(organization.host(), app.clientId(), app.secret(), null).status())
                .as("and nothing changed").isEqualTo(200);
    }

    @Test
    void anApplicationMayNotHoldMoreSecretsAtOnceThanTheSettingAllows() {
        Organization organization = organizationWithClients();
        TestBrowser admin = adminOf(organization);
        Registered app = register(admin, ALL_SCOPES);

        assertThat(rotate(admin, app.id(), 24, false).status()).isEqualTo(201);
        assertThat(rotate(admin, app.id(), 24, false).status()).isEqualTo(201);
        Response third = rotate(admin, app.id(), 24, false);

        assertThat(third.status()).isEqualTo(409);
        assertThat(code(third)).isEqualTo("CONFLICT");
        assertThat(JsonPath.<Integer>read(read(admin, app.id()).body(), "$.data.app.activeSecrets")).isEqualTo(3);
    }

    @Test
    void endingOneSecretStopsItAtOnceAndEndsTheTokensItMadeButNotTheOthers() {
        Organization organization = organizationWithClients();
        TestBrowser admin = adminOf(organization);
        Registered app = register(admin, ALL_SCOPES);
        Response rotation = rotate(admin, app.id(), 24, false);
        String second = newSecretOf(rotation);
        String firstSecretId = JsonPath.<List<String>>read(read(admin, app.id()).body(),
                "$.data.secrets[?(@.number==1)].id").get(0);
        Caller madeByFirst = clientCaller(organization, app);
        Caller madeBySecond = new Caller(port, organization.host(),
                accessToken(tokenRequest(organization.host(), app.clientId(), second, null)), null);
        assertThat(listObjects(madeByFirst)).isEqualTo(200);
        assertThat(listObjects(madeBySecond)).isEqualTo(200);

        Response revoked = revokeSecret(admin, app.id(), firstSecretId);

        assertThat(revoked.status()).as(revoked.body()).isEqualTo(200);
        assertInvalidClient(tokenRequest(organization.host(), app.clientId(), app.secret(), null));
        assertThat(listObjects(madeByFirst)).as("the tokens of the ended secret end with it").isEqualTo(401);
        assertThat(listObjects(madeBySecond)).as("the other secret is untouched").isEqualTo(200);
        assertThat(revokeSecret(admin, app.id(), firstSecretId).status()).as("ending again changes nothing")
                .isEqualTo(200);
    }

    @Test
    void aSecretOfAnotherApplicationIsNotFoundWhenTheAddressNamesItWithThisOne() {
        Organization organization = organizationWithClients();
        TestBrowser admin = adminOf(organization);
        Registered one = register(admin, ALL_SCOPES);
        Registered two = register(admin, ALL_SCOPES);

        Response refused = revokeSecret(admin, one.id(), secretIds(admin, two.id()).get(0));

        assertThat(refused.status()).isEqualTo(404);
        assertThat(tokenRequest(organization.host(), two.clientId(), two.secret(), null).status()).isEqualTo(200);
    }

    @Test
    void switchingAnApplicationOffEndsItsTokensAtOnceAndSwitchingItOnDoesNotBringThemBack() {
        Organization organization = organizationWithClients();
        TestBrowser admin = adminOf(organization);
        Registered app = register(admin, ALL_SCOPES);
        Caller program = clientCaller(organization, app);
        assertThat(listObjects(program)).isEqualTo(200);

        Response off = disable(admin, app.id());

        assertThat(off.status()).as(off.body()).isEqualTo(200);
        assertThat(JsonPath.<String>read(off.body(), "$.data.app.stoppedReason")).isEqualTo("disabled");
        assertThat(listObjects(program)).isEqualTo(401);
        assertInvalidClient(tokenRequest(organization.host(), app.clientId(), app.secret(), null));

        assertThat(enable(admin, app.id()).status()).isEqualTo(200);

        assertThat(listObjects(program)).as("an ended token stays ended").isEqualTo(401);
        assertThat(tokenRequest(organization.host(), app.clientId(), app.secret(), null).status()).isEqualTo(200);
    }

    @Test
    void removingAnApplicationEndsItsSecretsAndTokensAndItCannotBeFoundAgain() {
        Organization organization = organizationWithClients();
        TestBrowser admin = adminOf(organization);
        Registered app = register(admin, ALL_SCOPES);
        Caller program = clientCaller(organization, app);

        Response removed = removeApplication(admin, app.id());

        assertThat(removed.status()).isEqualTo(200);
        assertThat(listObjects(program)).isEqualTo(401);
        assertInvalidClient(tokenRequest(organization.host(), app.clientId(), app.secret(), null));
        assertThat(read(admin, app.id()).status()).isEqualTo(404);
        assertThat(removeApplication(admin, app.id()).status()).isEqualTo(404);
        assertThat(JsonPath.<List<?>>read(admin.get(CLIENTS).body(), "$.data")).isEmpty();
        assertThat(owner("select ended_reason from oauth_client_secret where client_row_id = ?::uuid", app.id()))
                .containsExactly("removed");
    }

    @Test
    void narrowingWhatAnApplicationMayDoEndsItsTokensAndWideningDoesNot() {
        Organization organization = organizationWithClients();
        TestBrowser admin = adminOf(organization);
        Registered app = register(admin, "narrowing", "[\"objects.read\",\"records.read\"]", null);
        Caller program = clientCaller(organization, app);
        assertThat(listObjects(program)).isEqualTo(200);
        long version = versionOfApplication(admin, app.id());

        Response wider = admin.request("PUT", CLIENTS + "/" + app.id(), "{\"name\":\"narrowing\",\"description\":\"\","
                + "\"scopes\":" + ALL_SCOPES + ",\"tokenMinutes\":15,\"version\":" + version + "}");
        assertThat(wider.status()).as(wider.body()).isEqualTo(200);
        assertThat(listObjects(program)).as("widening ends nothing").isEqualTo(200);

        Response narrower = admin.request("PUT", CLIENTS + "/" + app.id(), "{\"name\":\"narrowing\","
                + "\"description\":\"\",\"scopes\":[\"records.read\"],\"tokenMinutes\":15,\"version\":"
                + versionOfApplication(admin, app.id()) + "}");

        assertThat(narrower.status()).as(narrower.body()).isEqualTo(200);
        assertThat(listObjects(program)).as("narrowing ends the tokens already issued").isEqualTo(401);
        Caller again = clientCaller(organization, app);
        assertThat(listObjects(again)).as("a new token holds only what is left").isEqualTo(403);
    }

    @Test
    void aChangeBasedOnAnOldVersionIsAConflictAndChangesNothing() {
        Organization organization = organizationWithClients();
        TestBrowser admin = adminOf(organization);
        Registered app = register(admin, ALL_SCOPES);
        long version = versionOfApplication(admin, app.id());
        String body = "{\"name\":\"renamed\",\"description\":\"\",\"scopes\":" + ALL_SCOPES
                + ",\"tokenMinutes\":15,\"version\":";
        assertThat(admin.request("PUT", CLIENTS + "/" + app.id(), body + version + "}").status()).isEqualTo(200);

        Response stale = admin.request("PUT", CLIENTS + "/" + app.id(), body + version + "}");

        assertThat(stale.status()).isEqualTo(409);
        assertThat(code(stale)).isEqualTo("CONCURRENT_MODIFICATION");
    }

    @Test
    void aSecondInstanceSeesEveryChangeAtOnce() {
        Organization organization = organizationWithClients();
        TestBrowser admin = adminOf(organization);
        Registered app = register(admin, ALL_SCOPES);
        try (TestApplication other = TestApplication.start()) {
            String basic = Base64.getEncoder().encodeToString((app.clientId() + ":" + app.secret())
                    .getBytes(StandardCharsets.UTF_8));
            TestHttp onOther = new TestHttp(other.port());
            Response first = askOnOther(onOther, organization.host(), basic);
            Caller viaOther = new Caller(other.port(), organization.host(), accessToken(first), null);
            assertThat(listObjects(viaOther)).isEqualTo(200);

            rotate(admin, app.id(), 0, false);
            assertThat(askOnOther(onOther, organization.host(), basic).status())
                    .as("the old secret fails on the other instance at once").isEqualTo(401);

            disable(admin, app.id());
            assertThat(listObjects(viaOther)).as("a token ends on the other instance at once").isEqualTo(401);
        }
    }

    private static Response askOnOther(TestHttp http, String host, String basic) {
        return http.post(TOKEN, "grant_type=client_credentials", "Host", host, "X-Forwarded-For", freshSource(),
                "Authorization", "Basic " + basic, "Content-Type", "application/x-www-form-urlencoded");
    }

    @Test
    void theListSaysWhichSecretsWorkAndNeverHoldsTheirValues() {
        Organization organization = organizationWithClients();
        TestBrowser admin = adminOf(organization);
        Registered app = register(admin, ALL_SCOPES);
        Response rotation = rotate(admin, app.id(), 0, false);

        Response detail = read(admin, app.id());

        assertThat(JsonPath.<List<String>>read(detail.body(), "$.data.secrets[*].state"))
                .containsExactly("ACTIVE", "ENDED");
        assertThat(detail.body()).doesNotContain(app.secret()).doesNotContain(newSecretOf(rotation));
    }
}
