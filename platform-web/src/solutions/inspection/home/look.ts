/** Which look of the home page the person chose (FA9): 1 is the Quality Management System look, 2 the cream look. */
export type HomeLook = 1 | 2;

const KEY = "home-look";

/** The remembered look; 1 when nothing is stored or storage is closed (a private window, blocked site data). */
export function readHomeLook(): HomeLook {
  try {
    return window.localStorage.getItem(KEY) === "2" ? 2 : 1;
  } catch {
    return 1;
  }
}

export function writeHomeLook(look: HomeLook): void {
  try {
    window.localStorage.setItem(KEY, String(look));
  } catch {
    // The choice then lasts until the page is closed, which is all a convenience needs.
  }
}
