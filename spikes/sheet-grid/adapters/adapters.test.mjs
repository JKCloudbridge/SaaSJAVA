import test from "node:test";
import assert from "node:assert/strict";
import { makeTemplate } from "../lib/model.mjs";
import { validateGrid } from "../schema/validate.mjs";
import { normalise } from "./common.mjs";
import { toFortune, fromFortune } from "./fortune.mjs";
import { toUniver, fromUniver } from "./univer.mjs";
import { toJspreadsheet, fromJspreadsheet } from "./jspreadsheet.mjs";

for (const [rows, columns, dimension] of [[20, 8, 2], [60, 15, 2], [0, 10, 1], [500, 100, 2]]) {
  const doc = makeTemplate({ rows, columns, dimension });
  test(`round trip ${rows}x${columns} dimension ${dimension}: each library gives back the same canonical document`, () => {
    const expected = normalise(doc);
    assert.deepEqual(normalise(fromFortune(toFortune(doc), doc)), expected);
    assert.deepEqual(normalise(fromUniver(toUniver(doc), doc)), expected);
    assert.deepEqual(normalise(fromJspreadsheet(toJspreadsheet(doc), doc)), expected);
    assert.deepEqual(validateGrid(fromFortune(toFortune(doc), doc)), []);
  });
}
