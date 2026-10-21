import test from "node:test";
import assert from "node:assert/strict";
import { makeTemplate, cellKey } from "./model.mjs";
import { validateGrid } from "../schema/validate.mjs";
import { displayRows, indexOf, applyPatch, mergeRect, unmergeAt, toggleStyle, typeInto, pasteBlock, parseClipboard } from "./gridOps.mjs";

const setup = (fixedRatio = 0.05) => {
  const doc = makeTemplate({ rows: 6, columns: 4, fixedRatio });
  const rows = displayRows(doc);
  return { doc, rows, ...indexOf(doc, rows) };
};

test("merge, then undo restores the document and stays valid", () => {
  const { doc, rows, rowPos, colPos } = setup();
  const body0 = rowPos.get(doc.rows[0].id);
  const res = mergeRect(doc, rows, rowPos, colPos, { r0: body0, r1: body0 + 1, c0: 1, c1: 2 });
  assert.ok(res.patch);
  const { doc: merged, inverse } = applyPatch(doc, res.patch);
  assert.equal(merged.merges.length, doc.merges.length + 1);
  assert.deepEqual(validateGrid(merged), []);
  const { doc: back } = applyPatch(merged, inverse);
  assert.deepEqual(back, doc);
});

test("a merge over an existing merge, across zones or of one cell is refused", () => {
  const { doc, rows, rowPos, colPos } = setup();
  assert.ok(mergeRect(doc, rows, rowPos, colPos, { r0: 0, r1: 0, c0: 1, c1: 2 }).error);
  assert.ok(mergeRect(doc, rows, rowPos, colPos, { r0: 1, r1: 2, c0: 0, c1: 0 }).error);
  assert.ok(mergeRect(doc, rows, rowPos, colPos, { r0: 3, r1: 3, c0: 0, c1: 0 }).error);
});

test("unmerge removes exactly that merge", () => {
  const { doc, rowPos, colPos } = setup();
  const res = unmergeAt(doc, rowPos, colPos, 0, 0);
  assert.equal(applyPatch(doc, res.patch).doc.merges.length, doc.merges.length - 1);
  assert.ok(unmergeAt(doc, rowPos, colPos, 5, 3).error);
});

test("bold toggles on, then off", () => {
  const { doc, rows } = setup();
  const keys = [cellKey(rows[3].id, doc.columns[0].id), cellKey(rows[3].id, doc.columns[1].id)];
  const on = applyPatch(doc, toggleStyle(doc, keys, "bold").patch).doc;
  assert.ok(keys.every((k) => on.cells[k].style.bold));
  const off = applyPatch(on, toggleStyle(on, keys, "bold").patch).doc;
  assert.ok(keys.every((k) => !off.cells[k]?.style?.bold));
  assert.deepEqual(validateGrid(on), []);
});

test("pasting a block in fill mode checks every cell against its column", () => {
  const { doc, rows } = setup(0);
  const start = 2; // first body row (after the two header rows)
  const matrix = parseClipboard("note\tPass\t12.5\n\tFail\tabc\n");
  const res = pasteBlock(doc, rows, start, 0, matrix, "fill", new Set());
  assert.equal(res.applied, 3); // the first column holds fixed labels and is skipped
  assert.ok(res.skipped >= 3);
  assert.ok(res.errors.length >= 1);
  assert.ok(!JSON.stringify(res.errors).includes("abc"));
});

test("typing into a fixed cell in fill mode is not offered; author mode writes fixed text", () => {
  const { doc, rows } = setup();
  const r = rows[3], c = doc.columns[1];
  const author = typeInto(doc, r, c, "Instruction", "author");
  assert.equal(author.patch.cells[cellKey(r.id, c.id)].kind, "fixed");
  const bad = typeInto(doc, r, doc.columns[2], "<b>x</b>", "author");
  assert.ok(bad.error);
});
