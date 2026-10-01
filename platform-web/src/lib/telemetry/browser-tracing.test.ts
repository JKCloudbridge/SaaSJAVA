import { InMemorySpanExporter, SimpleSpanProcessor } from "@opentelemetry/sdk-trace-base";
import { afterEach, describe, expect, it, vi } from "vitest";
import { createApiClient } from "../api/client";
import { setLogSink } from "../log";
import { startBrowserTracing } from "./browser-tracing";

const okBody = JSON.stringify({
  data: { service: "platform", apiVersion: "v1", serverTime: "2026-10-01T10:00:00Z", databaseTime: "2026-10-01T10:00:00Z" },
});

describe("browser tracing", () => {
  let stop: (() => Promise<void>) | undefined;

  afterEach(async () => {
    await stop?.();
    stop = undefined;
    vi.unstubAllGlobals();
  });

  it("adds the trace context to the API call so the API continues the same trace", async () => {
    const restoreLog = setLogSink(() => {});
    const exporter = new InMemorySpanExporter();
    const received: Request[] = [];
    vi.stubGlobal(
      "fetch",
      vi.fn(async (request: Request) => {
        received.push(request);
        return new Response(okBody, { status: 200, headers: { "Content-Type": "application/json" } });
      }),
    );
    stop = startBrowserTracing({ extraProcessors: [new SimpleSpanProcessor(exporter)] });
    const client = createApiClient({ baseUrl: window.location.origin });

    await client.GET("/api/v1/platform/status");

    expect(received).toHaveLength(1);
    const traceparent = received[0]!.headers.get("traceparent");
    expect(traceparent).toMatch(/^00-[0-9a-f]{32}-[0-9a-f]{16}-0[01]$/);
    await vi.waitFor(() => expect(exporter.getFinishedSpans().length).toBeGreaterThan(0));
    const span = exporter.getFinishedSpans()[0]!;
    expect(traceparent).toContain(span.spanContext().traceId);
    expect(traceparent).toContain(span.spanContext().spanId);
    expect(span.name).toBe("GET");
    restoreLog();
  });

  it("keeps working without anyone collecting spans: the header is added all the same", async () => {
    const restoreLog = setLogSink(() => {});
    const received: Request[] = [];
    vi.stubGlobal(
      "fetch",
      vi.fn(async (request: Request) => {
        received.push(request);
        return new Response(okBody, { status: 200, headers: { "Content-Type": "application/json" } });
      }),
    );
    stop = startBrowserTracing();
    const client = createApiClient({ baseUrl: window.location.origin });

    await client.GET("/api/v1/platform/status");

    expect(received[0]!.headers.get("traceparent")).toMatch(/^00-[0-9a-f]{32}-[0-9a-f]{16}-0[01]$/);
    restoreLog();
  });

  it("survives the start, stop, start sequence React runs in development", async () => {
    const restoreLog = setLogSink(() => {});
    const received: Request[] = [];
    vi.stubGlobal(
      "fetch",
      vi.fn(async (request: Request) => {
        received.push(request);
        return new Response(okBody, { status: 200, headers: { "Content-Type": "application/json" } });
      }),
    );

    const firstStop = startBrowserTracing();
    void firstStop(); // not awaited, exactly like an effect clean-up
    stop = startBrowserTracing();
    await createApiClient({ baseUrl: window.location.origin }).GET("/api/v1/platform/status");

    expect(received[0]!.headers.get("traceparent")).toMatch(/^00-[0-9a-f]{32}-[0-9a-f]{16}-0[01]$/);
    restoreLog();
  });

  it("stopping twice is harmless", async () => {
    const first = startBrowserTracing();
    await first();
    await first();

    stop = startBrowserTracing();
    expect(stop).not.toBe(first);
  });

  it("does nothing the second time it is started", async () => {
    stop = startBrowserTracing();

    expect(startBrowserTracing()).toBe(stop);
  });
});
