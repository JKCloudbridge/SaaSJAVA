/**
 * Which look of the home page the person chose: 1 is the Quality Management System look (FA9), 2 the cream look, 3 the
 * board (FA13C). The first stays the one a person gets until they choose another.
 */
export type HomeLook = 1 | 2 | 3 | 4;

const KEY = "home-look";

/** The remembered look; 1 when nothing is stored or storage is closed (a private window, blocked site data). */
export function readHomeLook(): HomeLook {
  try {
    const stored = window.localStorage.getItem(KEY);
    return stored === "2" ? 2 : stored === "3" ? 3 : stored === "4" ? 4 : 1;
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
