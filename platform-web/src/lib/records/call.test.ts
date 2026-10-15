import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { api } from "@/lib/api/client";
import { json, refusal } from "@/test/fakeApi";
import { call, problemsOf } from "./call";

const QUERY = "/api/v1/objects/Deal__c/records/query";

function ask() {
  return call(() => api.POST("/api/v1/objects/{objectApiName}/records/query", { params: { path: { objectApiName: "Deal__c" } }, body: {} }));
}

/** Replaces fetch with a recorder that answers in order; returns what was asked, as "METHOD /path". */
function answers(...replies: (() => Response)[]): string[] {
  const seen: string[] = [];
  let next = 0;
  vi.stubGlobal(
    "fetch",
    vi.fn(async (request: Request) => {
      const path = new URL(request.url).pathname;
      seen.push(`${request.method} ${path}`);
      if (path === "/api/v1/auth/csrf") {
        return new Response(null, { status: 204 });
      }
      const reply = replies[Math.min(next, replies.length - 1)];
      next += 1;
      return reply ? reply() : new Response(null, { status: 204 });
    }),
  );
  return seen;
}

beforeEach(() => {
  document.cookie = "XSRF-TOKEN=; path=/; max-age=0";
});

afterEach(() => {
  vi.unstubAllGlobals();
  document.cookie = "XSRF-TOKEN=; path=/; max-age=0";
});

describe("making a call", () => {
  it("asks for the forgery cookie before a POST that only reads, when the page has none yet", async () => {
    // A list opened directly has made no write, so it has no cookie; the API refuses a POST without the header, and a
    // query is a POST. This is the 403 the first real-browser run of the list showed.
    const seen = answers(() => json({ data: [], pagination: { hasMore: false, limit: 25 } }));

    const outcome = await ask();

    expect(outcome.ok).toBe(true);
    expect(seen).toEqual(["GET /api/v1/auth/csrf", `POST ${QUERY}`]);
  });

  it("asks for the cookie once when several calls start together, so no call carries a value the others replaced", async () => {
    // Every answer of the cookie endpoint replaces the cookie: a call that read the first value for its header and was
    // sent after the second answer would carry two different values and be refused (the 403 the real browser showed).
    const seen = answers(() => json({ data: [] }));

    await Promise.all([ask(), ask(), ask()]);

    expect(seen.filter((entry) => entry === "GET /api/v1/auth/csrf")).toHaveLength(1);
    expect(seen.filter((entry) => entry === `POST ${QUERY}`)).toHaveLength(3);
    expect(seen[0]).toBe("GET /api/v1/auth/csrf");
  });

  it("does not ask for it again when the page has it", async () => {
    document.cookie = "XSRF-TOKEN=held; path=/";
    const seen = answers(() => json({ data: [] }));

    await ask();

    expect(seen).toEqual([`POST ${QUERY}`]);
  });

  it("tries a refused sign-in once more after a silent refresh, and no more than once", async () => {
    document.cookie = "XSRF-TOKEN=held; path=/";
    const seen = answers(
      () => refusal("UNAUTHENTICATED", "Sign in.", 401),
      () => new Response(null, { status: 204 }),
      () => json({ data: [] }),
    );

    const outcome = await ask();

    expect(outcome.ok).toBe(true);
    expect(seen).toEqual([`POST ${QUERY}`, "POST /api/v1/auth/refresh", `POST ${QUERY}`]);
  });

  it("gives the failure as it is when the refresh does not work either", async () => {
    document.cookie = "XSRF-TOKEN=held; path=/";
    const seen = answers(() => refusal("UNAUTHENTICATED", "Sign in.", 401));

    const outcome = await ask();

    expect(outcome.ok).toBe(false);
    expect(!outcome.ok && outcome.failure.code).toBe("UNAUTHENTICATED");
    expect(seen).toEqual([`POST ${QUERY}`, "POST /api/v1/auth/refresh"]);
  });

  it("gives the API's own code, words and invalid fields for a refusal", async () => {
    document.cookie = "XSRF-TOKEN=held; path=/";
    answers(() =>
      json({ error: { code: "VALIDATION_ERROR", message: "Check the fields.", fields: { name__c: ["Too long."] } } }, 422),
    );

    const outcome = await ask();

    expect(outcome.ok).toBe(false);
    if (!outcome.ok) {
      expect(outcome.failure.code).toBe("VALIDATION_ERROR");
      expect(outcome.failure.status).toBe(422);
      expect(problemsOf(outcome.failure)).toEqual({ name__c: ["Too long."] });
    }
  });

  it("says nothing was reached when the network fails", async () => {
    document.cookie = "XSRF-TOKEN=held; path=/";
    vi.stubGlobal("fetch", vi.fn(async () => Promise.reject(new TypeError("offline"))));

    const outcome = await ask();

    expect(outcome.ok).toBe(false);
    expect(!outcome.ok && outcome.failure.code).toBe("NETWORK_ERROR");
  });

  it("counts a body-less success as a success", async () => {
    document.cookie = "XSRF-TOKEN=held; path=/";
    answers(() => new Response(null, { status: 204 }));

    expect((await ask()).ok).toBe(true);
  });
});

describe("the problems of a refusal", () => {
  it("are the fields it names, or its message as one general problem", () => {
    expect(problemsOf({ code: "VALIDATION_ERROR", message: "m", fields: { a: ["x"] } })).toEqual({ a: ["x"] });
    expect(problemsOf({ code: "FORBIDDEN", message: "Not allowed." })).toEqual({ fields: ["Not allowed."] });
    expect(problemsOf({ code: "VALIDATION_ERROR", message: "m", fields: {} })).toEqual({ fields: ["m"] });
  });
});
