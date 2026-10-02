/** What a token from a mailed link looks like: URL-safe characters only, and neither very short nor very long. */
const TOKEN_PATTERN = /^[A-Za-z0-9_-]{20,200}$/;

/**
 * Reads the one-time token of a mailed link from the part of the address after the `#`, and removes it from the address
 * bar. The part after `#` is never sent to a server, so the token cannot reach an access log or a `Referer` header; the
 * page sends it to the API in the body of a request. Removing it keeps it out of the browser's history and of anything
 * a person copies from the address bar afterwards.
 *
 * @returns the token, or undefined when the address carries none or something that cannot be one
 */
export function takeTokenFromAddress(): string | undefined {
  const hash = window.location.hash.startsWith("#") ? window.location.hash.slice(1) : window.location.hash;
  const token = new URLSearchParams(hash).get("token") ?? undefined;
  if (window.location.hash) {
    window.history.replaceState(null, "", window.location.pathname + window.location.search);
  }
  return token !== undefined && TOKEN_PATTERN.test(token) ? token : undefined;
}
