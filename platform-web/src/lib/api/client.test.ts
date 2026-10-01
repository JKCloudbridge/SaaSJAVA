import { afterEach, describe, expect, it } from "vitest";
import { setLogSink } from "../log";
import { createApiClient, newRequestId, REQUEST_ID_HEADER, TRACE_ID_HEADER } from "./client";

const statusBody = {
  data: {
    service: "platform",
    apiVersion: "v1",
    serverTime: "2026-10-01T10:00:00Z",
    databaseTime: "2026-10-01T10:00:00Z",
  },
};

function json(body: unknown, init: ResponseInit = {}): Response {
  return new Response(JSON.stringify(body), {
    ...init,
    headers: { "Content-Type": "application/json", ...init.headers },
  });
}

describe("request ids", () => {
  it("are accepted by the API's rules and do not repeat", () => {
    const first = newRequestId();
    const second = newRequestId();

    expect(first).toMatch(/^web_[0-9A-HJKMNP-TV-Z]{22}$/);
    expect(first).toHaveLength(26);
    expect(first).not.toEqual(second);
  });
});

describe("api client", () => {
  const restore: Array<() => void> = [];
  afterEach(() => {
    restore.splice(0).forEach((undo) => undo());
  });

  function capture(): string[] {
    const lines: string[] = [];
    restore.push(setLogSink((line) => lines.push(line)));
    return lines;
  }

  it("sends a generated request id and returns the typed envelope", async () => {
    let sent: Request | undefined;
    const client = createApiClient({
      baseUrl: "http://api.test",
      fetch: async (request) => {
        sent = request;
        return json(statusBody);
      },
    });

    const { data, error } = await client.GET("/api/v1/platform/status");

    expect(error).toBeUndefined();
    expect(data?.data.service).toBe("platform");
    expect(sent?.url).toBe("http://api.test/api/v1/platform/status");
    expect(sent?.headers.get(REQUEST_ID_HEADER)).toMatch(/^web_[0-9A-HJKMNP-TV-Z]{22}$/);
  });

  it("keeps a request id the caller already set", async () => {
    let sent: Request | undefined;
    const client = createApiClient({
      baseUrl: "http://api.test",
      fetch: async (request) => {
        sent = request;
        return json(statusBody);
      },
    });

    await client.GET("/api/v1/platform/status", { headers: { [REQUEST_ID_HEADER]: "caller-chosen-id" } });

    expect(sent?.headers.get(REQUEST_ID_HEADER)).toBe("caller-chosen-id");
  });

  it("logs one structured line per call with the request id and the trace id the API answered with", async () => {
    const lines = capture();
    const client = createApiClient({
      baseUrl: "http://api.test",
      fetch: async () => json(statusBody, { headers: { [TRACE_ID_HEADER]: "0af7651916cd43dd8448eb211c80319c" } }),
    });

    await client.GET("/api/v1/platform/status", { headers: { [REQUEST_ID_HEADER]: "caller-chosen-id" } });

    expect(lines).toHaveLength(1);
    const record = JSON.parse(lines[0]!);
    expect(record).toMatchObject({
      message: "api request completed",
      requestId: "caller-chosen-id",
      traceId: "0af7651916cd43dd8448eb211c80319c",
      http_method: "GET",
      http_path: "/api/v1/platform/status",
      http_status: 200,
    });
    expect(record["@timestamp"]).toBeTruthy();
  });

  it("logs a failure that never got an answer, without the url's query string or any body", async () => {
    const lines = capture();
    const client = createApiClient({
      baseUrl: "http://api.test",
      fetch: async () => {
        throw new TypeError("network down");
      },
    });

    await expect(client.GET("/api/v1/platform/status")).rejects.toThrow("network down");

    const record = JSON.parse(lines[0]!);
    expect(record.message).toBe("api request failed before a response arrived");
    expect(record.http_path).toBe("/api/v1/platform/status");
    expect(JSON.stringify(record)).not.toContain("network down");
  });

  it("returns the API's error model as the error of the call", async () => {
    const client = createApiClient({
      baseUrl: "http://api.test",
      fetch: async () =>
        json(
          { error: { code: "SERVICE_UNAVAILABLE", message: "The service is temporarily unavailable.", requestId: "r1" } },
          { status: 503 },
        ),
    });

    const { data, error, response } = await client.GET("/api/v1/platform/status");

    expect(data).toBeUndefined();
    expect(response.status).toBe(503);
    expect(error?.error.code).toBe("SERVICE_UNAVAILABLE");
  });
});
