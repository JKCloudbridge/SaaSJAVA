package app.platform.data.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.metadata.FieldConfiguration.AutoNumberConfiguration;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The order of the stages is part of the contract (note 4 section 20, ADR-0091): a test holds the list, and the
 * integration tests hold the behaviour ({@code WritePipelineStagesIT}). The format of an auto-number is held here too.
 */
class WritePipelineOrderTest {

    @Test
    void theStagesAreInTheDocumentedOrder() {
        assertThat(WritePipeline.STAGES).containsExactly("shape", "record type", "field permission", "type",
                "required", "relationship", "unique", "rule", "formula");
    }

    @Test
    void anAutoNumberIsThePrefixAndTheDigitsPaddedWithZeros() {
        AutoNumberConfiguration invoice = new AutoNumberConfiguration("INV-", 1, 6);

        assertThat(List.of(AutoNumbers.format(invoice, 1), AutoNumbers.format(invoice, 123),
                AutoNumbers.format(invoice, 1_234_567)))
                .containsExactly("INV-000001", "INV-000123", "INV-1234567");
        assertThat(AutoNumbers.format(new AutoNumberConfiguration("", 900, 3), 900)).isEqualTo("900");
    }
}
