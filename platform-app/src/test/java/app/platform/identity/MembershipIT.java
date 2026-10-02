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
import com.jayway.jsonpath.JsonPath;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Membership proper (Sprint 5, ADR-0026): the check at sign-in and on every request, deactivating and reactivating
 * members, the administrator marker and the last administrator, with the allowed, denied and cross-tenant cases of
 * every endpoint.
 */
@PlatformIntegrationTest
class MembershipIT {

    private static final String ME = "/api/v1/auth/me";

    @LocalServerPort
    private int port;

    @Autowired
    private Users users;

    private TestBrowser signedIn(Organization organization, TestUser person) {
        return TestOrganizations.signedIn(port, organization.host(), person);
    }

    private static Response deactivate(TestBrowser admin, UUID membership) {
        return admin.postJson("/api/v1/members/" + membership + "/deactivate", "{}");
    }

    private static Response reactivate(TestBrowser admin, UUID membership) {
        return admin.postJson("/api/v1/members/" + membership + "/reactivate", "{}");
    }

    private static Response administrator(TestBrowser admin, UUID membership, boolean value) {
        return admin.request("PUT", "/api/v1/members/" + membership + "/administrator",
                "{\"administrator\":" + value + "}");
    }

    /** What a caller can see of a response, without what is unique to each request. */
    private static String shape(Response response) {
        Map<String, String> headers = new TreeMap<>();
        response.headers().forEach((name, values) -> {
            String lower = name.toLowerCase();
            if (!lower.equals("date") && !lower.equals("x-request-id") && !lower.equals("x-trace-id")
                    && !lower.equals("set-cookie") && !lower.equals("content-length")) {
                headers.put(lower, String.join("|", values));
            }
        });
        return response.status() + " " + headers + " " + response.body().replaceAll("\"requestId\":\"[^\"]*\"", "-")
                .replaceAll("\"traceId\":\"[^\"]*\"", "-");
    }

    // ---- the check at sign-in ----

    @Test
    void aNonMemberIsRefusedAtSignInExactlyLikeAWrongPassword() throws SQLException {
        Organization organization = TestOrganizations.create(users);
        TestUser outsider = TestUsers.create(users);
        String unknown = "nobody-" + UUID.randomUUID() + "@example.test";

        Response notAMember = new TestBrowser(port, organization.host()).signInPassword(outsider.email(),
                outsider.password());
        Response wrongPassword = new TestBrowser(port, organization.host()).signInPassword(outsider.email(),
                "not the password at all");
        Response unknownAddress = new TestBrowser(port, organization.host()).signInPassword(unknown,
                "not the password at all");

        assertThat(notAMember.status()).isEqualTo(401);
        assertThat(shape(notAMember)).isEqualTo(shape(wrongPassword)).isEqualTo(shape(unknownAddress));
        assertThat(notAMember.header("Set-Cookie")).as("no session of any kind").isEmpty();
        assertThat(IdentityDb.auditOf(outsider.user().id()))
                .anyMatch(record -> "not_a_member".equals(record.reason())
                        && organization.id().value().equals(record.tenantId()));
        // The same person signs in on the platform host, where there is no organization to belong to.
        assertThat(new TestBrowser(port, TestSignIn.PLATFORM_HOST).signInPassword(outsider.email(),
                outsider.password()).status()).isEqualTo(204);
    }

    @Test
    void aMemberOfOneOrganizationCannotSignInToAnotherOrSwapTheHost() {
        Organization first = TestOrganizations.create(users);
        Organization second = TestOrganizations.create(users);
        TestUser person = TestUsers.create(users);
        TestOrganizations.join(first.tenant(), person, false);
        TestBrowser inFirst = signedIn(first, person);

        assertThat(inFirst.get(ME).status()).isEqualTo(200);
        assertThat(new TestBrowser(port, second.host()).signInPassword(person.email(), person.password()).status())
                .as("wrong organization").isEqualTo(401);
        // The first organization's token, swapped onto the second host, by header or by forwarded host.
        String bearer = inFirst.signIn(person.email(), person.password()).bearer();
        assertThat(new TestHttp(port).get(ME, "Host", second.host(), "Authorization", bearer).status()).isEqualTo(401);
        assertThat(new TestHttp(port).get(ME, "Host", first.host(), "X-Forwarded-Host", second.host(),
                "X-Tenant-Id", second.id().toString(), "Authorization", bearer).status())
                .as("a forged header changes nothing").isEqualTo(200);
    }

    // ---- deactivate and reactivate ----

    @Test
    void aDeactivatedMemberIsSignedOutAtOnceAndCannotGetBackButOtherOrganizationsKeepWorking() throws SQLException {
        Organization organization = TestOrganizations.create(users);
        Organization elsewhere = TestOrganizations.create(users);
        TestBrowser admin = signedIn(organization, organization.admin().person());
        Member member = TestOrganizations.join(users, organization.tenant(), false);
        TestOrganizations.join(elsewhere.tenant(), member.person(), false);
        TestBrowser inside = signedIn(organization, member.person());
        TestBrowser inElsewhere = signedIn(elsewhere, member.person());
        TestBrowser onPlatform = new TestBrowser(port, TestSignIn.PLATFORM_HOST);
        onPlatform.signIn(member.person().email(), member.person().password());
        String bearer = inside.signIn(member.person().email(), member.person().password()).bearer();
        assertThat(inside.get(ME).status()).isEqualTo(200);

        Response deactivated = deactivate(admin, member.membership());

        assertThat(deactivated.status()).isEqualTo(204);
        assertThat(inside.get(ME).status()).as("the cookie session").isEqualTo(401);
        assertThat(new TestHttp(port).get(ME, "Host", organization.host(), "Authorization", bearer).status())
                .as("the access token").isEqualTo(401);
        assertThat(inside.post("/api/v1/auth/refresh").status()).as("the refresh token").isEqualTo(401);
        assertThat(inElsewhere.get(ME).status()).as("another organization").isEqualTo(200);
        assertThat(onPlatform.get(ME).status()).as("the platform host").isEqualTo(200);
        Response back = new TestBrowser(port, organization.host()).signInPassword(member.person().email(),
                member.person().password());
        assertThat(back.status()).isEqualTo(401);
        assertThat(shape(back)).isEqualTo(shape(new TestBrowser(port, organization.host())
                .signInPassword(member.person().email(), "wrong password")));
        assertThat(IdentityDb.auditOfType("membership.deactivated"))
                .anyMatch(record -> member.person().user().id().equals(record.userId())
                        && organization.id().value().equals(record.tenantId()));
        assertThat(IdentityDb.value(String.class, "select status from membership where id = ?",
                member.membership())).isEqualTo("DEACTIVATED");
    }

    @Test
    void aReactivatedMemberSignsInAgainAndOldTokensDoNotReturn() {
        Organization organization = TestOrganizations.create(users);
        TestBrowser admin = signedIn(organization, organization.admin().person());
        Member member = TestOrganizations.join(users, organization.tenant(), false);
        TestBrowser inside = signedIn(organization, member.person());
        String oldBearer = inside.signIn(member.person().email(), member.person().password()).bearer();
        String oldRefresh = inside.cookie("platform_rt").orElseThrow();
        deactivate(admin, member.membership());

        Response reactivated = reactivate(admin, member.membership());
        TestBrowser fresh = new TestBrowser(port, organization.host());
        fresh.signIn(member.person().email(), member.person().password());

        assertThat(reactivated.status()).isEqualTo(204);
        assertThat(fresh.get(ME).status()).isEqualTo(200);
        assertThat(new TestHttp(port).get(ME, "Host", organization.host(), "Authorization", oldBearer).status())
                .as("the old access token stays dead").isEqualTo(401);
        TestBrowser replay = new TestBrowser(port, organization.host());
        replay.put("platform_rt", oldRefresh);
        assertThat(replay.post("/api/v1/auth/refresh").status()).as("the old refresh token stays dead")
                .isEqualTo(401);
        assertThat(reactivate(admin, member.membership()).status()).as("not deactivated any more").isEqualTo(409);
    }

    @Test
    void aReactivatedAdministratorIsNotAnAdministratorUntilNamedAgain() {
        Organization organization = TestOrganizations.create(users);
        TestBrowser admin = signedIn(organization, organization.admin().person());
        Member second = TestOrganizations.join(users, organization.tenant(), true);
        deactivate(admin, second.membership());
        reactivate(admin, second.membership());

        TestBrowser again = signedIn(organization, second.person());

        assertThat(again.get("/api/v1/members").status()).isEqualTo(403);
        assertThat(administrator(admin, second.membership(), true).status()).isEqualTo(204);
        assertThat(again.get("/api/v1/members").status()).isEqualTo(200);
    }

    // ---- the last administrator ----

    @Test
    void theLastAdministratorCannotBeDeactivatedOrReleasedAndAnotherOneLetsTheFounderLeave() throws SQLException {
        Organization organization = TestOrganizations.create(users);
        TestBrowser founder = signedIn(organization, organization.admin().person());
        Member plain = TestOrganizations.join(users, organization.tenant(), false);

        Response deactivateLast = deactivate(founder, organization.admin().membership());
        Response releaseLast = administrator(founder, organization.admin().membership(), false);

        assertThat(deactivateLast.status()).isEqualTo(409);
        assertThat(JsonPath.<String>read(deactivateLast.body(), "$.error.message")).contains("last administrator");
        assertThat(releaseLast.status()).isEqualTo(409);
        assertThat(administrator(founder, plain.membership(), true).status()).isEqualTo(204);
        // Now the founder can step aside, and the organization is not stuck.
        assertThat(deactivate(founder, organization.admin().membership()).status()).isEqualTo(204);
        TestBrowser second = signedIn(organization, plain.person());
        assertThat(second.get("/api/v1/members").status()).isEqualTo(200);
        assertThat(deactivate(second, plain.membership()).status()).as("and now the second is the last")
                .isEqualTo(409);
        assertThat(IdentityDb.value(Long.class, "select count(*) from membership where tenant_id = ? and "
                + "administrator and status = 'ACTIVE'", organization.id().value())).isEqualTo(1L);
    }

    @Test
    void twoAdministratorsDeactivatingEachOtherAtOnceLeaveOne() throws Exception {
        for (int round = 0; round < 3; round++) {
            Organization organization = TestOrganizations.create(users);
            Member other = TestOrganizations.join(users, organization.tenant(), true);
            TestBrowser first = signedIn(organization, organization.admin().person());
            TestBrowser second = signedIn(organization, other.person());
            CountDownLatch go = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                Future<Response> a = pool.submit(() -> {
                    go.await();
                    return deactivate(first, other.membership());
                });
                Future<Response> b = pool.submit(() -> {
                    go.await();
                    return deactivate(second, organization.admin().membership());
                });
                go.countDown();
                List<Integer> statuses = List.of(a.get().status(), b.get().status());

                assertThat(statuses).as("one wins, the other is refused, never both").containsExactlyInAnyOrder(204,
                        409);
            } finally {
                pool.shutdownNow();
            }
            assertThat(IdentityDb.value(Long.class, "select count(*) from membership where tenant_id = ? and "
                    + "administrator and status = 'ACTIVE'", organization.id().value())).isEqualTo(1L);
        }
    }

    @Test
    void deactivatingAndReactivatingOneMemberAtOnceNeverBreaksAnything() throws Exception {
        Organization organization = TestOrganizations.create(users);
        Member member = TestOrganizations.join(users, organization.tenant(), false);
        List<TestBrowser> admins = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            Member extra = TestOrganizations.join(users, organization.tenant(), true);
            admins.add(signedIn(organization, extra.person()));
        }
        ExecutorService pool = Executors.newFixedThreadPool(4);
        List<Integer> statuses = new ArrayList<>();
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < admins.size(); i++) {
                TestBrowser admin = admins.get(i);
                boolean down = i % 2 == 0;
                results.add(pool.submit(() -> (down ? deactivate(admin, member.membership())
                        : reactivate(admin, member.membership())).status()));
            }
            for (Future<Integer> result : results) {
                statuses.add(result.get());
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(statuses).allMatch(status -> status == 204 || status == 409);
        assertThat(IdentityDb.value(String.class, "select status from membership where id = ?",
                member.membership())).isIn("ACTIVE", "DEACTIVATED");
    }

    // ---- authorization of every endpoint: allowed, denied, cross-tenant ----

    @Test
    void onlyAdministratorsMayListAndChangeMembers() throws SQLException {
        Organization organization = TestOrganizations.create(users);
        TestBrowser admin = signedIn(organization, organization.admin().person());
        Member plain = TestOrganizations.join(users, organization.tenant(), false);
        Member target = TestOrganizations.join(users, organization.tenant(), false);
        TestBrowser member = signedIn(organization, plain.person());

        // allowed
        Response list = admin.get("/api/v1/members");
        assertThat(list.status()).isEqualTo(200);
        assertThat(JsonPath.<List<?>>read(list.body(), "$.data")).hasSize(3);
        assertThat(list.body()).contains("\"you\":true").doesNotContain("password").doesNotContain("token");
        // denied: a member who is not an administrator, and callers who are not signed in
        assertThat(member.get("/api/v1/members").status()).isEqualTo(403);
        assertThat(deactivate(member, target.membership()).status()).isEqualTo(403);
        assertThat(reactivate(member, target.membership()).status()).isEqualTo(403);
        assertThat(administrator(member, target.membership(), true).status()).isEqualTo(403);
        TestBrowser anonymous = new TestBrowser(port, organization.host());
        assertThat(anonymous.get("/api/v1/members").status()).isEqualTo(401);
        assertThat(deactivate(anonymous, target.membership()).status()).isEqualTo(401);
        assertThat(IdentityDb.value(String.class, "select status from membership where id = ?", target.membership()))
                .isEqualTo("ACTIVE");
    }

    @Test
    void anAdministratorOfAnotherOrganizationFindsNothingOfThisOne() {
        Organization organization = TestOrganizations.create(users);
        Organization stranger = TestOrganizations.create(users);
        Member target = TestOrganizations.join(users, organization.tenant(), false);
        TestBrowser outsider = signedIn(stranger, stranger.admin().person());

        assertThat(deactivate(outsider, target.membership()).status()).isEqualTo(404);
        assertThat(reactivate(outsider, target.membership()).status()).isEqualTo(404);
        assertThat(administrator(outsider, target.membership(), true).status()).isEqualTo(404);
        assertThat(JsonPath.<List<?>>read(outsider.get("/api/v1/members").body(), "$.data")).hasSize(1);
        // A forged tenant header or host header on the outsider's own host changes nothing.
        String bearer = outsider.signIn(stranger.admin().person().email(), stranger.admin().person().password())
                .bearer();
        Response forged = new TestHttp(port).request("POST", "/api/v1/members/" + target.membership() + "/deactivate",
                "{}", "Host", stranger.host(), "X-Tenant-Id", organization.id().toString(), "X-Forwarded-Host",
                organization.host(), "Authorization", bearer, "Content-Type", "application/json");
        assertThat(forged.status()).isEqualTo(404);
        // The token of the other organization is no good on this organization's host.
        Response swapped = new TestHttp(port).request("POST", "/api/v1/members/" + target.membership() + "/deactivate",
                "{}", "Host", organization.host(), "Authorization", bearer, "Content-Type", "application/json");
        assertThat(swapped.status()).isEqualTo(401);
        assertThat(IdentityDb.auditOfType("membership.deactivated")
                .stream().noneMatch(record -> target.person().user().id().equals(record.userId()))).isTrue();
    }

    @Test
    void membersAreNotAvailableOnThePlatformHost() {
        Organization organization = TestOrganizations.create(users);
        TestBrowser onPlatform = new TestBrowser(port, TestSignIn.PLATFORM_HOST);
        onPlatform.signIn(organization.admin().person().email(), organization.admin().person().password());

        assertThat(onPlatform.get("/api/v1/members").status()).isEqualTo(404);
        assertThat(deactivate(onPlatform, organization.admin().membership()).status()).isEqualTo(404);
    }

    @Test
    void aMemberIdThatIsNotAnIdentifierIsRefusedAndNothingChanges() {
        Organization organization = TestOrganizations.create(users);
        TestBrowser admin = signedIn(organization, organization.admin().person());

        assertThat(admin.postJson("/api/v1/members/not-an-id/deactivate", "{}").status()).isEqualTo(400);
        assertThat(deactivate(admin, UUID.randomUUID()).status()).isEqualTo(404);
    }
}
