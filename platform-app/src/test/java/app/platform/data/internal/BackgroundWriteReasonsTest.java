package app.platform.data.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.data.BackgroundWriteException;
import app.platform.data.BackgroundWriteException.Reason;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * What a refusal of the write pipeline is called when the platform made the write (ADR-0139): one stable reason per
 * stage, and a message that is only the reason, never the words the pipeline says to a person (they can quote a value).
 */
class BackgroundWriteReasonsTest {

    @Test
    void everyStageOfThePipelineHasAReasonOfItsOwn() {
        assertThat(DefaultBackgroundWrites.reasonOf("shape")).isEqualTo(Reason.INVALID_VALUE);
        assertThat(DefaultBackgroundWrites.reasonOf("record type")).isEqualTo(Reason.RECORD_TYPE_REJECTED);
        assertThat(DefaultBackgroundWrites.reasonOf("field permission")).isEqualTo(Reason.FIELD_NOT_WRITABLE);
        assertThat(DefaultBackgroundWrites.reasonOf("type")).isEqualTo(Reason.INVALID_VALUE);
        assertThat(DefaultBackgroundWrites.reasonOf("required")).isEqualTo(Reason.REQUIRED_MISSING);
        assertThat(DefaultBackgroundWrites.reasonOf("relationship")).isEqualTo(Reason.LINK_TARGET_GONE);
        assertThat(DefaultBackgroundWrites.reasonOf("dependent")).isEqualTo(Reason.PAIR_NOT_ALLOWED);
        assertThat(DefaultBackgroundWrites.reasonOf("unique")).isEqualTo(Reason.UNIQUE_CLASH);
        assertThat(DefaultBackgroundWrites.reasonOf("rule")).isEqualTo(Reason.REJECTED_BY_RULE);
        assertThat(DefaultBackgroundWrites.reasonOf("formula")).isEqualTo(Reason.FORMULA_FAILED);
    }

    @Test
    void everyStageNamedByThePipelineIsMapped() {
        // A stage added to the pipeline without a reason would fall to the general one; this fails the build first.
        List<String> unmapped = WritePipeline.STAGES.stream()
                .filter(stage -> !stage.equals("shape") && !stage.equals("type")
                        && DefaultBackgroundWrites.reasonOf(stage) == Reason.INVALID_VALUE)
                .toList();

        assertThat(unmapped).isEmpty();
    }

    @Test
    void aStageThatIsNotKnownIsAnInvalidValueNotAnError() {
        assertThat(DefaultBackgroundWrites.reasonOf(null)).isEqualTo(Reason.INVALID_VALUE);
        assertThat(DefaultBackgroundWrites.reasonOf("a stage of a later release")).isEqualTo(Reason.INVALID_VALUE);
    }

    @Test
    void theKeysAreStableLowerCaseWordsAndOnlyAConcurrentChangeIsWorthRetrying() {
        for (Reason reason : Reason.values()) {
            assertThat(reason.key()).matches("[a-z][a-z_]{0,59}");
            assertThat(reason.transientFailure()).as(reason.key()).isEqualTo(reason == Reason.CONCURRENT_MODIFICATION);
        }
        assertThat(Reason.values()).extracting(Reason::key).doesNotHaveDuplicates();
    }

    @Test
    void theExceptionSaysOnlyItsReasonAndHasNoStack() {
        BackgroundWriteException failure = new BackgroundWriteException(Reason.UNIQUE_CLASH);

        assertThat(failure.getMessage()).isEqualTo("unique_clash");
        assertThat(failure.reason()).isEqualTo(Reason.UNIQUE_CLASH);
        assertThat(failure.getStackTrace()).isEmpty();
        assertThat(failure.getCause()).isNull();
    }
}
