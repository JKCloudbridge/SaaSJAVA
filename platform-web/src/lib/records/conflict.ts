import { inputFromValue, recordValue, sameInput } from "./fieldValue";
import type { FieldInput, FormField, RecordView } from "./types";

/**
 * What happened to a record while a person was editing it (Sprint 19, ADR-0129): the server refused the save with
 * `409 CONCURRENT_MODIFICATION` because somebody changed the record first, and nothing was saved. This compares the
 * three versions the person has (the record as they opened it, the record as it is now, and what they typed) and says,
 * field by field, what can be put back on top of the newer record and what needs a choice.
 *
 * <p>It is the default recovery: "reload and reapply". The person's changes are kept whenever they do not collide with
 * the other change, nothing is overwritten without being shown, and the next save carries the newer version number, so
 * the server still decides (it can refuse again if the record moved again).
 */
export interface Clash {
  field: string;
  /** What the person typed. */
  mine: FieldInput;
  /** What the record holds now, because of the other change. */
  theirs: FieldInput;
}

export interface Conflict {
  /** Fields the person changed that nobody else touched (or that the other change set to the same thing): kept as typed. */
  reapply: string[];
  /** Fields both changed to different things: the person chooses. */
  clashes: Clash[];
  /** Fields only the other change touched: the person will see the newer value; listed so nothing changes unannounced. */
  theirChanges: string[];
}

/**
 * Compares the changes.
 *
 * @param fields the fields of the form
 * @param base the record as the person opened it
 * @param latest the record as it is now
 * @param mine the fields the person changed, by what each control holds now
 */
export function compareChanges(
  fields: readonly FormField[],
  base: RecordView,
  latest: RecordView,
  mine: Readonly<Record<string, FieldInput>>,
): Conflict {
  const reapply: string[] = [];
  const clashes: Clash[] = [];
  const theirChanges: string[] = [];
  for (const field of fields) {
    const name = field.apiName;
    const before = inputFromValue(field, recordValue(base, name));
    const now = inputFromValue(field, recordValue(latest, name));
    const theirsChanged = !sameInput(before, now);
    const typed = mine[name];
    if (typed === undefined) {
      if (theirsChanged && !field.calculated && field.kind !== "SYSTEM") {
        theirChanges.push(name);
      }
      continue;
    }
    if (!theirsChanged || sameInput(typed, now)) {
      reapply.push(name);
    } else {
      clashes.push({ field: name, mine: typed, theirs: now });
    }
  }
  return { reapply, clashes, theirChanges };
}

/** Whether there is anything to decide: some field both people changed differently. */
export function needsChoice(conflict: Conflict): boolean {
  return conflict.clashes.length > 0;
}
