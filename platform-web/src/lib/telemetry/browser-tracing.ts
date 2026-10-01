import { context, propagation, trace } from "@opentelemetry/api";
import { OTLPTraceExporter } from "@opentelemetry/exporter-trace-otlp-http";
import { registerInstrumentations } from "@opentelemetry/instrumentation";
import { FetchInstrumentation } from "@opentelemetry/instrumentation-fetch";
import { resourceFromAttributes } from "@opentelemetry/resources";
import { BatchSpanProcessor, type SpanProcessor } from "@opentelemetry/sdk-trace-base";
import { WebTracerProvider } from "@opentelemetry/sdk-trace-web";
import { ATTR_SERVICE_NAME } from "@opentelemetry/semantic-conventions";

export interface BrowserTracingOptions {
  /** Name shown in the trace viewer for the browser's side of a trace. */
  serviceName?: string;
  /** Where to ship the browser's spans (a same-origin path, see next.config.ts). Omit to keep spans local. */
  exportUrl?: string;
  /** Additional span processors, for tests. */
  extraProcessors?: SpanProcessor[];
}

let stopCurrent: (() => Promise<void>) | undefined;

/**
 * Starts distributed tracing in the browser: every call the page makes to its own origin becomes a client span, and
 * the standard trace context header (traceparent) is added to the request, so the API continues the same trace and
 * the database statements it runs end up in it too. Spans are shipped only when `exportUrl` is given; the header is
 * added either way, because correlating a request does not depend on anyone collecting the spans.
 *
 * Calling it again while running does nothing. Returns a function that stops tracing and removes the instrumentation.
 * Stopping takes effect immediately; the returned promise only covers flushing the spans that are still queued. That
 * matters because React runs effects twice in development (start, stop, start): a stop that finished "later" would
 * switch off the tracing the second start had just set up.
 */
export function startBrowserTracing(options: BrowserTracingOptions = {}): () => Promise<void> {
  if (stopCurrent) {
    return stopCurrent;
  }
  const processors: SpanProcessor[] = [...(options.extraProcessors ?? [])];
  if (options.exportUrl) {
    processors.push(new BatchSpanProcessor(new OTLPTraceExporter({ url: options.exportUrl })));
  }
  const provider = new WebTracerProvider({
    resource: resourceFromAttributes({ [ATTR_SERVICE_NAME]: options.serviceName ?? "platform-web" }),
    spanProcessors: processors,
  });
  provider.register();
  const unregister = registerInstrumentations({
    instrumentations: [
      new FetchInstrumentation({
        // The page only calls its own origin; the exporter's requests must not be traced themselves.
        ignoreUrls: [/\/telemetry\//],
        propagateTraceHeaderCorsUrls: [],
      }),
    ],
  });

  const stop = (): Promise<void> => {
    if (stopCurrent !== stop) {
      return Promise.resolve();
    }
    stopCurrent = undefined;
    unregister();
    trace.disable();
    context.disable();
    propagation.disable();
    return provider.shutdown();
  };
  stopCurrent = stop;
  return stop;
}
