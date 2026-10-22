package app.platform.metadata.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.metadata.ApprovalProcessDefinition;
import app.platformapi.ApprovalStepSpec;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The steps of a process are stored as JSON and read back by every later release (FA4A, ADR-0187): a process version
 * stored before a step could be skipped has no such key and must read as "never skipped", and the flag must survive a
 * trip through the stored form and into the definition the engine reads.
 */
class ApprovalProcessCodecTest {

    private static final String BEFORE_THE_FLAG = "[{\"name\":\"First\",\"label\":\"First\",\"completion\":\"ANY\","
            + "\"approvers\":[{\"kind\":\"OWNER\",\"value\":null}]}]";

    private final ApprovalProcessCodec codec = new ApprovalProcessCodec();

    @Test
    void aVersionStoredBeforeTheFlagReadsAsNeverSkipped() {
        List<ApprovalStepSpec> read = codec.readSteps(BEFORE_THE_FLAG);

        assertThat(read).hasSize(1);
        assertThat(read.get(0).skipWhenNobody()).isFalse();
        assertThat(ApprovalProcessCodec.step(read.get(0)).skipWhenNobody()).isFalse();
    }

    @Test
    void rewritingAnOldVersionSaysFalseOutrightSoTwoSpellingsOfTheSameDefinitionAreEqual() {
        List<ApprovalStepSpec> read = codec.readSteps(BEFORE_THE_FLAG);

        assertThat(codec.writeSteps(read)).contains("\"skipWhenNobody\":false");
        assertThat(codec.writeSteps(codec.readSteps(codec.writeSteps(read)))).isEqualTo(codec.writeSteps(read));
    }

    @Test
    void theFlagSurvivesTheStoredFormAndReachesTheDefinition() {
        ApprovalStepSpec skippable = new ApprovalStepSpec("Second", "Second", "ANY", List.of(
                new app.platformapi.ApprovalApproverSpec("OWNER", null)), true);

        List<ApprovalStepSpec> read = codec.readSteps(codec.writeSteps(List.of(skippable)));

        assertThat(read.get(0).skipWhenNobody()).isTrue();
        ApprovalProcessDefinition.Step step = ApprovalProcessCodec.step(read.get(0));
        assertThat(step.skipWhenNobody()).isTrue();
        assertThat(step.name()).isEqualTo("Second");
    }

    @Test
    void aMissingOrNullFlagInARequestMeansFalse() {
        assertThat(new ApprovalStepSpec("S", "S", "ANY", List.of(), null).skipWhenNobody()).isFalse();
        assertThat(new ApprovalStepSpec("S", "S", "ANY", List.of(), false).skipWhenNobody()).isFalse();
    }
}
