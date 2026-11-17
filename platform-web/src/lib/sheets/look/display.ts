import type { GridCell, GridDoc, Zone } from "../types";
import { formatNumber, formatsNumbers, isPlainNumber, type AnswerKind } from "./numberFormat";
import type { ResolvedLook } from "./look";

/**
 * What a cell shows, in words, and what kind of thing it is (SH4). Pure: the grid only draws the answer.
 *
 *  - a formula cell shows its answer (the server's, or the browser's while an author types), a number written in the
 *    cell's number format, anything else as it is, an error as its code;
 *  - a yes/no value shows the words Yes and No (a tick is never the only sign);
 *  - a number typed or fixed in a cell whose format formats numbers, or whose column holds numbers, is formatted; a value
 *    that is text, such as a date, is shown as it is;
 *  - an attachment cell shows a quiet placeholder with the count in fixed words;
 *  - nothing is invented: a formula without an answer shows nothing.
 */

export interface Answer {
  text: string;
  kind: AnswerKind;
}

export interface CellDisplay {
  text: string;
  /** The displayed number, for the conditional colours and for right alignment; null when the cell does not show one. */
  number: number | null;
  /** A quiet placeholder rather than content (an attachment cell with nothing in it). */
  quiet: boolean;
  /** A cell with a list of values the author sees marked (yes/no and picklist input cells). */
  list: string | null;
  /** An attachment cell: the grid draws a paperclip beside the count (SH5.1). */
  attachment?: boolean;
}

export type InputType = "text" | "number" | "date" | "picklist" | "boolean" | "boolean_reason" | "attachment";

/** The prompt a Yes/No cell with a reason states (SH5.1, ADR-0263), or an empty text. */
export function promptOf(doc: GridDoc, rowId: string, columnId: string): string {
  const stated = doc.sheet?.inputs?.[`${rowId}:${columnId}`];
  const prompt = typeof stated === "object" && stated !== null ? (stated as { prompt?: unknown }).prompt : undefined;
  return typeof prompt === "string" ? prompt : "";
}

/** The words for a count of attachments. */
export function attachmentWords(count: number): string {
  if (count <= 0) return "(no attachments yet)";
  return count === 1 ? "(1 attachment)" : `(${count} attachments)`;
}

/** What type an input cell has: its own statement, else its column's. A fixed cell has none. */
export function typeOf(doc: GridDoc, rowId: string, columnId: string, zone: Zone, cell: GridCell | undefined): InputType | null {
  const isInput = zone === "blank" || (zone === "body" && (cell?.kind ?? (cell?.value != null ? "fixed" : "input")) === "input");
  if (!isInput) return null;
  const stated = doc.sheet?.inputs?.[`${rowId}:${columnId}`];
  const type = typeof stated === "object" && stated !== null ? (stated as { type?: unknown }).type : undefined;
  if (
    type === "text" || type === "number" || type === "date" || type === "picklist" || type === "boolean"
    || type === "boolean_reason" || type === "attachment"
  ) {
    return type;
  }
  return doc.columns.find((column) => column.id === columnId)?.type ?? "text";
}

function listOf(doc: GridDoc, rowId: string, columnId: string, type: InputType): string | null {
  if (type === "boolean" || type === "boolean_reason") return "List: Yes, No";
  if (type !== "picklist") return null;
  const stated = doc.sheet?.inputs?.[`${rowId}:${columnId}`];
  const own = typeof stated === "object" && stated !== null ? (stated as { picklist?: { values?: unknown } }).picklist?.values : undefined;
  const values = Array.isArray(own)
    ? own.filter((v): v is string => typeof v === "string")
    : (doc.columns.find((column) => column.id === columnId)?.picklist?.values ?? []);
  if (values.length === 0) return null;
  const shown = values.slice(0, 5).join(", ");
  return `List: ${shown}${values.length > 5 ? ", and more" : ""}`;
}

export function displayOf(
  doc: GridDoc,
  rowId: string,
  columnId: string,
  zone: Zone,
  cell: GridCell | undefined,
  look: ResolvedLook,
  answer: Answer | undefined,
  withLists: boolean,
): CellDisplay {
  const type = typeOf(doc, rowId, columnId, zone, cell);
  const list = withLists && type !== null ? listOf(doc, rowId, columnId, type) : null;
  const plain = (text: string): CellDisplay => ({ text, number: null, quiet: false, list });
  if (answer !== undefined) {
    if (answer.kind === "number") {
      const n = Number(answer.text);
      return {
        text: formatNumber(look.numberFormat, answer.text),
        number: Number.isFinite(n) ? n : null,
        quiet: false,
        list,
      };
    }
    return plain(answer.text);
  }
  const value = cell?.value;
  if (type === "boolean_reason") {
    // The answer on the top line (a quiet "Yes / No" until there is one), then the prompt followed by the reason
    // (SH5.1). A fill-in's grid hands the answer and the reason over as the answer, a line break, the reason
    // (`choiceCellText`).
    const prompt = promptOf(doc, rowId, columnId);
    let answer = value === true ? "Yes" : value === false ? "No" : "";
    let reason = "";
    if (typeof value === "string" && value !== "") {
      const at = value.indexOf("\n");
      answer = at < 0 ? value : value.slice(0, at);
      reason = at < 0 ? "" : value.slice(at + 1);
    }
    const below = [prompt, reason].filter((part) => part !== "").join(" ");
    const top = answer === "" ? "Yes / No" : answer;
    return { text: below === "" ? top : `${top}\n${below}`, number: null, quiet: answer === "" && below === "", list };
  }
  if (value === true) return plain("Yes");
  if (value === false) return plain("No");
  if (type === "attachment") {
    // A fill-in's grid draws the count of files in the cell as words ("(2 attachments)"); with none, a quiet placeholder.
    // With a prompt (SH5.1) the author's text stands before the count; the cell is then no placeholder.
    const counted = typeof value === "string" && value !== "";
    const prompt = promptOf(doc, rowId, columnId);
    const count = counted ? value : attachmentWords(0);
    return {
      text: prompt === "" ? count : `${prompt} ${count}`,
      number: null,
      quiet: !counted && prompt === "",
      list,
      attachment: true,
    };
  }
  if (typeof value === "string" && value !== "" && isPlainNumber(value) && (type === "number" || formatsNumbers(look.numberFormat))) {
    const n = Number(value);
    return { text: formatNumber(look.numberFormat, value), number: Number.isFinite(n) ? n : null, quiet: false, list };
  }
  return plain(value ?? "");
}
