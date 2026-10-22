/**
 * The undo and redo history of an edit session (FA3, ADR-0172 section 4). It keeps the documents themselves: a document
 * is never changed in place and shares what an edit did not touch with the one before, so a step costs little and undo
 * restores exactly what was there. A new edit forgets what had been undone.
 */
export interface History<T> {
  past: readonly T[];
  present: T;
  future: readonly T[];
}

/** The most steps kept. Older ones are forgotten. */
export const HISTORY_MAX = 100;

export function start<T>(present: T): History<T> {
  return { past: [], present, future: [] };
}

/** The history after an edit that gave `next`. An edit that gave the same document is not a step. */
export function push<T>(history: History<T>, next: T, max: number = HISTORY_MAX): History<T> {
  if (Object.is(next, history.present)) {
    return history;
  }
  const past = [...history.past, history.present];
  return { past: past.length > max ? past.slice(past.length - max) : past, present: next, future: [] };
}

export function canUndo<T>(history: History<T>): boolean {
  return history.past.length > 0;
}

export function canRedo<T>(history: History<T>): boolean {
  return history.future.length > 0;
}

export function undo<T>(history: History<T>): History<T> {
  if (history.past.length === 0) {
    return history;
  }
  const present = history.past[history.past.length - 1] as T;
  return { past: history.past.slice(0, -1), present, future: [history.present, ...history.future] };
}

export function redo<T>(history: History<T>): History<T> {
  if (history.future.length === 0) {
    return history;
  }
  const present = history.future[0] as T;
  const future = history.future.slice(1);
  return { past: [...history.past, history.present], present, future };
}
