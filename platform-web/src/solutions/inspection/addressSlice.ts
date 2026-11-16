import type { QueryFilterNode } from "@/lib/records/types";

/**
 * What the address of the list of fill-ins asks it to show (MC3, ADR-0262): the tiles of the Management Core home page
 * open the list "already narrowed to what the number counts". Each part is a condition on a field of the fill-in's
 * header that the record store already judges, or the one server-side filter for fill-ins that wait; the browser adds up
 * nothing and decides nothing. A value the server does not know is left out (a month must look like one), never
 * guessed.
 *
 * The parts: `period` (a month, `yyyy-MM`), `plant` (a code), `sent=1` (waiting or decided), `needs=1` (decided to need
 * an Action plan), `scored=1` (has a score), `plan=none|open` (an ordinary fill-in, or an Action plan not yet
 * approved) and `waiting=review|top`. A plain status is the older `status` parameter and is handled by the page.
 */
export interface AddressSlice {
  /** The conditions, joined with AND, or undefined for none. */
  filter?: QueryFilterNode;
  /** The server-side filter for fill-ins that wait for a decision. */
  waiting?: "review" | "top";
  /** What the narrowing is called, in plain words, for the note over the list. */
  words: string[];
  /** The parameters the slice reads, so a page can keep them when a row is opened. */
  keys: readonly string[];
}

export const SLICE_KEYS = ["period", "plant", "sent", "needs", "scored", "plan", "waiting"] as const;

const MONTH = /^\d{4}-(0[1-9]|1[0-2])$/;
const CODE = /^[A-Za-z0-9_-]{1,10}$/;
const SENT = ["SUBMITTED", "APPROVED"];

/** The slice an address asks for. */
export function addressSlice(params: { get(name: string): string | null } | null | undefined): AddressSlice {
  const items: QueryFilterNode[] = [];
  const words: string[] = [];
  const period = params?.get("period") ?? "";
  if (MONTH.test(period)) {
    items.push({ op: "EQ", field: "period", values: [period] });
    words.push(`the month ${period}`);
  }
  const plant = params?.get("plant") ?? "";
  if (CODE.test(plant)) {
    items.push({ op: "EQ", field: "plant", values: [plant] });
    words.push("one plant");
  }
  const needs = params?.get("needs") === "1";
  if (needs) {
    items.push({ op: "EQ", field: "result", values: ["ACTION_PLAN"] });
    items.push({ op: "IN", field: "status", values: SENT });
    words.push("fill-ins that need an Action plan");
  } else if (params?.get("sent") === "1") {
    items.push({ op: "IN", field: "status", values: SENT });
    words.push("fill-ins that were sent");
  }
  if (params?.get("scored") === "1") {
    items.push({ op: "IS_NOT_EMPTY", field: "score" });
    words.push("with a score");
  }
  const plan = params?.get("plan");
  if (plan === "none" || needs) {
    items.push({ op: "IS_EMPTY", field: "parentFill" });
  } else if (plan === "open") {
    items.push({ op: "IS_NOT_EMPTY", field: "parentFill" });
    items.push({ op: "NE", field: "status", values: ["APPROVED"] });
    words.push("Action plans that are not approved yet");
  }
  const waitingValue = params?.get("waiting");
  const waiting = waitingValue === "review" || waitingValue === "top" ? waitingValue : undefined;
  if (waiting) {
    words.push(waiting === "top" ? "fill-ins waiting for top management" : "fill-ins waiting for review");
  }
  const filter = items.length === 0 ? undefined : items.length === 1 ? items[0] : { op: "AND", items };
  return { filter, waiting, words, keys: SLICE_KEYS };
}

/** A stable text of the slice, for the key of a request: two equal slices share it. */
export function sliceKey(slice: AddressSlice): string {
  return JSON.stringify([slice.filter ?? null, slice.waiting ?? null]);
}

/** Two conditions as one: both must hold; either may be missing. */
export function both(first: QueryFilterNode | undefined, second: QueryFilterNode | undefined): QueryFilterNode | undefined {
  if (!first) {
    return second;
  }
  if (!second) {
    return first;
  }
  return { op: "AND", items: [first, second] };
}

/** The words of the narrowing, as one phrase: "the month 2026-11, fill-ins that were sent". */
export function sliceWords(slice: AddressSlice): string {
  return slice.words.join(", ");
}
