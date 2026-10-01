package app.platform.observability.correlation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class TransactionCorrelationListenerTest {

    private static final String TRACE_ID = "0123456789abcdef0123456789abcdef";

    @Test
    void namesBothIdentifiersAndFitsTheDatabaseLimit() {
        String requestId = RequestIds.generate(1_800_000_000_000L);

        Optional<String> name = TransactionCorrelationListener.applicationName(
                Optional.of(TRACE_ID), Optional.of(requestId));

        assertThat(name).contains("t=" + TRACE_ID + " r=" + requestId);
        assertThat(name.orElseThrow().length())
                .isLessThanOrEqualTo(TransactionCorrelationListener.MAX_APPLICATION_NAME);
    }

    @Test
    void usesWhateverIsAvailable() {
        assertThat(TransactionCorrelationListener.applicationName(Optional.of(TRACE_ID), Optional.empty()))
                .contains("t=" + TRACE_ID);
        assertThat(TransactionCorrelationListener.applicationName(Optional.empty(), Optional.of("req_12345678")))
                .contains("r=req_12345678");
    }

    @Test
    void staysSilentWithoutIdentifiers() {
        assertThat(TransactionCorrelationListener.applicationName(Optional.empty(), Optional.empty())).isEmpty();
    }

    @Test
    void cutsAnOverlongClientSuppliedRequestIdInsteadOfFailing() {
        String longRequestId = "a".repeat(64);

        Optional<String> name = TransactionCorrelationListener.applicationName(
                Optional.of(TRACE_ID), Optional.of(longRequestId));

        assertThat(name.orElseThrow()).hasSize(TransactionCorrelationListener.MAX_APPLICATION_NAME)
                .startsWith("t=" + TRACE_ID);
    }
}
