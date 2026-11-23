package app.platform.publicapi.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.licensing.Entitlements;
import app.platform.sharedkernel.ActorId;
import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestApplication;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestOrganizations;
import app.platform.testsupport.TestOrganizations.Organization;
import app.platform.testsupport.TestSignIn;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Guessing a client secret is slowed and every refusal looks alike (Sprint 31, ADR-0268): failed attempts are limited
 * per source address and per client identifier, an unknown application, a wrong secret, a switched-off application, the
 * wrong host, a plan without the feature and the kill switch all answer {@code invalid_client} in the same words with
 * the same status, and only a caller who has used up the limit is told to wait. The limits of a fixed window may split
 * one count in two, so a test asserts that the limit arrives within twice the number.
 */
@PlatformIntegrationTest
class ClientTokenEndpointLimitsIT extends ClientSupport {

    private static final int PER_CLIENT = 10;
    private static final int PER_SOURCE = 20;

    @Autowired
    private Entitlements entitlements;

    @Test
    void everyCauseOfARefusalAnswersInTheSameWordsWithTheSameStatus() {
        Organization organization = organizationWithClients();
        TestBrowser admin = adminOf(organization);
        Registered off = register(admin, ALL_SCOPES);
        disable(admin, off.id());
        Registered live = register(admin, ALL_SCOPES);
        Organization other = organizationWithClients();
        String host = organization.host();

        List<Response> refusals = List.of(
                tokenRequest(host, "ca_" + "z".repeat(24), live.secret(), null),
                tokenRequest(host, live.clientId(), "cs_" + "A".repeat(43), null),
                tokenRequest(host, off.clientId(), off.secret(), null),
                tokenRequest(other.host(), live.clientId(), live.secret(), null),
                tokenRequest(TestSignIn.PLATFORM_HOST, live.clientId(), live.secret(), null),
                tokenRequest(host, "not-shaped-like-an-identifier", "x", null));

        for (Response refusal : refusals) {
            assertInvalidClient(refusal);
            assertThat(refusal.header("WWW-Authenticate")).isEqualTo(refusals.get(0).header("WWW-Authenticate"));
            assertThat(refusal.header("Content-Type").orElse("")).contains("application/json");
        }
    }

    @Test
    void aRequestWithoutAnyCredentialIsRefusedWithoutSayingAnythingAboutTheApplication() {
        Organization organization = organizationWithClients();
        Registered app = register(adminOf(organization), ALL_SCOPES);

        Response noSecret = tokenRequest(organization.host(), app.clientId(), "", null);
        Response noHeader = new TestHttp(port).post(TOKEN, "grant_type=client_credentials", "Host",
                organization.host(), "X-Forwarded-For", freshSource(), "Content-Type",
                "application/x-www-form-urlencoded");

        for (Response refused : List.of(noSecret, noHeader)) {
            assertThat(refused.status()).as(refused.body()).isIn(400, 401);
            assertThat(refused.body()).doesNotContain(app.clientId()).doesNotContain("access_token");
        }
    }

    @Test
    void aClientIdentifierMayFailOnlyAFewTimesFromAnySourceAndThenEvenTheRightSecretWaits() {
        Organization organization = organizationWithClients();
        Registered app = register(adminOf(organization), ALL_SCOPES);
        int firstLimited = -1;

        for (int attempt = 1; attempt <= 3 * PER_CLIENT && firstLimited < 0; attempt++) {
            Response answer = tokenRequest(organization.host(), app.clientId(), "cs_" + "B".repeat(43), null);
            if (answer.status() == 429) {
                firstLimited = attempt;
            } else {
                assertInvalidClient(answer);
            }
        }

        assertThat(firstLimited).as("the limit arrived").isPositive();
        assertThat(firstLimited).isBetween(PER_CLIENT, 2 * PER_CLIENT + 1);
        Response rightSecret = tokenRequest(organization.host(), app.clientId(), app.secret(), null);
        assertThat(rightSecret.status()).as("the owner waits one short window").isEqualTo(429);
        assertThat(rightSecret.header("Retry-After")).isPresent();
        assertThat(rightSecret.body()).contains("temporarily_unavailable").doesNotContain(app.secret());
    }

    @Test
    void aWaitingClientDoesNotStopAnotherApplicationOfTheSameOrganization() {
        Organization organization = organizationWithClients();
        TestBrowser admin = adminOf(organization);
        Registered attacked = register(admin, ALL_SCOPES);
        Registered bystander = register(admin, ALL_SCOPES);
        for (int attempt = 0; attempt < 2 * PER_CLIENT + 1; attempt++) {
            tokenRequest(organization.host(), attacked.clientId(), "cs_" + "C".repeat(43), null);
        }

        assertThat(tokenRequest(organization.host(), bystander.clientId(), bystander.secret(), null).status())
                .isEqualTo(200);
    }

    @Test
    void aSourceMayFailOnlyUpToItsLimitWhateverIdentifiersItTriesAndAnotherSourceIsFree() {
        Organization organization = organizationWithClients();
        Registered app = register(adminOf(organization), ALL_SCOPES);
        String source = freshSource();
        int firstLimited = -1;

        for (int attempt = 1; attempt <= 3 * PER_SOURCE && firstLimited < 0; attempt++) {
            Response answer = tokenRequest(organization.host(), "ca_" + UUID.randomUUID().toString()
                    .replace("-", "").substring(0, 24), "cs_" + "D".repeat(43), null, source);
            if (answer.status() == 429) {
                firstLimited = attempt;
            } else {
                assertInvalidClient(answer);
            }
        }

        assertThat(firstLimited).isBetween(PER_SOURCE, 2 * PER_SOURCE + 1);
        assertThat(tokenRequest(organization.host(), app.clientId(), app.secret(), null, source).status())
                .as("from the source that failed too often").isEqualTo(429);
        assertThat(tokenRequest(organization.host(), app.clientId(), app.secret(), null, freshSource()).status())
                .as("from another source").isEqualTo(200);
    }

    @Test
    void anAttemptThatSucceedsIsNeverCountedAgainstTheSource() {
        Organization organization = organizationWithClients();
        Registered app = register(adminOf(organization), ALL_SCOPES);
        String source = freshSource();

        for (int attempt = 0; attempt < 3 * PER_SOURCE; attempt++) {
            assertThat(tokenRequest(organization.host(), app.clientId(), app.secret(), null, source).status())
                    .isEqualTo(200);
        }
    }

    @Test
    void aPlanWithoutTheFeatureRefusesTheTokenEndpointAndStopsLiveTokensAndComesBackWithThePlan() {
        Organization organization = organizationWithClients();
        TestBrowser admin = adminOf(organization);
        Registered app = register(admin, ALL_SCOPES);
        Caller program = clientCaller(organization, app);
        assertThat(program.get(OBJECTS).status()).isEqualTo(200);

        entitlements.override(organization.id(), FEATURE, false, ActorId.SYSTEM);

        assertInvalidClient(tokenRequest(organization.host(), app.clientId(), app.secret(), null));
        assertThat(program.get(OBJECTS).status()).as("a live token stops working").isEqualTo(401);
        assertThat(admin.get(CLIENTS).status()).as("and the screens say the plan lacks it").isEqualTo(409);

        entitlements.override(organization.id(), FEATURE, null, ActorId.SYSTEM);

        assertThat(tokenRequest(organization.host(), app.clientId(), app.secret(), null).status()).isEqualTo(200);
    }

    @Test
    void theKillSwitchRefusesClientCredentialsLikeAWrongSecretAndHidesTheManagementEndpoints() {
        Organization organization = organizationWithClients();
        TestBrowser admin = adminOf(organization);
        Registered app = register(admin, ALL_SCOPES);
        Caller program = clientCaller(organization, app);
        try (TestApplication off = TestApplication.start("platform.identity.clients.enabled=false")) {
            String basic = Base64.getEncoder().encodeToString((app.clientId() + ":" + app.secret())
                    .getBytes(StandardCharsets.UTF_8));
            Response token = new TestHttp(off.port()).post(TOKEN, "grant_type=client_credentials", "Host",
                    organization.host(), "X-Forwarded-For", freshSource(), "Authorization", "Basic " + basic,
                    "Content-Type", "application/x-www-form-urlencoded");
            TestBrowser adminThere = TestOrganizations.signedIn(off.port(), organization.host(),
                    organization.admin().person());

            assertInvalidClient(token);
            assertThat(adminThere.get(CLIENTS).status()).isEqualTo(404);
            assertThat(adminThere.get(CLIENTS + "/catalogue").status()).isEqualTo(404);
            assertThat(program.onInstance(off.port()).get(OBJECTS).status()).as("a live token stops").isEqualTo(401);
            assertThat(program.get(OBJECTS).status()).as("the instance that has it on is unchanged").isEqualTo(200);
        }
    }

    @Test
    void theFailuresAreCountedAsAMetricByCauseAndNeverByIdentifier() {
        Organization organization = organizationWithClients();
        Registered app = register(adminOf(organization), ALL_SCOPES);
        tokenRequest(organization.host(), app.clientId(), "cs_" + "E".repeat(43), null);
        tokenRequest(organization.host(), "ca_" + "y".repeat(24), "cs_" + "E".repeat(43), null);

        List<String> tags = new ArrayList<>();
        meters.getMeters().stream().filter(meter -> meter.getId().getName().startsWith("platform.clients."))
                .forEach(meter -> meter.getId().getTags()
                        .forEach(tag -> tags.add(tag.getKey() + "=" + tag.getValue())));

        assertThat(tags).isNotEmpty();
        assertThat(tags).allMatch(tag -> !tag.contains(app.clientId()) && !tag.contains(app.secret()));
        assertThat(tags).contains("reason=wrong_secret", "reason=unknown_client");
    }

    @Autowired
    private io.micrometer.core.instrument.MeterRegistry meters;

    @Test
    void refusalsOfAKnownApplicationAreAuditedByIdentifierAndTheSourceNeverTheSecret() {
        Organization organization = organizationWithClients();
        Registered app = register(adminOf(organization), ALL_SCOPES);
        String wrong = "cs_" + "F".repeat(43);
        tokenRequest(organization.host(), app.clientId(), wrong, null);

        List<String> trail = owner("select reason || ' ' || attributes::text from audit_record "
                + "where event_type = 'clients.token_refused' and attributes ->> 'client' = ?", app.clientId());

        assertThat(trail).isNotEmpty();
        trail.forEach(line -> assertThat(line).startsWith("wrong_secret ").doesNotContain(wrong)
                .doesNotContain(app.secret()));
    }
}
