import type { Middleware } from "openapi-fetch";

/** The cookie the API sets for forgery protection, and the header the page sends it back in. */
export const FORGERY_COOKIE = "XSRF-TOKEN";
export const FORGERY_HEADER = "X-XSRF-TOKEN";

const READ_ONLY_METHODS = new Set(["GET", "HEAD", "OPTIONS", "TRACE"]);

/** The value of a cookie of this page, if there is one. */
export function readCookie(name: string): string | undefined {
  if (typeof document === "undefined") {
    return undefined;
  }
  for (const part of document.cookie.split(";")) {
    const [key, ...value] = part.trim().split("=");
    if (key === name) {
      return decodeURIComponent(value.join("="));
    }
  }
  return undefined;
}

/**
 * Sends the forgery-protection value with every request that changes something. The API compares the header with
 * the cookie of the same request; a page on another site can make the browser send the cookie but cannot read it, so
 * it cannot make the header match. Requests that only read carry nothing.
 */
export const forgeryProtectionMiddleware: Middleware = {
  onRequest({ request }) {
    if (!READ_ONLY_METHODS.has(request.method.toUpperCase())) {
      const token = readCookie(FORGERY_COOKIE);
      if (token) {
        request.headers.set(FORGERY_HEADER, token);
      }
    }
    return request;
  },
};
