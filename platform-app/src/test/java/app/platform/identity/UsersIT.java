package app.platform.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.platform.sharedkernel.ActorId;
import app.platform.testsupport.IdentityDb;
import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestDatabase;
import app.platform.testsupport.TestSignIn;
import app.platform.testsupport.TestUsers;
import app.platformapi.ApiException;
import app.platformapi.ErrorCode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Users, their lifecycle and their passwords on a real PostgreSQL: creation and the address rules, the lifecycle
 * enforced by the module and by the database trigger (and the two agree for every pair of states), the security
 * version, password change and reset, and the audit record of each change.
 */
@PlatformIntegrationTest
class UsersIT {

    private static final ActorId ACTOR = new ActorId(UUID.randomUUID());
    private static final String STRONG = "violet lantern ocean 77 mountain";

    @Autowired
    private Users users;

    @LocalServerPort
    private int port;

    private static String newEmail() {
        return "user-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12) + "@example.test";
    }

    private User active() {
        return users.createActive(newEmail(), "Test User", STRONG.toCharArray(), ACTOR);
    }

    // ---- creation ----

    @Test
    void anActiveUserIsCreatedVerifiedWithTheAddressInLowerCase() {
        String email = "User-" + UUID.randomUUID().toString().substring(0, 8) + "@Example.TEST";

        User user = users.createActive(email, "Test User", STRONG.toCharArray(), ACTOR);

        assertThat(user.email()).isEqualTo(email.toLowerCase());
        assertThat(user.status()).isEqualTo(UserStatus.ACTIVE);
        assertThat(user.emailVerifiedAt()).isNotNull();
        assertThat(users.findByEmail(email.toUpperCase()).map(User::id)).contains(user.id());
    }

    @Test
    void anInvitedUserHasNoPasswordAndCannotSignIn() throws SQLException {
        User invited = users.createInvited(newEmail(), "Invited", ACTOR);

        assertThat(invited.status()).isEqualTo(UserStatus.INVITED);
        assertThat(IdentityDb.value(Long.class, "select count(*) from user_credential where user_id = ?",
                invited.id())).isZero();
        assertThat(new TestBrowser(port, TestSignIn.PLATFORM_HOST)
                .signInPassword(invited.email(), "any password at all here").status()).isEqualTo(401);
    }

    @Test
    void theSameAddressInAnotherSpellingIsRefusedAsAConflictWithoutNamingTheOtherAccount() {
        User first = active();

        assertThatThrownBy(() -> users.createActive(first.email().toUpperCase(), "Other", STRONG.toCharArray(),
                ACTOR))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.CONFLICT));
    }

    @Test
    void twoSimultaneousCreationsOfOneAddressHaveOneWinner() throws Exception {
        String email = newEmail();
        int attempts = 6;
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        try {
            List<Future<Boolean>> results = new java.util.ArrayList<>();
            for (int i = 0; i < attempts; i++) {
                results.add(pool.submit(() -> {
                    go.await();
                    try {
                        users.createActive(email, "Racer", STRONG.toCharArray(), ACTOR);
                        return true;
                    } catch (ApiException e) {
                        return false;
                    }
                }));
            }
            go.countDown();
            int winners = 0;
            for (Future<Boolean> result : results) {
                winners += result.get() ? 1 : 0;
            }

            assertThat(winners).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void badInputIsRefusedWithTheFieldAndWithoutRepeatingTheValue() {
        assertThatThrownBy(() -> users.createActive("not-an-address", "Name", STRONG.toCharArray(), ACTOR))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.fields()).containsKey("email"));
        assertThatThrownBy(() -> users.createActive(newEmail(), " padded ", STRONG.toCharArray(), ACTOR))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.fields()).containsKey("displayName"));
        assertThatThrownBy(() -> users.createActive(newEmail(), "Name", "short".toCharArray(), ACTOR))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.fields()).containsKey("password");
                    assertThat(e.fields().toString()).doesNotContain("short");
                });
    }

    @Test
    void theStoredHashIsArgon2idAndNoPasswordIsStoredAnywhere() throws SQLException {
        User user = active();

        String hash = IdentityDb.value(String.class, "select password_hash from user_credential where user_id = ?",
                user.id());

        assertThat(hash).startsWith("$argon2id$").doesNotContain(STRONG);
        assertThat(IdentityDb.entireAuditTableAsText()).doesNotContain(STRONG);
    }

    // ---- activation ----

    @Test
    void anInvitedUserIsActivatedWithAPasswordAndOnlyOnce() {
        User invited = users.createInvited(newEmail(), "Invited", ACTOR);

        User activated = users.activate(invited.id(), STRONG.toCharArray(), ACTOR);

        assertThat(activated.status()).isEqualTo(UserStatus.ACTIVE);
        assertThat(activated.emailVerifiedAt()).isNotNull();
        assertThatThrownBy(() -> users.activate(invited.id(), STRONG.toCharArray(), ACTOR))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.CONFLICT));
    }

    // ---- lifecycle ----

    @Test
    void suspendReinstateAndDeactivateFollowTheLegalPath() {
        User user = active();

        assertThat(users.suspend(user.id(), ACTOR).status()).isEqualTo(UserStatus.SUSPENDED);
        assertThat(users.reinstate(user.id(), ACTOR).status()).isEqualTo(UserStatus.ACTIVE);
        assertThat(users.deactivate(user.id(), ACTOR).status()).isEqualTo(UserStatus.DEACTIVATED);
    }

    @Test
    void anIllegalMoveIsAConflictAndAFinalUserStaysFinal() {
        User user = active();
        users.deactivate(user.id(), ACTOR);

        assertThatThrownBy(() -> users.reinstate(user.id(), ACTOR)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> users.suspend(user.id(), ACTOR)).isInstanceOf(ApiException.class);
        assertThat(users.findById(user.id()).orElseThrow().status()).isEqualTo(UserStatus.DEACTIVATED);
    }

    @Test
    void theModuleAndTheDatabaseTriggerAgreeForEveryPairOfStates() throws SQLException {
        for (UserStatus from : UserStatus.values()) {
            for (UserStatus to : UserStatus.values()) {
                if (from == to) {
                    continue;
                }
                User user = userIn(from);

                boolean databaseAccepts = databaseAccepts(user, to);

                assertThat(databaseAccepts).as(from + " to " + to).isEqualTo(from.canTransitionTo(to));
            }
        }
    }

    @Test
    void aUserCannotStartAnywhereButInvited() {
        assertThatThrownBy(() -> IdentityDb.execute("insert into platform_user (email, display_name, status, "
                + "created_by, updated_by) values (?, 'X', 'ACTIVE', ?, ?)", newEmail(), ACTOR.value(), ACTOR.value()))
                .hasMessageContaining("starts as INVITED");
    }

    // ---- security version ----

    @Test
    void leavingActiveRaisesTheSecurityVersionAndComingBackKeepsItRaised() {
        User user = active();

        User suspended = users.suspend(user.id(), ACTOR);
        User reinstated = users.reinstate(user.id(), ACTOR);

        assertThat(suspended.securityVersion()).isGreaterThan(user.securityVersion());
        assertThat(reinstated.securityVersion()).isEqualTo(suspended.securityVersion());
    }

    @Test
    void theDatabaseRaisesTheVersionEvenForAHandWrittenStatement() throws SQLException {
        User user = active();

        IdentityDb.execute("update platform_user set status = 'SUSPENDED', version = version + 1, updated_by = ? "
                + "where id = ?", ACTOR.value(), user.id());

        assertThat(users.findById(user.id()).orElseThrow().securityVersion()).isGreaterThan(user.securityVersion());
    }

    @Test
    void theSecurityVersionNeverGoesDown() {
        User user = active();
        users.signOutEverywhere(user.id(), ACTOR);

        assertThatThrownBy(() -> IdentityDb.execute("update platform_user set security_version = 0, "
                + "version = version + 1, updated_by = ? where id = ?", ACTOR.value(), user.id()))
                .hasMessageContaining("never goes down");
    }

    @Test
    void signingOutEverywhereRaisesTheVersion() {
        User user = active();

        users.signOutEverywhere(user.id(), ACTOR);

        assertThat(users.findById(user.id()).orElseThrow().securityVersion()).isGreaterThan(user.securityVersion());
    }

    // ---- passwords ----

    @Test
    void changingThePasswordNeedsTheCurrentOneAndTheNewOneWorksAfterwards() {
        TestUsers.TestUser user = TestUsers.create(users);
        String next = "another violet lantern 88 ocean";

        users.changePassword(user.user().id(), user.password().toCharArray(), next.toCharArray());

        TestBrowser browser = new TestBrowser(port, TestSignIn.PLATFORM_HOST);
        assertThat(browser.signInPassword(user.email(), user.password()).status()).isEqualTo(401);
        assertThat(browser.signInPassword(user.email(), next).status()).isEqualTo(204);
        assertThat(users.findById(user.user().id()).orElseThrow().securityVersion())
                .isGreaterThan(user.user().securityVersion());
    }

    @Test
    void aWrongCurrentPasswordIsRefusedAndAudited() {
        TestUsers.TestUser user = TestUsers.create(users);

        assertThatThrownBy(() -> users.changePassword(user.user().id(), "not the password".toCharArray(),
                "another violet lantern 88 ocean".toCharArray()))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.fields()).containsKey("currentPassword"));

        assertThat(IdentityDb.auditOf(user.user().id())).anyMatch(
                record -> record.type().equals("auth.password.change_refused")
                        && "wrong_current_password".equals(record.reason()));
    }

    @Test
    void theNewPasswordMustMeetThePolicyAndDifferFromTheCurrentOne() {
        TestUsers.TestUser user = TestUsers.create(users);

        assertThatThrownBy(() -> users.changePassword(user.user().id(), user.password().toCharArray(),
                "short".toCharArray()))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.fields()).containsKey("newPassword"));
        assertThatThrownBy(() -> users.changePassword(user.user().id(), user.password().toCharArray(),
                user.password().toCharArray()))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.fields().get("newPassword"))
                        .contains("Must differ from the current password."));
    }

    @Test
    void guessingTheCurrentPasswordThroughTheChangeFormIsRateLimited() {
        TestUsers.TestUser user = TestUsers.create(users);
        ApiException last = null;

        for (int i = 0; i < 12; i++) {
            try {
                users.changePassword(user.user().id(), ("guess number " + i + " of the password").toCharArray(),
                        "another violet lantern 88 ocean".toCharArray());
            } catch (ApiException e) {
                last = e;
            }
        }

        assertThat(last).isNotNull();
        assertThat(last.code()).isEqualTo(ErrorCode.RATE_LIMITED);
        assertThat(last.retryAfterSeconds()).isPositive();
    }

    @Test
    void aResetNeedsNoCurrentPasswordButKeepsThePolicy() {
        TestUsers.TestUser user = TestUsers.create(users);

        assertThatThrownBy(() -> users.resetPassword(user.user().id(), "password123456".toCharArray(), ACTOR))
                .isInstanceOf(ApiException.class);
        users.resetPassword(user.user().id(), "a reset violet lantern 99 sea".toCharArray(), ACTOR);

        assertThat(new TestBrowser(port, TestSignIn.PLATFORM_HOST)
                .signInPassword(user.email(), "a reset violet lantern 99 sea").status()).isEqualTo(204);
    }

    // ---- audit ----

    @Test
    void creationAndEveryStatusChangeLeaveAnAuditRecordWithoutSecrets() {
        User user = active();
        users.suspend(user.id(), ACTOR);
        users.reinstate(user.id(), ACTOR);

        List<IdentityDb.Audit> records = IdentityDb.auditOf(user.id());

        assertThat(records).extracting(IdentityDb.Audit::type).contains("auth.user.created",
                "auth.user.status_changed");
        assertThat(records.stream().filter(r -> r.type().equals("auth.user.status_changed")).toList()).hasSize(3);
        assertThat(records.toString()).doesNotContain(STRONG);
    }

    // ---- helpers ----

    private User userIn(UserStatus status) {
        return switch (status) {
            case INVITED -> users.createInvited(newEmail(), "Invited", ACTOR);
            case ACTIVE -> active();
            case SUSPENDED -> {
                User user = active();
                yield users.suspend(user.id(), ACTOR);
            }
            case DEACTIVATED -> {
                User user = active();
                yield users.deactivate(user.id(), ACTOR);
            }
        };
    }

    private static boolean databaseAccepts(User user, UserStatus to) throws SQLException {
        try (Connection owner = TestDatabase.ownerConnection();
                PreparedStatement update = owner.prepareStatement(
                        "update platform_user set status = ?, updated_by = ?, version = version + 1 where id = ?")) {
            update.setString(1, to.name());
            update.setObject(2, ACTOR.value());
            update.setObject(3, user.id());
            update.executeUpdate();
            return true;
        } catch (SQLException e) {
            assertThat(e.getSQLState()).isEqualTo("23514");
            return false;
        }
    }
}
