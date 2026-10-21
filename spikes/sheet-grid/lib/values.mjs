// The rules for the value of a cell, shared by the validator (Node) and the grid (browser). Messages are fixed words.
import { COLUMN_TYPES } from "./model.mjs";

// "<" followed by a letter, "/", "!" or "?" starts markup; a space after it ("5 < 6") is plain text. Tab and line feed
// are allowed (a cell may hold a few lines); every other control character is not.
export function unsafeText(s) {
  return /<[A-Za-z/!?]/.test(s) || /[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f]/.test(s);
}

export function valueProblem(col, v) {
  switch (col.type) {
    case "text": return typeof v === "string" ? null : "A text column holds text.";
    case "number": return typeof v === "string" && /^-?\d{1,38}(\.\d{1,18})?$/.test(v) ? null : "A number column holds digits, with an optional sign and decimals.";
    case "date": return typeof v === "string" && /^\d{4}-\d{2}-\d{2}$/.test(v) && !Number.isNaN(Date.parse(v + "T00:00:00Z")) && new Date(v + "T00:00:00Z").toISOString().startsWith(v) ? null : "A date column holds a date written year-month-day.";
    case "boolean": return typeof v === "boolean" ? null : "A yes or no column holds yes or no.";
    case "picklist": return typeof v === "string" && col.picklist?.values.includes(v) ? null : "A list column holds one of its listed values.";
    default: return COLUMN_TYPES.includes(col.type) ? null : "Unknown column type.";
  }
}

// What a person types into a cell, turned into the stored value for the column (or a refusal in words).
export function parseTyped(col, text) {
  const t = text.trim();
  if (t === "") return { value: null };
  let v = t;
  if (col.type === "boolean") {
    if (/^(yes|true|y|1)$/i.test(t)) v = true;
    else if (/^(no|false|n|0)$/i.test(t)) v = false;
    else return { error: "A yes or no column holds yes or no." };
  } else if (col.type === "picklist") {
    const hit = col.picklist?.values.find((x) => x.toLowerCase() === t.toLowerCase());
    if (!hit) return { error: "A list column holds one of its listed values." };
    v = hit;
  } else if (col.type === "number") v = t.replace(",", ".");
  const bad = valueProblem(col, v);
  if (bad) return { error: bad };
  if (typeof v === "string" && unsafeText(v)) return { error: "A text contains markup or a control character." };
  return { value: v };
}
