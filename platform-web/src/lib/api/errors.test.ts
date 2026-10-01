import { describe, expect, it } from "vitest";
import { REQUEST_ID_HEADER, TRACE_ID_HEADER } from "./client";
import { failureFromNetworkError, failureFromResponse } from "./errors";

describe("failureFromResponse", () => {
  it("uses the API's error model when the body has it", () => {
    const body = {
      error: {
        code: "VALIDATION_ERROR",
        message: "The request is invalid.",
        fields: { name: ["must not be blank"] },
        requestId: "req_a",
        traceId: "trace_a",
      },
    };

    const failure = failureFromResponse(body, new Response(null, { status: 400 }));

    expect(failure).toEqual({
      code: "VALIDATION_ERROR",
      message: "The request is invalid.",
      fields: { name: ["must not be blank"] },
      requestId: "req_a",
      traceId: "trace_a",
      status: 400,
    });
  });

  it("falls back to the response headers for the identifiers", () => {
    const response = new Response(null, {
      status: 404,
      headers: { [REQUEST_ID_HEADER]: "req_header", [TRACE_ID_HEADER]: "trace_header" },
    });

    const failure = failureFromResponse({ error: { code: "NOT_FOUND", message: "Not found." } }, response);

    expect(failure.requestId).toBe("req_header");
    expect(failure.traceId).toBe("trace_header");
  });

  it("does not trust a body that merely looks like an error with an unknown code", () => {
    const failure = failureFromResponse({ error: { code: "MADE_UP", message: "x" } }, new Response(null, { status: 500 }));

    expect(failure.code).toBe("UNREADABLE_RESPONSE");
  });

  it("copes with answers that are not the error model at all (for example a gateway page)", () => {
    const failure = failureFromResponse(undefined, new Response("<html>bad gateway</html>", { status: 502 }));

    expect(failure.code).toBe("UNREADABLE_RESPONSE");
    expect(failure.status).toBe(502);
    expect(failure.message).not.toContain("html");
  });
});

describe("failureFromNetworkError", () => {
  it("is a calm, generic message", () => {
    expect(failureFromNetworkError()).toEqual({
      code: "NETWORK_ERROR",
      message: "The server could not be reached. Check your connection and try again.",
    });
  });
});
