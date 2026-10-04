package app.platform.security.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.identity.Users;
import app.platform.licensing.Plans;
import app.platform.licensing.Subscriptions;
import app.platform.security.Ability;
import app.platform.security.MemberAccess;
import app.platform.security.Permissions;
import app.platform.sharedkernel.ActorId;
import app.platform.tenant.TenantContext;
import app.platform.tenant.TenantContexts;
import app.platform.testsupport.TestApplication;
import app.platform.testsupport.TestOrganizations;
import app.platform.testsupport.TestOrganizations.Member;
import app.platform.testsupport.TestOrganizations.Organization;
import app.platform.testsupport.TestPlatform;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;

/**
 * Decision of Sprint 9 (ADR-0053): the security cache does not use Redis at all, so Redis being down can never make an
 * answer wrong. The proof: with the instance's Redis paused (what a hung or unreachable server looks like), every
 * change still shows on the next question, and the cache keeps serving while nothing changes.
 */
class SecurityCacheRedisOutageIT {

    private static final ActorId ACTOR = new ActorId(UUID.randomUUID());
    private static final String PASSWORD = UUID.randomUUID().toString();
    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8")
            .withExposedPorts(6379).withCommand("redis-server", "--requirepass", PASSWORD);

    private static TestApplication app;

    @BeforeAll
    static void start() {
        REDIS.start();
        app = TestApplication.startWithRedis(REDIS.getHost(), REDIS.getMappedPort(6379), PASSWORD);
    }

    @AfterAll
    static void stop() {
        try {
            REDIS.getDockerClient().unpauseContainerCmd(REDIS.getContainerId()).exec();
        } catch (RuntimeException alreadyRunning) {
            // Nothing to undo.
        }
        app.close();
        REDIS.stop();
    }

    @Test
    void whileRedisIsDownEveryChangeStillShowsOnTheNextQuestion() {
        Users users = app.bean(Users.class);
        Organization organization = TestOrganizations.create(users);
        TestPlatform.subscribe(app.bean(Plans.class), app.bean(Subscriptions.class), organization.id(), 5, 3);
        Member person = TestOrganizations.join(users, organization.tenant(), false);
        UUID member = person.membership();
        TenantContexts contexts = app.bean(TenantContexts.class);
        Permissions permissions = app.bean(Permissions.class);
        MemberAccess access = app.bean(MemberAccess.class);
        SecurityCache cache = app.bean(SecurityCache.class);
        TenantContext tenant = TenantContext.of(organization.id());

        REDIS.getDockerClient().pauseContainerCmd(REDIS.getContainerId()).exec();
        try {
            assertThat(contexts.call(tenant, () -> permissions.has(member, Ability.SESSIONS_MANAGE))).isFalse();
            long hits = cache.hitCount();
            assertThat(contexts.call(tenant, () -> permissions.has(member, Ability.SESSIONS_MANAGE))).isFalse();
            assertThat(cache.hitCount()).as("served from the cache although Redis is down").isEqualTo(hits + 1);

            contexts.run(tenant, () -> access.grant(member, "sessions.manage", "", ACTOR));
            assertThat(contexts.call(tenant, () -> permissions.has(member, Ability.SESSIONS_MANAGE)))
                    .as("granted, Redis down").isTrue();

            contexts.run(tenant, () -> access.revokeGrant(member, "sessions.manage", ACTOR));
            assertThat(contexts.call(tenant, () -> permissions.has(member, Ability.SESSIONS_MANAGE)))
                    .as("revoked, Redis down").isFalse();
        } finally {
            REDIS.getDockerClient().unpauseContainerCmd(REDIS.getContainerId()).exec();
        }
    }
}
