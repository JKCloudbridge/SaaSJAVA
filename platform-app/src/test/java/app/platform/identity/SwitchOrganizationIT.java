package app.platform.identity;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.IdentityDb;
import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestOrganizations;
import app.platform.testsupport.TestOrganizations.Member;
import app.platform.testsupport.TestOrganizations.Organization;
import app.platform.testsupport.TestSignIn;
import app.platform.testsupport.TestUsers;
import app.platform.testsupport.TestUsers.TestUser;
import app.platform.testsupport.TestWarmUp;
import com.jayway.jsonpath.JsonPath;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Organization switching (Sprint 5, ADR-0027, ADR-0029): the list of a person's organizations, and moving to another
 * one
 * with a one-time proof that is checked against the membership, the host and the clock. One person with memberships in
 * two organizations reaches each of them and nothing else.
 */
@PlatformIntegrationTest
class SwitchOrganizationIT {

    private static final String ME = "/api/v1/auth/me";

    @LocalServerPort
    private int port;

    @Autowired
    private Users users;

    private static Response switchTo(TestBrowser browser, String slug) {
        return browser.postJson("/api/v1/auth/switch", "{\"slug\":\"" + slug + "\"}");
    }

    private static Response complete(TestBrowser browser, String token) {
        return browser.postJson("/api/v1/auth/switch/complete", "{\"token\":\"" + token + "\"}");
    }

    private static String tokenOf(Response asked) {
        return JsonPath.read(asked.body(), "$.data.token");
    }

    private TestBrowser signedInOnPlatform(TestUser person) {
        TestBrowser browser = new TestBrowser(port, TestSignIn.PLATFORM_HOST);
        browser.signIn(person.email(), person.password());
        return browser;
    }

    // ---- the list ----

    @Test
    void thePersonSeesTheirOwnOrganizationsOnEveryHost() {
        Organization first = TestOrganizations.create(users);
        Organization second = TestOrganizations.create(users);
        Organization unrelated = TestOrganizations.create(users);
        TestUser person = TestUsers.create(users);
        TestOrganizations.join(first.tenant(), person, false);
        TestOrganizations.join(second.tenant(), person, true);

        for (TestBrowser browser : List.of(signedInOnPlatform(person),
                TestOrganizations.signedIn(port, first.host(), person))) {
            Response list = browser.get("/api/v1/organizations");

            assertThat(list.status()).isEqualTo(200);
            List<String> slugs = JsonPath.read(list.body(), "$.data[*].slug");
            assertThat(slugs).containsExactlyInAnyOrder(first.tenant().slug(), second.tenant().slug())
                    .doesNotContain(unrelated.tenant().slug());
            List<String> hosts = JsonPath.read(list.body(), "$.data[*].host");
            assertThat(hosts).containsExactlyInAnyOrder(first.host(), second.host());
        }
    }

    @Test
    void aDeactivatedMembershipAndAClosedOrganizationAreNotListed() throws SQLException {
        Organization open = TestOrganizations.create(users);
        Organization deactivated = TestOrganizations.create(users);
        Organization closed = TestOrganizations.create(users);
        TestUser person = TestUsers.create(users);
        TestOrganizations.join(open.tenant(), person, false);
        UUID membership = TestOrganizations.join(deactivated.tenant(), person, false);
        TestOrganizations.join(closed.tenant(), person, false);
        TestBrowser browser = signedInOnPlatform(person);
        IdentityDb.executeWithoutTriggers("update membership set status = 'DEACTIVATED' "
                + "where id = ?", membership);
        IdentityDb.executeWithoutTriggers("update tenant set status = 'SUSPENDED' where id = ?", closed.id().value());

        List<String> slugs = JsonPath.read(browser.get("/api/v1/organizations").body(), "$.data[*].slug");

        assertThat(slugs).containsExactly(open.tenant().slug());
    }

    @Test
    void theListNeedsASignInAndShowsNothingForAPersonWithoutMemberships() {
        assertThat(new TestBrowser(port, TestSignIn.PLATFORM_HOST).get("/api/v1/organizations").status())
                .isEqualTo(401);
        TestBrowser alone = signedInOnPlatform(TestUsers.create(users));

        assertThat(JsonPath.<List<?>>read(alone.get("/api/v1/organizations").body(), "$.data")).isEmpty();
    }

    // ---- the switch ----

    @Test
    void aPersonMovesBetweenTwoOrganizationsAndCannotReachAThird() {
        Organization first = TestOrganizations.create(users);
        Organization second = TestOrganizations.create(users);
        Organization third = TestOrganizations.create(users);
        TestUser person = TestUsers.create(users);
        TestOrganizations.join(first.tenant(), person, false);
        TestOrganizations.join(second.tenant(), person, false);
        TestBrowser inFirst = TestOrganizations.signedIn(port, first.host(), person);

        Response asked = switchTo(inFirst, second.tenant().slug());

        assertThat(asked.status()).isEqualTo(200);
        assertThat(JsonPath.<String>read(asked.body(), "$.data.host")).isEqualTo(second.host());
        TestBrowser inSecond = new TestBrowser(port, second.host());
        assertThat(inSecond.get(ME).status()).as("not signed in there yet").isEqualTo(401);
        assertThat(complete(inSecond, tokenOf(asked)).status()).isEqualTo(204);
        inSecond.completeSignIn();
        assertThat(inSecond.get(ME).status()).isEqualTo(200);
        assertThat(JsonPath.<String>read(inSecond.get(ME).body(), "$.data.id"))
                .isEqualTo(person.user().id().toString());
        assertThat(inFirst.get(ME).status()).as("the first session is untouched").isEqualTo(200);
        // And back again, from the second.
        Response back = switchTo(inSecond, first.tenant().slug());
        assertThat(back.status()).isEqualTo(200);
        assertThat(JsonPath.<String>read(back.body(), "$.data.host")).isEqualTo(first.host());
        // Never into an organization the person does not belong to, and never the same answer as a missing one.
        Response notMine = switchTo(inFirst, third.tenant().slug());
        Response missing = switchTo(inFirst, "no-such-organization");
        assertThat(notMine.status()).isEqualTo(404);
        assertThat(notMine.body().replaceAll("\"requestId\":\"[^\"]*\"", "").replaceAll("\"traceId\":\"[^\"]*\"", ""))
                .isEqualTo(missing.body().replaceAll("\"requestId\":\"[^\"]*\"", "")
                        .replaceAll("\"traceId\":\"[^\"]*\"", ""));
        assertThat(IdentityDb.auditOfType("auth.organization.switched"))
                .anyMatch(record -> person.user().id().equals(record.userId()));
    }

    @Test
    void theNewSessionBelongsToTheDestinationHostOnly() {
        Organization first = TestOrganizations.create(users);
        Organization second = TestOrganizations.create(users);
        TestUser person = TestUsers.create(users);
        TestOrganizations.join(first.tenant(), person, false);
        TestOrganizations.join(second.tenant(), person, false);
        TestBrowser inFirst = TestOrganizations.signedIn(port, first.host(), person);
        TestBrowser inSecond = new TestBrowser(port, second.host());
        complete(inSecond, tokenOf(switchTo(inFirst, second.tenant().slug())));
        String bearer = inSecond.completeSignIn().bearer();

        assertThat(new TestHttp(port).get(ME, "Host", second.host(), "Authorization", bearer).status()).isEqualTo(200);
        assertThat(new TestHttp(port).get(ME, "Host", first.host(), "Authorization", bearer).status())
                .as("not the other organization").isEqualTo(401);
        assertThat(new TestHttp(port).get(ME, "Host", TestSignIn.PLATFORM_HOST, "Authorization", bearer).status())
                .as("not the platform host").isEqualTo(401);
    }

    @Test
    void theProofWorksOnceOnlyOnItsHostAndOnlyForAMember() throws SQLException {
        Organization first = TestOrganizations.create(users);
        Organization second = TestOrganizations.create(users);
        Organization third = TestOrganizations.create(users);
        TestUser person = TestUsers.create(users);
        TestOrganizations.join(first.tenant(), person, false);
        UUID membership = TestOrganizations.join(second.tenant(), person, false);
        TestBrowser inFirst = TestOrganizations.signedIn(port, first.host(), person);

        String proof = tokenOf(switchTo(inFirst, second.tenant().slug()));
        // Presented on the wrong host: refused, and still usable on the right one (a wrong host burns nothing).
        Response wrongHost = complete(new TestBrowser(port, third.host()), proof);
        assertThat(wrongHost.status()).isEqualTo(400);
        assertThat(complete(new TestBrowser(port, TestSignIn.PLATFORM_HOST), proof).status())
                .as("the platform host is not a destination").isEqualTo(404);
        assertThat(complete(new TestBrowser(port, second.host()), proof).status()).isEqualTo(204);
        Response again = complete(new TestBrowser(port, second.host()), proof);
        assertThat(again.status()).as("once only").isEqualTo(400);
        assertThat(again.body()).contains("\"token\"");
        // Unknown, expired and for-a-person-who-left proofs all get the same refusal.
        Response garbage = complete(new TestBrowser(port, second.host()), "x".repeat(43));
        String expired = tokenOf(switchTo(inFirst, second.tenant().slug()));
        IdentityDb.executeWithoutTriggers("update organization_handoff set expires_at = now() - interval '1 minute' "
                + "where user_id = ? and used_at is null", person.user().id());
        String left = tokenOf(switchTo(inFirst, second.tenant().slug()));
        IdentityDb.executeWithoutTriggers("update membership set status = 'DEACTIVATED' "
                + "where id = ?", membership);
        Response expiredAnswer = complete(new TestBrowser(port, second.host()), expired);
        Response leftAnswer = complete(new TestBrowser(port, second.host()), left);
        assertThat(garbage.status()).isEqualTo(400);
        assertThat(expiredAnswer.body().replaceAll("\"requestId\":\"[^\"]*\"", "")
                .replaceAll("\"traceId\":\"[^\"]*\"", ""))
                .isEqualTo(garbage.body().replaceAll("\"requestId\":\"[^\"]*\"", "")
                        .replaceAll("\"traceId\":\"[^\"]*\"", ""));
        assertThat(leftAnswer.status()).isEqualTo(400);
        assertThat(IdentityDb.value(String.class, "select token_hash from organization_handoff where user_id = ? "
                + "order by created_at limit 1", person.user().id())).startsWith("sha256:").doesNotContain(proof);
    }

    @Test
    void severalUsesOfOneProofAtOnceHaveOneWinner() throws Exception {
        Organization first = TestOrganizations.create(users);
        Organization second = TestOrganizations.create(users);
        TestUser person = TestUsers.create(users);
        TestOrganizations.join(first.tenant(), person, false);
        TestOrganizations.join(second.tenant(), person, false);
        String proof = tokenOf(switchTo(TestOrganizations.signedIn(port, first.host(), person),
                second.tenant().slug()));
        ExecutorService pool = Executors.newFixedThreadPool(6);
        List<Integer> statuses = new ArrayList<>();
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                results.add(pool.submit(() -> complete(new TestBrowser(port, second.host()), proof).status()));
            }
            for (Future<Integer> result : results) {
                statuses.add(result.get());
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(statuses.stream().filter(status -> status == 204)).hasSize(1);
        assertThat(statuses.stream().filter(status -> status == 400)).hasSize(5);
    }

    @Test
    void aDeactivatedMemberCannotSwitchIntoTheOrganizationTheyLeft() {
        Organization first = TestOrganizations.create(users);
        Organization second = TestOrganizations.create(users);
        Member leaver = TestOrganizations.join(users, second.tenant(), false);
        TestOrganizations.join(first.tenant(), leaver.person(), false);
        TestBrowser admin = TestOrganizations.signedIn(port, second.host(), second.admin().person());
        TestBrowser inFirst = TestOrganizations.signedIn(port, first.host(), leaver.person());
        admin.postJson("/api/v1/members/" + leaver.membership() + "/deactivate", "{}");

        Response refused = switchTo(inFirst, second.tenant().slug());

        assertThat(refused.status()).isEqualTo(404);
    }

    @Test
    void aSwitchNeedsASignInAndTakesNoTenantFromAnythingButTheCallersMemberships() {
        Organization first = TestOrganizations.create(users);
        Organization second = TestOrganizations.create(users);
        TestUser person = TestUsers.create(users);
        TestOrganizations.join(first.tenant(), person, false);
        TestBrowser inFirst = TestOrganizations.signedIn(port, first.host(), person);

        assertThat(switchTo(new TestBrowser(port, first.host()), first.tenant().slug()).status()).isEqualTo(401);
        // A forged tenant in a header or in the body is not a membership.
        Response forged = inFirst.postJson("/api/v1/auth/switch", "{\"slug\":\"" + second.tenant().slug()
                + "\",\"tenantId\":\"" + first.id().value() + "\"}", "X-Tenant-Id", second.id().toString());
        assertThat(forged.status()).isEqualTo(404);
        // The proof is not the access token and not a way to read the API.
        assertThat(new TestHttp(port).get(ME, "Host", second.host(), "Authorization", "Bearer "
                + tokenOf(switchTo(inFirst, first.tenant().slug()))).status()).isEqualTo(401);
    }

    @Test
    void askingForProofsIsLimitedPerPerson() {
        TestWarmUp.redis(port);
        Organization first = TestOrganizations.create(users);
        Organization second = TestOrganizations.create(users);
        TestUser person = TestUsers.create(users);
        TestOrganizations.join(first.tenant(), person, false);
        TestOrganizations.join(second.tenant(), person, false);
        TestBrowser browser = TestOrganizations.signedIn(port, first.host(), person);
        int accepted = 0;
        Response refused = null;
        for (int i = 0; i < 60 && refused == null; i++) {
            Response answer = switchTo(browser, second.tenant().slug());
            if (answer.status() == 200) {
                accepted++;
            } else {
                refused = answer;
            }
        }

        // Twenty a minute. If Redis answered late once the count may have been split in two (ADR-0021), so the limit
        // may
        // arrive at up to twice the number; it must arrive.
        assertThat(refused).as("asking again and again is stopped").isNotNull();
        assertThat(refused.status()).isEqualTo(429);
        assertThat(accepted).isBetween(20, 40);
    }
}
