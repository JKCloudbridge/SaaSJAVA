// One driver per candidate: how the script selects cells, types, and drives merge, bold and italic the way a person
// would (keyboard, toolbar). Everything else (reading the result) goes through window.__spike.getDoc(), which returns the
// CANONICAL document through the adapter, so every check also proves that the adapter reads back what the person did.
//
// Positions are display positions: row 0 is the first header row, column 0 the first column.

const nameOf = (x, y) => { let n = "", k = x + 1; while (k > 0) { const m = (k - 1) % 26; n = String.fromCharCode(65 + m) + n; k = Math.floor((k - 1) / 26); } return n + (y + 1); };

// The grid is the largest canvas on the page (Univer also draws a small one for its formula bar).
async function clickCanvasArea(page) {
  const p = await page.evaluate(() => {
    let best = null, area = 0;
    for (const c of document.querySelectorAll("canvas")) { const r = c.getBoundingClientRect(); const a = r.width * r.height; if (a > area) { area = a; best = r; } }
    return { x: best.x + best.width / 2, y: best.y + best.height / 2 };
  });
  await page.mouse.click(p.x, p.y);
}

const common = {
  async typeText(page, text) { await page.keyboard.type(text, { delay: 30 }); },
  async commit(page) { await page.keyboard.press("Enter"); },
  async shortcut(page, what) { await page.keyboard.press(what === "bold" ? "Control+b" : "Control+i"); },
};

export const DRIVERS = {
  own: {
    ...common,
    path: "own",
    async select(page, r, c) { await page.locator(`[role=gridcell][data-r="${r}"][data-c="${c}"]`).click(); },
    async selectRange(page, r0, c0, r1, c1) {
      await this.select(page, r0, c0);
      for (let i = 0; i < c1 - c0; i++) await page.keyboard.press("Shift+ArrowRight");
      for (let i = 0; i < r1 - r0; i++) await page.keyboard.press("Shift+ArrowDown");
    },
    async toolbar(page, what) { await page.getByRole("button", { name: what === "bold" ? "Bold" : "Italic" }).click(); },
    async merge(page) { await page.getByRole("button", { name: "Merge", exact: true }).click(); },
    async unmerge(page) { await page.getByRole("button", { name: "Unmerge" }).click(); },
    mergeVia: "toolbar buttons",
  },
  fortune: {
    ...common,
    path: "fortune",
    async select(page, r, c) {
      await clickCanvasArea(page);
      await page.evaluate(([r, c]) => window.__spike.api.setSelection([{ row: [r + 1, r + 1], column: [c, c] }]), [r, c]);
      await page.waitForTimeout(300);
    },
    async selectRange(page, r0, c0, r1, c1) {
      await clickCanvasArea(page);
      await page.evaluate(([r0, c0, r1, c1]) => window.__spike.api.setSelection([{ row: [r0 + 1, r1 + 1], column: [c0, c1] }]), [r0, c0, r1, c1]);
      await page.waitForTimeout(300);
    },
    async toolbar(page, what) { await page.locator(`[aria-label^="${what === "bold" ? "Bold" : "Italic"}"]`).first().click(); },
    async merge(page) { await page.locator('.fortune-toolbar-combo-button[data-tips="Merge cells"]').first().click(); },
    // clicking a merged cell selects the whole merged area in the UI; the library call needs that same area
    async unmerge(page) {
      await page.evaluate(() => {
        const a = window.__spike.api; const sel = a.getSelection()[0];
        const m = Object.values(a.getAllSheets()[0].config?.merge ?? {}).find((x) => x.r === sel.row[0] && x.c === sel.column[0]);
        if (m) a.cancelMerge([{ row: [m.r, m.r + m.rs - 1], column: [m.c, m.c + m.cs - 1] }]);
      });
    },
    mergeVia: "toolbar button; unmerge by library call",
  },
  univer: {
    ...common,
    path: "univer",
    async select(page, r, c) {
      await clickCanvasArea(page);
      await page.evaluate(([r, c]) => { window.__spike.univerAPI.getActiveWorkbook().getActiveSheet().getRange(r + 1, c).activate(); }, [r, c]);
      await page.waitForTimeout(300);
    },
    async selectRange(page, r0, c0, r1, c1) {
      await clickCanvasArea(page);
      await page.evaluate(([r0, c0, r1, c1]) => { window.__spike.univerAPI.getActiveWorkbook().getActiveSheet().getRange(r0 + 1, c0, r1 - r0 + 1, c1 - c0 + 1).activate(); }, [r0, c0, r1, c1]);
      await page.waitForTimeout(300);
    },
    async toolbar(page, what) { await page.locator(`button[aria-label="${what === "bold" ? "Bold" : "Italic"}"]`).first().click(); },
    async merge(page) {
      // the toolbar entry opens a menu; the default action is the first item
      await page.locator('[data-u-command="sheet.command.add-worksheet-merge"]').first().click();
      await page.waitForTimeout(250);
      const item = page.getByText(/^Merge all$/i).first();
      if (await item.isVisible().catch(() => false)) await item.click();
    },
    async unmerge(page) { await page.evaluate(() => { window.__spike.univerAPI.getActiveWorkbook().getActiveSheet().getActiveRange().breakApart(); }); },
    mergeVia: "toolbar button; unmerge by library call",
  },
  jspreadsheet: {
    ...common,
    path: "jspreadsheet",
    async select(page, r, c) { await page.locator(`td[data-x="${c}"][data-y="${r}"]`).first().click(); },
    async selectRange(page, r0, c0, r1, c1) { await page.evaluate(([r0, c0, r1, c1]) => window.__spike.ws.updateSelectionFromCoords(c0, r0, c1, r1), [r0, c0, r1, c1]); },
    // the free toolbar has a bold button and no italic button
    async toolbar(page, what) {
      if (what === "italic") throw new Error("the toolbar has no italic button");
      await page.locator('.jtoolbar-item:has(i:text-is("format_bold"))').first().click();
    },
    async merge(page) { await page.locator('.jtoolbar-item:has(i:text-is("web"))').first().click(); },
    async unmerge(page) {
      await page.evaluate((src) => {
        const nameOf = eval(src);
        const ws = window.__spike.ws; const s = ws.selectedCell;
        ws.removeMerge(nameOf(Math.min(s[0], s[2]), Math.min(s[1], s[3])));
      }, nameOf.toString());
    },
    mergeVia: "toolbar button; unmerge by library call",
  },
};

// Puts text on the system clipboard of the page and presses Control+V (a real paste event, as from a spreadsheet application).
export async function pasteText(page, text) {
  await page.evaluate(async (t) => { await navigator.clipboard.writeText(t); }, text);
  await page.keyboard.press("Control+v");
}
