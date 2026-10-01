import { afterEach, describe, expect, it } from "vitest";
import { createApiClient } from "./client";
import { FORGERY_COOKIE, FORGERY_HEADER, readCookie } from "./forgery";

function noContent(): Response {
  return new Response(null, { status: 204 });
}

describe("forgery protection", () => {
  afterEach(() => {
    document.cookie = `${FORGERY_COOKIE}=; path=/; max-age=0`;
  });

  it("reads a cookie of the page by name and nothing else", () => {
    document.cookie = `${FORGERY_COOKIE}=abc%3D123; path=/`;
    document.cookie = "other=1; path=/";

    expect(readCookie(FORGERY_COOKIE)).toBe("abc=123");
    expect(readCookie("missing")).toBeUndefined();
    document.cookie = "other=; path=/; max-age=0";
  });

  it("sends the cookie's value as a header with a request that changes something", async () => {
    document.cookie = `${FORGERY_COOKIE}=the-token; path=/`;
    let sent: Request | undefined;
    const client = createApiClient({
      baseUrl: "http://api.test",
      fetch: async (request) => {
        sent = request;
        return noContent();
      },
    });

    await client.POST("/api/v1/auth/sign-out");

    expect(sent?.headers.get(FORGERY_HEADER)).toBe("the-token");
  });

  it("sends nothing with a request that only reads", async () => {
    document.cookie = `${FORGERY_COOKIE}=the-token; path=/`;
    let sent: Request | undefined;
    const client = createApiClient({
      baseUrl: "http://api.test",
      fetch: async (request) => {
        sent = request;
        return new Response(JSON.stringify({ data: { id: "1", email: "e", displayName: "n" } }), {
          headers: { "Content-Type": "application/json" },
        });
      },
    });

    await client.GET("/api/v1/auth/me");

    expect(sent?.headers.has(FORGERY_HEADER)).toBe(false);
  });

  it("sends no header when the page holds no cookie yet", async () => {
    let sent: Request | undefined;
    const client = createApiClient({
      baseUrl: "http://api.test",
      fetch: async (request) => {
        sent = request;
        return noContent();
      },
    });

    await client.POST("/api/v1/auth/sign-out");

    expect(sent?.headers.has(FORGERY_HEADER)).toBe(false);
  });
});
