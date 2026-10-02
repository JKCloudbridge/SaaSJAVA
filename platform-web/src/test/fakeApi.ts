import { vi } from "vitest";

/** A JSON answer, as the API sends one. */
export function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}

/** The API's error model, with its own words. */
export function refusal(code: string, message: string, status: number): Response {
  return json({ error: { code, message } }, status);
}

export type Handler = (request: Request) => Response | Promise<Response>;

/** What a test sent: the method and path as "GET /api/v1/x", and the body text. */
export interface Call {
  key: string;
  body: string;
}

/**
 * Replaces `fetch` with a fake API for a test. `answers` maps "METHOD /path" to a handler; anything else answers 204.
 * The forgery-cookie request is answered and not recorded. Returns the recorded calls.
 */
export function fakeApi(answers: Record<string, Handler> = {}): Call[] {
  const calls: Call[] = [];
  vi.stubGlobal(
    "fetch",
    vi.fn(async (request: Request) => {
      const key = `${request.method} ${new URL(request.url).pathname}`;
      if (key === "GET /api/v1/auth/csrf") {
        return new Response(null, { status: 204 });
      }
      calls.push({ key, body: await request.clone().text() });
      const handler = answers[key];
      return handler ? handler(request) : new Response(null, { status: 204 });
    }),
  );
  return calls;
}
