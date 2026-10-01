package app.platform.sharedkernel.logging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class LogContextTest {

    @AfterEach
    void clean() {
        MDC.clear();
    }

    @Test
    void valueIsVisibleInsideTheScopeAndGoneAfterwards() {
        LogContext.Scope scope = LogContext.with(LogContext.TENANT_ID, "tenant-a");
        assertThat(LogContext.tenantId()).contains("tenant-a");

        scope.close();

        assertThat(LogContext.tenantId()).isEmpty();
    }

    @Test
    void nestedScopeRestoresThePreviousValue() {
        LogContext.Scope outer = LogContext.with(LogContext.REQUEST_ID, "req_outer");
        LogContext.Scope inner = LogContext.with(LogContext.REQUEST_ID, "req_inner");
        assertThat(LogContext.requestId()).contains("req_inner");

        inner.close();
        assertThat(LogContext.requestId()).contains("req_outer");

        outer.close();
        assertThat(LogContext.requestId()).isEmpty();
    }

    @Test
    void emptyValuesCountAsAbsent() {
        MDC.put(LogContext.TRACE_ID, "");

        assertThat(LogContext.traceId()).isEmpty();
    }
}
