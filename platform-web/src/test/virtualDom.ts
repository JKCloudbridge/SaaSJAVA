import { afterEach, beforeEach, vi } from "vitest";

/**
 * jsdom lays nothing out, so every element is 0 by 0 and a virtualized list would draw no rows. This gives the scroll
 * container of a grid a size (the virtualizer reads `clientWidth` and `clientHeight`), for the tests of the sheet grid.
 * Call it at the top of a test file; it restores the elements after each test.
 */
export function sizeTheGrid(width = 900, height = 480): void {
  beforeEach(() => {
    vi.spyOn(Element.prototype, "clientWidth", "get").mockReturnValue(width);
    vi.spyOn(Element.prototype, "clientHeight", "get").mockReturnValue(height);
  });
  afterEach(() => {
    vi.restoreAllMocks();
  });
}
