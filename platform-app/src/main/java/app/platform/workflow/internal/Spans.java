package app.platform.workflow.internal;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Wraps a piece of workflow work in a span (ADR-0137), so that a trace shows the handling of an event and the run of an
 * execution as steps of their own, tagged with the identifiers that tie them together (the event, the execution, the
 * workflow). A tag is an identifier or a name, never a value of a record, a condition or a setting. Without tracing the
 * work simply runs.
 *
 * <p>The outbox does not carry the trace of the request that wrote the record, so the handling of an event and the
 * run of its execution are separate traces joined by those tags, not parent and child.
 */
@Component
class Spans {

    private final ObjectProvider<Tracer> tracer;

    Spans(ObjectProvider<Tracer> tracer) {
        this.tracer = tracer;
    }

    /** Runs the work in a span of this name with these tags. */
    void run(String name, Map<String, String> tags, Runnable work) {
        call(name, tags, () -> {
            work.run();
            return null;
        });
    }

    /** Runs the work in a span of this name with these tags and gives back what it gives. */
    <T> T call(String name, Map<String, String> tags, java.util.function.Supplier<T> work) {
        Tracer current = tracer.getIfAvailable();
        if (current == null) {
            return work.get();
        }
        Span span = current.nextSpan().name(name);
        tags.forEach(span::tag);
        span.start();
        try (Tracer.SpanInScope _ = current.withSpan(span)) {
            return work.get();
        } catch (RuntimeException failure) {
            // The type name only: the message of a library exception can quote the data that failed.
            span.tag("error.type", failure.getClass().getSimpleName());
            throw failure;
        } finally {
            span.end();
        }
    }
}
