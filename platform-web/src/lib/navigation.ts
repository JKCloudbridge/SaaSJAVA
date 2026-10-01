/**
 * Leaves the page for another address of this site (a full navigation, not a client-side route change). The sign-in
 * flow needs it: after the password check the browser has to follow the API's redirects, which set the session cookies.
 * One function, so tests can replace it instead of the browser's location object.
 */
export function navigate(url: string): void {
  window.location.assign(url);
}
