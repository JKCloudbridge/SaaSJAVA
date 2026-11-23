package app.platform.integration;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.sharedkernel.ActorId;
import app.platform.sharedkernel.TenantId;
import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestDatabase;
import app.platform.testsupport.tenancy.TenantFixtures;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * What the database itself holds for the webhooks (V096, ADR-0274 to ADR-0278), whatever the application does: a
 * subscription starts switched off, is changed only while it is off, is switched on only for an address that was
 * verified exactly as it stands, moves only along the three states and never changes the member it acts as; one event
 * makes one delivery; a finished delivery is final and a dead letter comes back only as a replay of the next
 * generation;
 * an attempt is never changed; a delivery is deleted only when it has ended and is a day old; and the limit override is
 * one row inside its ranges. Each rule is tried with direct statements as the application role and as the owner of the
 * database, because a revoked grant would not hold.
 */
@PlatformIntegrationTest
class WebhookGuardIT {

    enum Role { APP, OWNER }

    private static final UUID ACTOR = ActorId.SYSTEM.value();
    private static final String CHECK_VIOLATION = "23514";
    private static final String UNIQUE_VIOLATION = "23505";
    private static final String FINGERPRINT = "f".repeat(64);

    private record World(TenantId tenant, UUID user, UUID membership) {
    }

    private static int run(Role role, TenantId tenant, String sql, Object... parameters) throws SQLException {
        if (role == Role.APP) {
            return TenantFixtures.asTenant(tenant, connection -> TenantFixtures.update(connection, sql, parameters));
        }
        try (Connection connection = TestDatabase.ownerConnection()) {
            connection.setAutoCommit(false);
            try {
                TenantFixtures.setTenant(connection, tenant);
                int changed = TenantFixtures.update(connection, sql, parameters);
                connection.commit();
                return changed;
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            }
        }
    }

    private static String stateOf(Role role, TenantId tenant, String sql, Object... parameters) {
        try {
            run(role, tenant, sql, parameters);
            return null;
        } catch (SQLException e) {
            return e.getSQLState();
        }
    }

    private static World world() throws SQLException {
        TenantId tenant = TenantFixtures.createActiveTenant().id();
        UUID user = UUID.randomUUID();
        UUID membership = UUID.randomUUID();
        TenantFixtures.asTenant(tenant, connection -> {
            TenantFixtures.update(connection, "insert into platform_user (id, email, display_name, created_by, "
                    + "updated_by) values (?, ?, 'Guard', ?, ?)", user, "guard-" + user + "@example.test", ACTOR,
                    ACTOR);
            return TenantFixtures.update(connection, "insert into membership (id, tenant_id, user_id, created_by, "
                    + "updated_by) values (?, ?, ?, ?, ?)", membership, tenant.value(), user, ACTOR, ACTOR);
        });
        return new World(tenant, user, membership);
    }

    private static UUID subscription(World world) throws SQLException {
        UUID id = UUID.randomUUID();
        run(Role.APP, world.tenant(), "insert into webhook_subscription (id, tenant_id, name, address, events, "
                + "membership_id, user_id, fingerprint, created_by, updated_by) values (?, ?, ?, "
                + "'https://hooks.example.com/in', array['record.created'], ?, ?, ?, ?, ?)", id,
                world.tenant().value(), "hook-" + UUID.randomUUID().toString().substring(0, 8), world.membership(),
                world.user(), FINGERPRINT, ACTOR, ACTOR);
        return id;
    }

    private static String change(String set) {
        return "update webhook_subscription set " + set + ", version = version + 1, updated_by = ? where id = ?";
    }

    private static void verifyAndSwitchOn(Role role, World world, UUID id) throws SQLException {
        run(role, world.tenant(), change("verified_fingerprint = fingerprint, verified_at = now()"), ACTOR, id);
        run(role, world.tenant(), change("state = 'ACTIVE', disabled_reason = null"), ACTOR, id);
    }

    private static UUID delivery(World world, UUID subscription, String state) throws SQLException {
        UUID id = UUID.randomUUID();
        run(Role.APP, world.tenant(), "insert into webhook_delivery (id, tenant_id, subscription_id, event_id, "
                + "event_type, object_api_name, record_id, state, finished_at, created_by, updated_by) values "
                + "(?, ?, ?, ?, 'record.created', 'ObjectA__c', ?, ?, case when ? = 'PENDING' then null else now() "
                + "end, ?, ?)", id, world.tenant().value(), subscription, UUID.randomUUID(), UUID.randomUUID(), state,
                state, ACTOR, ACTOR);
        return id;
    }

    private static String move(String set) {
        return "update webhook_delivery set " + set + ", version = version + 1, updated_by = ? where id = ?";
    }

    // ---- the subscription ----

    @Test
    void aSubscriptionStartsSwitchedOffAndIsSwitchedOnOnlyForAnAddressThatWasVerifiedAsItStands() throws SQLException {
        for (Role role : Role.values()) {
            World world = world();
            assertThat(stateOf(role, world.tenant(), "insert into webhook_subscription (tenant_id, name, address, "
                    + "events, membership_id, user_id, fingerprint, state, disabled_reason, created_by, updated_by) "
                    + "values (?, 'starts-on', 'https://hooks.example.com/in', array['record.created'], ?, ?, ?, "
                    + "'ACTIVE', null, ?, ?)", world.tenant().value(), world.membership(), world.user(), FINGERPRINT,
                    ACTOR, ACTOR)).as(role + " cannot start one that is on").isEqualTo(CHECK_VIOLATION);
            UUID id = subscription(world);

            assertThat(stateOf(role, world.tenant(), change("state = 'ACTIVE', disabled_reason = null"), ACTOR, id))
                    .as(role + " cannot switch on an unverified address").isEqualTo(CHECK_VIOLATION);
            run(role, world.tenant(), change("verified_fingerprint = fingerprint, verified_at = now()"), ACTOR, id);
            assertThat(stateOf(role, world.tenant(), change("state = 'ACTIVE', disabled_reason = null"), ACTOR, id))
                    .as(role + " can switch on a verified address").isNull();
        }
    }

    @Test
    void aSubscriptionIsChangedOnlyWhileItIsOffAndTheVerificationBelongsToOneFingerprint() throws SQLException {
        for (Role role : Role.values()) {
            World world = world();
            UUID id = subscription(world);
            assertThat(stateOf(role, world.tenant(), change("address = 'https://hooks.example.com/other'"), ACTOR,
                    id)).as(role + " may change it while it is off").isNull();
            verifyAndSwitchOn(role, world, id);

            assertThat(stateOf(role, world.tenant(), change("address = 'https://hooks.example.com/third'"), ACTOR,
                    id)).as(role + " cannot change it while it is on").isEqualTo(CHECK_VIOLATION);
            assertThat(stateOf(role, world.tenant(), change("events = array['record.updated']"), ACTOR, id))
                    .isEqualTo(CHECK_VIOLATION);
            assertThat(stateOf(role, world.tenant(), change("fingerprint = '" + "a".repeat(64) + "'"), ACTOR, id))
                    .isEqualTo(CHECK_VIOLATION);
            assertThat(stateOf(role, world.tenant(), change("name = 'renamed'"), ACTOR, id))
                    .isEqualTo(CHECK_VIOLATION);
        }
    }

    @Test
    void theStatesMoveOnlyAlongTheTableOfTheMachine() throws SQLException {
        for (Role role : Role.values()) {
            World world = world();
            UUID id = subscription(world);
            assertThat(stateOf(role, world.tenant(), change("state = 'FAILING', disabled_reason = null"), ACTOR, id))
                    .as(role + ": off to failing").isEqualTo(CHECK_VIOLATION);
            verifyAndSwitchOn(role, world, id);

            assertThat(stateOf(role, world.tenant(), change("state = 'FAILING'"), ACTOR, id)).as("active to failing")
                    .isNull();
            assertThat(stateOf(role, world.tenant(), change("state = 'ACTIVE'"), ACTOR, id)).as("failing to active")
                    .isNull();
            assertThat(stateOf(role, world.tenant(), change("state = 'DISABLED', disabled_reason = 'auto_disabled'"),
                    ACTOR, id)).as("active to off").isNull();
        }
    }

    @Test
    void theMemberASubscriptionActsAsNeverChangesAndASecretNumberNeverGoesBack() throws SQLException {
        for (Role role : Role.values()) {
            World world = world();
            World other = world();
            UUID id = subscription(world);

            assertThat(stateOf(role, world.tenant(), change("user_id = '" + other.user() + "'"), ACTOR, id))
                    .as(role + " cannot change the user").isEqualTo(CHECK_VIOLATION);
            assertThat(stateOf(role, world.tenant(), change("membership_id = '" + other.membership() + "'"), ACTOR,
                    id)).as(role + " cannot change the membership").isNotNull();
            run(role, world.tenant(), change("secret_number = 3"), ACTOR, id);
            assertThat(stateOf(role, world.tenant(), change("secret_number = 2"), ACTOR, id))
                    .as(role + " cannot take a secret number back").isNotNull();
        }
    }

    @Test
    void aSubscriptionIsNeverDeletedAndARemovedOneStaysRemoved() throws SQLException {
        for (Role role : Role.values()) {
            World world = world();
            UUID id = subscription(world);

            int deleted;
            try {
                deleted = run(role, world.tenant(), "delete from webhook_subscription where id = ?", id);
            } catch (SQLException refused) {
                deleted = 0;
            }
            assertThat(deleted).as(role + " cannot delete").isZero();
            run(role, world.tenant(), change("deleted_at = now(), deleted_by = '" + ACTOR + "'"), ACTOR, id);
            assertThat(stateOf(role, world.tenant(), change("deleted_at = null, deleted_by = null"), ACTOR, id))
                    .as(role + " cannot bring a removed one back").isNotNull();
        }
    }

    // ---- the delivery ----

    @Test
    void aDeliveryStartsQueuedSkippedOrDeadLetteredAndOneEventMakesOneDelivery() throws SQLException {
        for (Role role : Role.values()) {
            World world = world();
            UUID subscription = subscription(world);
            for (String state : new String[] {"DONE", "SENDING", "SUPERSEDED"}) {
                assertThat(stateOf(role, world.tenant(), "insert into webhook_delivery (tenant_id, subscription_id, "
                        + "event_id, event_type, object_api_name, record_id, state, finished_at, created_by, "
                        + "updated_by) values (?, ?, ?, 'record.created', 'ObjectA__c', ?, ?, "
                        + "case when ? = 'SENDING' then null else now() end, ?, ?)", world.tenant().value(),
                        subscription, UUID.randomUUID(), UUID.randomUUID(), state, state, ACTOR, ACTOR))
                        .as(role + " cannot start " + state).isEqualTo(CHECK_VIOLATION);
            }
            UUID event = UUID.randomUUID();
            String insert = "insert into webhook_delivery (tenant_id, subscription_id, event_id, event_type, "
                    + "object_api_name, record_id, created_by, updated_by) values (?, ?, ?, 'record.created', "
                    + "'ObjectA__c', ?, ?, ?)";
            assertThat(stateOf(role, world.tenant(), insert, world.tenant().value(), subscription, event,
                    UUID.randomUUID(), ACTOR, ACTOR)).isNull();
            assertThat(stateOf(role, world.tenant(), insert, world.tenant().value(), subscription, event,
                    UUID.randomUUID(), ACTOR, ACTOR)).as(role + ": the same event twice").isEqualTo(UNIQUE_VIOLATION);
        }
    }

    @Test
    void aFinishedDeliveryIsFinalAndADeadLetterComesBackOnlyAsAReplayOfTheNextGeneration() throws SQLException {
        for (Role role : Role.values()) {
            for (String ended : new String[] {"DONE", "SKIPPED", "SUPERSEDED"}) {
                World world = world();
                UUID subscription = subscription(world);
                UUID id = delivery(world, subscription, "PENDING");
                run(role, world.tenant(), move("state = '" + ended + "', finished_at = now()"), ACTOR, id);

                assertThat(stateOf(role, world.tenant(), move("reason = 'rejected'"), ACTOR, id))
                        .as(role + " cannot touch a " + ended + " delivery").isEqualTo(CHECK_VIOLATION);
                assertThat(stateOf(role, world.tenant(), move("state = 'PENDING', finished_at = null"), ACTOR, id))
                        .isEqualTo(CHECK_VIOLATION);
            }
            World world = world();
            UUID subscription = subscription(world);
            UUID dead = delivery(world, subscription, "DEAD_LETTERED");
            assertThat(stateOf(role, world.tenant(), move("state = 'PENDING', finished_at = null"), ACTOR, dead))
                    .as(role + ": a dead letter does not go back without a replay").isEqualTo(CHECK_VIOLATION);
            assertThat(stateOf(role, world.tenant(), move("state = 'PENDING', finished_at = null, generation = "
                    + "generation + 1, attempts = 0"), ACTOR, dead)).as(role + ": a replay").isNull();
        }
    }

    @Test
    void whatADeliveryIsAboutNeverChangesAndAttemptsAreNeverTakenBack() throws SQLException {
        for (Role role : Role.values()) {
            World world = world();
            UUID subscription = subscription(world);
            UUID id = delivery(world, subscription, "PENDING");

            assertThat(stateOf(role, world.tenant(), move("record_id = '" + UUID.randomUUID() + "'"), ACTOR, id))
                    .as(role + " cannot change the record").isEqualTo(CHECK_VIOLATION);
            assertThat(stateOf(role, world.tenant(), move("event_id = '" + UUID.randomUUID() + "'"), ACTOR, id))
                    .isEqualTo(CHECK_VIOLATION);
            run(role, world.tenant(), move("attempts = 3"), ACTOR, id);
            assertThat(stateOf(role, world.tenant(), move("attempts = 2"), ACTOR, id))
                    .as(role + " cannot take an attempt back").isEqualTo(CHECK_VIOLATION);
            assertThat(stateOf(role, world.tenant(), move("generation = generation + 1"), ACTOR, id))
                    .as(role + ": only a replay starts a generation").isEqualTo(CHECK_VIOLATION);
        }
    }

    @Test
    void anAttemptRecordIsNeverChangedAndItsReasonHasTheShapeOfAKey() throws SQLException {
        for (Role role : Role.values()) {
            World world = world();
            UUID subscription = subscription(world);
            UUID id = delivery(world, subscription, "PENDING");
            String insert = "insert into webhook_attempt (tenant_id, delivery_id, subscription_id, generation, "
                    + "attempt_no, started_at, outcome, reason, created_by, updated_by) values (?, ?, ?, 1, 1, "
                    + "now(), 'RETRY', ?, ?, ?)";

            assertThat(stateOf(role, world.tenant(), insert, world.tenant().value(), id, subscription,
                    "Free text with spaces", ACTOR, ACTOR)).as(role + ": a reason is a key").isEqualTo(
                    CHECK_VIOLATION);
            assertThat(stateOf(role, world.tenant(), insert, world.tenant().value(), id, subscription, "unavailable",
                    ACTOR, ACTOR)).isNull();
            assertThat(stateOf(role, world.tenant(), "update webhook_attempt set duration_ms = 5, version = version "
                    + "+ 1, updated_by = ? where delivery_id = ?", ACTOR, id)).as(role + " cannot change an attempt")
                    .isEqualTo(CHECK_VIOLATION);
        }
    }

    @Test
    void aDeliveryIsDeletedOnlyWhenItHasEndedAndIsADayOld() throws SQLException {
        for (Role role : Role.values()) {
            World world = world();
            UUID subscription = subscription(world);
            UUID open = delivery(world, subscription, "PENDING");
            UUID recent = delivery(world, subscription, "SKIPPED");
            UUID old = delivery(world, subscription, "SKIPPED");
            run(role, world.tenant(), "update webhook_delivery set created_at = now() - interval '2 days' where id "
                    + "= ?", old);

            assertThat(stateOf(role, world.tenant(), "delete from webhook_delivery where id = ?", open))
                    .as(role + " cannot delete an open delivery").isEqualTo(CHECK_VIOLATION);
            assertThat(stateOf(role, world.tenant(), "delete from webhook_delivery where id = ?", recent))
                    .as(role + " cannot delete a young one").isEqualTo(CHECK_VIOLATION);
            assertThat(stateOf(role, world.tenant(), "delete from webhook_delivery where id = ?", old))
                    .as(role + " may delete an old finished one").isNull();
        }
    }

    // ---- the limit override ----

    @Test
    void theLimitOverrideIsOneRowInsideItsRangesAndSaysSomething() throws SQLException {
        for (Role role : Role.values()) {
            World world = world();
            String insert = "insert into rate_limit_override (tenant_id, organization_per_minute, "
                    + "organization_per_day, application_per_minute, created_by, updated_by) values (?, ?, ?, ?, ?, ?)";

            assertThat(stateOf(role, world.tenant(), insert, world.tenant().value(), 100, null, null, ACTOR, ACTOR))
                    .isNull();
            assertThat(stateOf(role, world.tenant(), insert, world.tenant().value(), 200, null, null, ACTOR, ACTOR))
                    .as(role + ": one live row for an organization").isEqualTo(UNIQUE_VIOLATION);
            World second = world();
            assertThat(stateOf(role, second.tenant(), insert, second.tenant().value(), 0, null, null, ACTOR, ACTOR))
                    .as(role + ": below the range").isEqualTo(CHECK_VIOLATION);
            assertThat(stateOf(role, second.tenant(), insert, second.tenant().value(), null, null, null, ACTOR,
                    ACTOR)).as(role + ": a row that says nothing").isEqualTo(CHECK_VIOLATION);
        }
    }
}
