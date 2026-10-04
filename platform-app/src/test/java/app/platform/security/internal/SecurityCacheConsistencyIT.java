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
import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestApplication;
import app.platform.testsupport.TestOrganizations;
import app.platform.testsupport.TestOrganizations.Member;
import app.platform.testsupport.TestOrganizations.Organization;
import app.platform.testsupport.TestPlatform;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * What the cache must never get wrong (ADR-0053), tried against a real database and two real instances: a transaction
 * sees its own uncommitted change and nobody else does, a rolled back change leaves no trace in the cache, and while
 * readers hammer both instances a change that has committed is visible on the very next question on both (the stated
 * bound: the next question after the commit).
 */
@PlatformIntegrationTest
class SecurityCacheConsistencyIT {

    private static final ActorId ACTOR = new ActorId(UUID.randomUUID());

    private static TestApplication instanceB;

    @Autowired
    private Users users;

    @Autowired
    private Plans plans;

    @Autowired
    private Subscriptions subscriptions;

    @Autowired
    private Permissions permissionsA;

    @Autowired
    private MemberAccess memberAccessA;

    @Autowired
    private TenantContexts contextsA;

    @Autowired
    private TransactionTemplate transaction;

    @BeforeAll
    static void startTheSecondInstance() {
        instanceB = TestApplication.start();
    }

    @AfterAll
    static void stopTheSecondInstance() {
        instanceB.close();
    }

    private Organization organization() {
        Organization organization = TestOrganizations.create(users);
        TestPlatform.subscribe(plans, subscriptions, organization.id(), 5, 3);
        return organization;
    }

    private boolean holdsOnA(Organization organization, UUID member) {
        return contextsA.call(TenantContext.of(organization.id()),
                () -> permissionsA.has(member, Ability.SESSIONS_MANAGE));
    }

    private static boolean holdsOnB(Organization organization, UUID member) {
        return instanceB.bean(TenantContexts.class).call(TenantContext.of(organization.id()),
                () -> instanceB.bean(Permissions.class).has(member, Ability.SESSIONS_MANAGE));
    }

    @Test
    void aTransactionSeesItsOwnChangeBeforeItCommitsAndNobodyElseDoes() {
        Organization organization = organization();
        Member person = TestOrganizations.join(users, organization.tenant(), false);
        UUID member = person.membership();
        assertThat(holdsOnA(organization, member)).as("nothing yet, and the answer is now kept").isFalse();
        assertThat(holdsOnA(organization, member)).isFalse();

        boolean[] seenInside = new boolean[3];
        contextsA.run(TenantContext.of(organization.id()), () -> transaction.execute(status -> {
            memberAccessA.grant(member, "sessions.manage", "", ACTOR);
            seenInside[0] = permissionsA.has(member, Ability.SESSIONS_MANAGE);
            // The change is not committed: another thread, which has its own transaction, must not see it.
            seenInside[1] = askFromAnotherThread(organization, member);
            status.setRollbackOnly();
            return null;
        }));

        assertThat(seenInside[0]).as("the transaction sees its own change at once").isTrue();
        assertThat(seenInside[1]).as("another reader does not see an uncommitted change").isFalse();
        assertThat(holdsOnA(organization, member)).as("after the rollback nothing was kept").isFalse();
        assertThat(holdsOnB(organization, member)).isFalse();
    }

    private boolean askFromAnotherThread(Organization organization, UUID member) {
        try (ExecutorService other = Executors.newSingleThreadExecutor()) {
            return other.submit(() -> holdsOnA(organization, member)).get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void whileReadersHammerBothInstancesACommittedChangeIsVisibleOnTheNextQuestionOnBoth() throws Exception {
        Organization organization = organization();
        Member person = TestOrganizations.join(users, organization.tenant(), false);
        UUID member = person.membership();
        AtomicBoolean writing = new AtomicBoolean(true);
        List<Throwable> readerFailures = new ArrayList<>();

        try (ExecutorService readers = Executors.newFixedThreadPool(4)) {
            List<Future<?>> running = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                boolean onB = i % 2 == 1;
                running.add(readers.submit(() -> {
                    try {
                        while (writing.get()) {
                            if (onB) {
                                holdsOnB(organization, member);
                            } else {
                                holdsOnA(organization, member);
                            }
                        }
                    } catch (RuntimeException e) {
                        synchronized (readerFailures) {
                            readerFailures.add(e);
                        }
                    }
                }));
            }
            try {
                for (int round = 0; round < 60; round++) {
                    contextsA.run(TenantContext.of(organization.id()),
                            () -> memberAccessA.grant(member, "sessions.manage", "", ACTOR));
                    assertThat(holdsOnA(organization, member)).as("round " + round + " granted, instance A").isTrue();
                    assertThat(holdsOnB(organization, member)).as("round " + round + " granted, instance B").isTrue();
                    contextsA.run(TenantContext.of(organization.id()),
                            () -> memberAccessA.revokeGrant(member, "sessions.manage", ACTOR));
                    assertThat(holdsOnA(organization, member)).as("round " + round + " revoked, instance A")
                            .isFalse();
                    assertThat(holdsOnB(organization, member)).as("round " + round + " revoked, instance B")
                            .isFalse();
                }
            } finally {
                writing.set(false);
                for (Future<?> reader : running) {
                    reader.get(60, TimeUnit.SECONDS);
                }
            }
        }

        assertThat(readerFailures).as("no reader failed while the changes went on").isEmpty();
    }
}
