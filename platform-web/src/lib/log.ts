/**
 * Browser logging. One JSON object per line, in the same vocabulary as the backend's structured logs
 * (requestId, traceId, tenantId), so a support engineer can search all hops of a request for the same value.
 * Never log tokens, passwords, request bodies or personal data here.
 */

export type LogLevel = "debug" | "info" | "warn" | "error";

export interface LogFields {
  requestId?: string;
  traceId?: string;
  /** Reserved: set once the tenant context exists (Sprint 2); never taken from user input. */
  tenantId?: string;
  [key: string]: string | number | boolean | undefined;
}

type Sink = (line: string) => void;

const consoleSink: Sink = (line) => {
  console.log(line);
};

let sink: Sink = consoleSink;

/** Replaces the output, for tests. Returns a function that restores the console. */
export function setLogSink(next: Sink): () => void {
  sink = next;
  return () => {
    sink = consoleSink;
  };
}

export function log(level: LogLevel, message: string, fields: LogFields = {}): void {
  sink(
    JSON.stringify({
      "@timestamp": new Date().toISOString(),
      "log.level": level,
      "log.logger": "platform-web",
      message,
      ...fields,
    }),
  );
}
