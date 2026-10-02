package app.platform.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.platform.sharedkernel.ActorId;
import app.platform.testsupport.IdentityDb;
import app.platform.testsupport.IdentityDb.Audit;
import app.platform.testsupport.LogCapture;
import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestDatabase;
import app.platform.testsupport.TestMembers;
import app.platform.testsupport.TestSignIn;
import app.platform.testsupport.TestUsers;
import app.platform.testsupport.TestUsers.TestUser;
import app.platform.testsupport.tenancy.TenantFixtures;
import app.platform.testsupport.tenancy.TenantFixtures.TestTenant;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Stories S3-SEC-11 and S3-SEC-22: every authentication event is audited with its true internal reason and the tenant
 * of the request (none on the platform host), nothing secret is in any record, and the audit table can only be added
 * to.
 */
@PlatformIntegrationTest
class AuditIT {

    private static final ActorId ACTOR = new ActorId(UUID.randomUUID());

    @LocalServerPort
    private int port;

    @Autowired
    private Users users;

    // ---- completeness ----

    @Test
    void everyKindOfAuthenticationEventIsRecorded() throws SQLException {
        Set<String> seen = new HashSet<>();
        TestUser user = TestUsers.create(users);
        TestBrowser browser = new TestBrowser(port, TestSignIn.PLATFORM_HOST);

        // failure, lock, success, token issue, refresh, reuse, password change, sign-out, sign-out-everywhere
        for (int i = 0; i < 5; i++) {
            browser.signInPassword(user.email(), "wrong " + i + " password text");
        }
        IdentityDb.execute("update user_credential set locked_until = now() - interval '1 second', version = version "
                + "+ 1, updated_by = ? where user_id = ?", ACTOR.value(), user.user().id());
        assertThat(IdentityDb.auditOf(user.user().id())).as("before the lock was lifted").isNotEmpty();
        TestBrowser.Session first = browser.signIn(user.email(), user.password());
        browser.post("/api/v1/auth/refresh");
        TestBrowser thief = new TestBrowser(port, TestSignIn.PLATFORM_HOST);
        thief.put("platform_rt", first.refreshToken());
        IdentityDb.execute("update oauth2_authorization set refresh_rotated_at = now() - interval '5 minutes', "
                + "version = version + 1, updated_by = user_id where user_id = ?", user.user().id());
        thief.post("/api/v1/auth/refresh");
        browser.signIn(user.email(), user.password());
        browser.post("/api/v1/auth/sign-out");
        browser.signIn(user.email(), user.password());
        browser.post("/api/v1/auth/sign-out-all");
        users.suspend(user.user().id(), ACTOR);
        // a wrong host
        String hostA = TenantFixtures.createActiveTenant().host();
        TestUser other = TestUsers.create(users);
        TestMembers.addForHost(hostA, other.user().id());
        TestBrowser.Session onA = new TestBrowser(port, hostA).signIn(other.email(), other.password());
        new app.platform.testsupport.TestHttp(port).get("/api/v1/auth/me", "Host", TestSignIn.PLATFORM_HOST,
                "Authorization", onA.bearer());
        users.changePassword(other.user().id(), other.password().toCharArray(),
                "a new violet lantern 61 harbor".toCharArray());

        IdentityDb.auditOf(user.user().id()).forEach(record -> seen.add(record.type()));
        IdentityDb.auditOf(other.user().id()).forEach(record -> seen.add(record.type()));

        assertThat(seen).contains(
                "auth.user.created", "auth.sign_in.failed", "auth.account.locked", "auth.sign_in.succeeded",
                "auth.token.issued", "auth.token.refreshed", "auth.refresh.reuse_detected", "auth.session.revoked",
                "auth.sign_out", "auth.sign_out_all", "auth.user.status_changed", "auth.token.refused",
                "auth.password.changed");
    }

    @Test
    void aRateLimitedAttemptIsAuditedAsDeniedWithTheLimitThatApplied() {
        TestBrowser attacker = new TestBrowser(port, TestSignIn.PLATFORM_HOST);
        String source = "2001:db8:" + Integer.toHexString((int) (Math.random() * 60000) + 1) + ":7:7::1";
        attacker.fromSource(source);
        for (int i = 0; i < 30; i++) {
            attacker.signInPassword("someone-" + i + "@example.test", "some wrong password");
        }

        List<Audit> limited = IdentityDb.auditOfType("auth.sign_in.rate_limited");

        assertThat(limited).isNotEmpty().allSatisfy(record -> {
            assertThat(record.outcome()).isEqualTo("DENIED");
            assertThat(record.reason()).isNotBlank();
        });
    }

    // ---- who and where ----

    @Test
    void anEventOnAnOrganizationHostNamesThatOrganizationAndOneOnThePlatformHostNamesNone() {
        TestUser user = TestUsers.create(users);
        TestTenant tenant = TenantFixtures.createActiveTenant();

        new TestBrowser(port, tenant.host()).signInPassword(user.email(), "wrong password for the record");
        new TestBrowser(port, TestSignIn.PLATFORM_HOST).signInPassword(user.email(), "wrong password for the record");

        List<Audit> failures = IdentityDb.auditOf(user.user().id()).stream()
                .filter(record -> record.type().equals("auth.sign_in.failed")).toList();
        assertThat(failures).hasSize(2);
        assertThat(failures).extracting(Audit::tenantId).containsExactlyInAnyOrder(tenant.id().value(), null);
    }

    @Test
    void anAuditedFailureCarriesTheRequestIdAndNeverTheTypedAddressOfAnUnknownAccount() throws SQLException {
        String stranger = "stranger-" + UUID.randomUUID() + "@example.test";

        new TestBrowser(port, TestSignIn.PLATFORM_HOST).signInPassword(stranger, "wrong password for the record");

        String requestId = IdentityDb.value(String.class, "select request_id from audit_record where "
                + "event_type = 'auth.sign_in.failed' and reason = 'unknown_account' order by occurred_at desc "
                + "limit 1");
        assertThat(requestId).isNotBlank();
        assertThat(IdentityDb.entireAuditTableAsText()).doesNotContain(stranger);
    }

    @Test
    void noSecretIsEverWrittenToTheAuditTable() {
        TestUser user = TestUsers.create(users);
        TestBrowser browser = new TestBrowser(port, TestSignIn.PLATFORM_HOST);
        browser.signInPassword(user.email(), "the-distinctive-wrong-password-7781");
        TestBrowser.Session session = browser.signIn(user.email(), user.password());
        browser.post("/api/v1/auth/refresh");
        browser.post("/api/v1/auth/sign-out");

        String everything = IdentityDb.entireAuditTableAsText();

        assertThat(everything).doesNotContain("the-distinctive-wrong-password-7781").doesNotContain(user.password())
                .doesNotContain(session.accessToken()).doesNotContain(session.refreshToken())
                .doesNotContain("argon2").doesNotContain("sha256:");
    }

    // ---- append-only ----

    @Test
    void anAuditRecordCanNeverBeChangedOrRemovedNotEvenByTheOwner() {
        new TestBrowser(port, TestSignIn.PLATFORM_HOST).signInPassword("nobody@example.test", "x".repeat(20));

        assertThatThrownBy(() -> IdentityDb.execute("update audit_record set reason = 'edited'"))
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> IdentityDb.execute("delete from audit_record")).hasMessageContaining("append-only");
    }

    @Test
    void theApplicationRoleCanOnlyAddRecordsAndCannotTruncate() throws SQLException {
        try (Connection app = TestDatabase.appConnection(); Statement statement = app.createStatement()) {
            assertThatThrownBy(() -> statement.execute("update audit_record set reason = 'edited'"))
                    .hasMessageContaining("append-only");
            assertThatThrownBy(() -> statement.execute("delete from audit_record"))
                    .hasMessageContaining("append-only");
            assertThatThrownBy(() -> statement.execute("truncate audit_record"))
                    .hasMessageContaining("permission denied");
        }
    }

    // ---- never fails the caller ----

    @Test
    void aFailureToWriteAnAuditRecordIsLoggedWithoutContentAndDoesNotFailTheSignIn() throws SQLException {
        TestUser user = TestUsers.create(users);
        try (LogCapture log = LogCapture.of("app.platform.audit.internal.JdbcAuditRecorder")) {
            IdentityDb.execute("revoke insert on audit_record from platform_app");
            try {
                int status = new TestBrowser(port, TestSignIn.PLATFORM_HOST)
                        .signInPassword(user.email(), user.password()).status();

                assertThat(status).as("the sign-in itself still works").isEqualTo(204);
            } finally {
                IdentityDb.execute("grant insert on audit_record to platform_app");
            }

            assertThat(log.events()).anySatisfy(event -> {
                assertThat(event.getFormattedMessage()).contains("auth.sign_in.succeeded")
                        .doesNotContain(user.email()).doesNotContain(user.password());
            });
        }
    }
}
