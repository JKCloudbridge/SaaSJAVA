import { api } from "@/lib/api/client";
import { failureFromNetworkError, failureFromResponse, type ApiFailure } from "@/lib/api/errors";
import { ensureForgeryCookie } from "@/lib/session/SessionProvider";

/** What a call came to: the data of the answer, or the API's own failure (its code, its words, its invalid fields). */
export type Outcome<T> = { ok: true; data: T } | { ok: false; failure: ApiFailure };

/** What `openapi-fetch` hands back for one call. */
interface Raw<T> {
  data?: T;
  error?: unknown;
  response: Response;
}

/**
 * Makes one call the way every record screen does: the forgery cookie first, and a 401 tried exactly once more after a
 * silent refresh (the access token is short-lived by design, ADR-0019, and a screen left open for a while must not show
 * a refusal for it). The page decides nothing from the answer except what to show.
 *
 * <p>The cookie is ensured for **every** call, not only the ones that change something: a query, a search, the names
 * behind a link and the preview of a save are all POSTs that only read, the API refuses a POST without the forgery
 * header, and a page opened directly (no write yet, so no cookie yet) would otherwise get a 403 on its first list. When
 * the cookie is there already this costs nothing.
 *
 * @param run makes the call; it is run again after a refresh, so it must not hold state of its own
 */
export async function call<T>(run: () => Promise<Raw<T>>): Promise<Outcome<T>> {
  try {
    await ensureForgeryCookie();
    let result = await run();
    if (result.response.status === 401) {
      const refreshed = await api.POST("/api/v1/auth/refresh");
      if (refreshed.response.status === 204) {
        await ensureForgeryCookie();
        result = await run();
      }
    }
    if (result.response.ok && result.data !== undefined) {
      return { ok: true, data: result.data };
    }
    if (result.response.ok) {
      // An answer with no body (204): the caller asked for nothing back.
      return { ok: true, data: undefined as T };
    }
    return { ok: false, failure: failureFromResponse(result.error, result.response) };
  } catch {
    return { ok: false, failure: failureFromNetworkError() };
  }
}

/** The failure of an outcome as the problems of a save: the fields it names, or its message as one general problem. */
export function problemsOf(failure: ApiFailure): Record<string, string[]> {
  if (failure.fields && Object.keys(failure.fields).length > 0) {
    return failure.fields;
  }
  return { fields: [failure.message] };
}
