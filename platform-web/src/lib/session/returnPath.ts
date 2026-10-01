const MAX_LENGTH = 200;

/**
 * The page to return to after signing in, reduced to a path on this site. Anything else (another site, a
 * protocol-relative address, a backslash, control characters, something very long) becomes the home page. The server
 * applies the same rule again before it redirects; this copy only keeps the address bar honest.
 */
export function safeReturnPath(candidate: string | string[] | undefined | null): string {
  const value = Array.isArray(candidate) ? candidate[0] : candidate;
  if (
    typeof value !== "string" ||
    value.length === 0 ||
    value.length > MAX_LENGTH ||
    !value.startsWith("/") ||
    value.startsWith("//") ||
    value.includes("\\") ||
    /[\u0000-\u001f\u007f]/.test(value)
  ) {
    return "/";
  }
  return value;
}
