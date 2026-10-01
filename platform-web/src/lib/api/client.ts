import createClient, { type Middleware } from "openapi-fetch";
import { log } from "../log";
import type { paths } from "./generated/schema";

/** Header that identifies one request across the browser, the API and the database log. */
export const REQUEST_ID_HEADER = "X-Request-ID";

/** Header the API answers with, naming the distributed trace the request belongs to. */
export const TRACE_ID_HEADER = "X-Trace-Id";

const ID_ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";

/**
 * A request ID the API accepts as is (letters, digits, dot, underscore, hyphen; 8 to 64 characters). Same length as
 * the IDs the API generates itself, which keeps the database's application name from being cut.
 */
export function newRequestId(): string {
  const random = crypto.getRandomValues(new Uint8Array(22));
  return "web_" + Array.from(random, (byte) => ID_ALPHABET[byte % ID_ALPHABET.length]).join("");
}

const startTimes = new WeakMap<Request, number>();

/**
 * Gives every request an ID the backend will adopt, and writes one structured browser log line per call with the
 * request ID and the trace ID the API answered with. The trace context itself (the traceparent header) is added by
 * the tracing instrumentation, see src/lib/telemetry.
 */
export const correlationMiddleware: Middleware = {
  onRequest({ request }) {
    if (!request.headers.has(REQUEST_ID_HEADER)) {
      request.headers.set(REQUEST_ID_HEADER, newRequestId());
    }
    startTimes.set(request, performance.now());
    return request;
  },
  onResponse({ request, response }) {
    log("info", "api request completed", {
      requestId: request.headers.get(REQUEST_ID_HEADER) ?? undefined,
      traceId: response.headers.get(TRACE_ID_HEADER) ?? undefined,
      http_method: request.method,
      http_path: new URL(request.url).pathname,
      http_status: response.status,
      duration_ms: Math.round(performance.now() - (startTimes.get(request) ?? performance.now())),
    });
  },
  onError({ request }) {
    log("error", "api request failed before a response arrived", {
      requestId: request.headers.get(REQUEST_ID_HEADER) ?? undefined,
      http_method: request.method,
      http_path: new URL(request.url).pathname,
    });
  },
};

export interface ApiClientOptions {
  /** Origin of the API. Defaults to the page's own origin, which is how the browser always calls it. */
  baseUrl?: string;
  /** Replaces the transport, for tests. */
  fetch?: (request: Request) => Promise<Response>;
}

/**
 * The typed API client. Paths, parameters and bodies come from the generated types, so a change to the API contract
 * that is not followed in the frontend fails type checking.
 */
export function createApiClient(options: ApiClientOptions = {}) {
  const client = createClient<paths>({
    baseUrl: options.baseUrl ?? (typeof window === "undefined" ? "" : window.location.origin),
    // Looked up on every call, not once: the tracing instrumentation replaces the global fetch after start-up.
    fetch: options.fetch ?? ((request) => globalThis.fetch(request)),
  });
  client.use(correlationMiddleware);
  return client;
}

/** The client the application uses. */
export const api = createApiClient();
