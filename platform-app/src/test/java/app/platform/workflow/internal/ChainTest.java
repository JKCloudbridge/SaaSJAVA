package app.platform.workflow.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * The rules that stop a workflow from starting itself (ADR-0140), without a database: a workflow already in the chain
 * is a loop, a chain has a depth bound that is reached exactly, and the chain an event carries is read leniently (an
 * event made before Sprint 23, or one with a damaged chain, is depth 0 and never an error).
 */
class ChainTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static Chain read(String payload) {
        return Chain.fromPayload(JSON.readTree(payload));
    }

    @Test
    void aPersonsWriteHasNoChain() {
        Chain chain = read("{\"object\":\"Order__c\",\"recordId\":\"" + UUID.randomUUID() + "\",\"version\":3}");

        assertThat(chain.root()).isNull();
        assertThat(chain.depth()).isZero();
        assertThat(chain.workflows()).isEmpty();
    }

    @Test
    void theChainOfAnActionsWriteIsReadFromTheEvent() {
        UUID root = UUID.randomUUID();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        Chain chain = read("{\"chain\":{\"root\":\"" + root + "\",\"depth\":2,\"workflows\":[\"" + first + "\",\""
                + second + "\"]}}");

        assertThat(chain.root()).isEqualTo(root);
        assertThat(chain.depth()).isEqualTo(2);
        assertThat(chain.workflows()).containsExactly(first, second);
    }

    @Test
    void aDamagedChainIsTheEmptyChainAndNeverAnError() {
        for (String damaged : new String[] {"{\"chain\":\"nonsense\"}", "{\"chain\":{\"root\":\"not-an-id\"}}",
            "{\"chain\":{\"root\":\"" + UUID.randomUUID() + "\",\"workflows\":[\"x\"]}}", "{\"chain\":[]}",
            "{\"chain\":null}"}) {
            Chain chain = read(damaged);

            assertThat(chain.depth()).as(damaged).isZero();
            assertThat(chain.workflows()).as(damaged).isEmpty();
        }
    }

    @Test
    void aNegativeDepthIsNotBelievedAndTheWorkflowsOfAChainAreFound() {
        UUID workflow = UUID.randomUUID();
        Chain chain = new Chain(UUID.randomUUID(), 1, List.of(workflow));

        assertThat(chain.contains(workflow)).isTrue();
        assertThat(chain.contains(UUID.randomUUID())).isFalse();
        assertThat(read("{\"chain\":{\"root\":\"" + UUID.randomUUID() + "\",\"depth\":-4}}").depth()).isZero();
    }

    @Test
    void aChainOfFiveDifferentWorkflowsReachesTheDepthLimitExactly() {
        // The limit is 5 executions in a chain: depths 0 to 4 run, the one at depth 5 is refused.
        Chain chain = Chain.none();
        for (int depth = 0; depth < 5; depth++) {
            assertThat(chain.isTooDeep(5)).as("depth " + depth).isFalse();
            chain = chain.extendedBy(UUID.randomUUID(), UUID.randomUUID());
        }

        assertThat(chain.depth()).isEqualTo(5);
        assertThat(chain.isTooDeep(5)).isTrue();
        assertThat(chain.workflows()).hasSize(5).doesNotHaveDuplicates();
    }

    @Test
    void extendingAChainKeepsItsRootAndAddsTheWorkflowAndOneLevel() {
        UUID root = UUID.randomUUID();
        UUID earlier = UUID.randomUUID();
        UUID workflow = UUID.randomUUID();
        UUID execution = UUID.randomUUID();

        Chain extended = new Chain(root, 1, List.of(earlier)).extendedBy(workflow, execution);

        assertThat(extended.root()).isEqualTo(root);
        assertThat(extended.depth()).isEqualTo(2);
        assertThat(extended.workflows()).containsExactly(earlier, workflow);
        assertThat(Chain.none().extendedBy(workflow, execution).root()).as("a schedule's firing is its own root")
                .isEqualTo(execution);
    }

    @Test
    void aWorkflowThatUpdatesTheFieldItTriggersOnIsStoppedByTheChain() {
        // A starts on a change; its action changes the record; the event it makes carries A in the chain, so the
        // handler refuses to start A again.
        UUID a = UUID.randomUUID();
        Chain afterA = Chain.none().extendedBy(a, UUID.randomUUID());

        assertThat(afterA.contains(a)).isTrue();
    }

    @Test
    void aStartingAndBStartingAStopsAtTheSecondA() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        Chain afterA = Chain.none().extendedBy(a, UUID.randomUUID());
        assertThat(afterA.contains(b)).as("B may follow A on purpose").isFalse();

        Chain afterB = afterA.extendedBy(b, UUID.randomUUID());

        assertThat(afterB.contains(a)).as("A again is a loop").isTrue();
        assertThat(afterB.contains(b)).isTrue();
    }
}
