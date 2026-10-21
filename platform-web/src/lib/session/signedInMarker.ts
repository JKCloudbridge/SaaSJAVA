/** The query parameter that tells the home page "this load follows a sign-in". */
export const SIGNED_IN_PARAM = "signedIn";

/**
 * The page to return to after a sign-in. Signing in ends in full navigations, so no page state survives them; the one
 * case that needs to know "the person just signed in" is a return to the home page, and it is marked with a parameter
 * the home page removes from the address as soon as it has read it. A deeper page the person asked for is untouched.
 * The marker grants nothing: all it can do is make the home page open the person's organization, which they just
 * signed in to do.
 */
export function returnPathAfterSignIn(returnTo: string): string {
  return returnTo === "/" ? `/?${SIGNED_IN_PARAM}=1` : returnTo;
}

/**
 * Reads the marker once and takes it out of the address (without adding a history entry), so a reload, a bookmark or
 * a later visit to the home page is an ordinary visit and does not open an organization by itself.
 */
export function takeSignedInMarker(): boolean {
  const address = new URL(window.location.href);
  if (address.searchParams.get(SIGNED_IN_PARAM) !== "1") {
    return false;
  }
  address.searchParams.delete(SIGNED_IN_PARAM);
  window.history.replaceState(window.history.state, "", `${address.pathname}${address.search}${address.hash}`);
  return true;
}
