"use client";

import { useEffect } from "react";
import { startBrowserTracing } from "@/lib/telemetry/browser-tracing";

/**
 * Starts browser tracing once the page is interactive. Renders nothing. Spans are shipped to /telemetry on this app's
 * own origin only when NEXT_PUBLIC_TRACE_EXPORT=true was set at build time; otherwise the trace context is still
 * passed to the API so requests can be correlated.
 */
export function TelemetryBootstrap() {
  useEffect(() => {
    const stop = startBrowserTracing({
      exportUrl: process.env.NEXT_PUBLIC_TRACE_EXPORT === "true" ? "/telemetry/v1/traces" : undefined,
    });
    return () => {
      void stop();
    };
  }, []);
  return null;
}
