/**
 * Where the gear of the shell leads, from the abilities the API lists for the signed-in member, or null when the member
 * has none of the abilities the setup pages are for. A convenience only: the API decides again on every call the page
 * makes (the standing rule of the frontend), and a link that is shown for a member who then gets a refusal shows the
 * API's own words.
 */
export function setupHref(abilities: readonly string[]): string | null {
  const manageAccess = abilities.includes("access.manage");
  if (!manageAccess && !abilities.includes("metadata.view")) {
    return null;
  }
  return manageAccess ? "/setup/profiles" : "/setup/objects";
}
