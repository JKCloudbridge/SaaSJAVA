import { useEffect, useState } from "react";
import { getNumberingInfo, type NumberingInfo } from "./api";

/**
 * The small helpers of the number a template gets while its identity is typed (Functional track FA11B, ADR-0206). Pure
 * where they can be, so they are tested without a browser. The pattern of a number is never here: the server's
 * numbering-info answer says which parts the chosen identification asks for and where its counter stands.
 */

/** What is typed so far; the three fields the number is made of. */
export interface IdentityValues {
  category: string;
  partA: string;
  partB: string;
}

/** A part as the server accepts it: capital letters, digits and a hyphen, at most 10 (typing is corrected as it goes). */
export function normalizePart(raw: string): string {
  return raw.toUpperCase().replace(/[^A-Z0-9-]/g, "").slice(0, 10);
}

/** A serial as it may be typed: digits only, at most 12. */
export function digitsOnly(raw: string): string {
  return raw.replace(/\D/g, "").slice(0, 12);
}

/** A serial written with the zeros the format pads to (never cut). */
export function padSerial(serial: number | string, width: number): string {
  const digits = String(serial);
  return digits.length >= width ? digits : "0".repeat(width - digits.length) + digits;
}

/** The three fields of a record or a form as the question the server answers. */
export function identityOf(fields: Readonly<Record<string, unknown>>): IdentityValues {
  const read = (name: string) => (typeof fields[name] === "string" ? (fields[name] as string) : "");
  return { category: read("category"), partA: read("partA"), partB: read("partB") };
}

/** Whether two sets of typed values mean the same identity. */
export function sameIdentity(a: IdentityValues, b: IdentityValues): boolean {
  return a.category === b.category && a.partA === b.partA && a.partB === b.partB;
}

/** The fields to leave out of the Details tab and the form: the parts the identification does not need, and the serial. */
export function hiddenIdentityFields(
  info: Pick<NumberingInfo, "known" | "partA" | "partB"> | undefined,
  values: IdentityValues,
  hideSerial: boolean,
): string[] {
  const hidden: string[] = [];
  if (hideSerial) {
    hidden.push("serial");
  }
  // While nothing is known (loading, or no identification chosen yet) a part shows only if it already holds a value.
  const needsA = info?.known ? info.partA : values.partA !== "";
  const needsB = info?.known ? info.partB : values.partB !== "";
  if (!needsA) {
    hidden.push("partA");
  }
  if (!needsB) {
    hidden.push("partB");
  }
  return hidden;
}

/**
 * Asks the server what the number of the typed identity is made of, a moment after the person stops typing. An old
 * answer never replaces a newer one, an identification that is not chosen yet asks nothing, and while the parts are
 * being typed the last answer for the same identification stays on the screen so that the form does not jump.
 */
export function useNumberingInfo(values: IdentityValues | undefined, delay = 300): NumberingInfo | undefined {
  const [answer, setAnswer] = useState<{ category: string; info: NumberingInfo } | undefined>();
  const category = values?.category ?? "";
  const partA = values?.partA ?? "";
  const partB = values?.partB ?? "";
  useEffect(() => {
    if (category === "") {
      return;
    }
    let current = true;
    const timer = setTimeout(() => {
      void getNumberingInfo({ category, partA: partA || undefined, partB: partB || undefined }).then((outcome) => {
        if (current && outcome.ok) {
          setAnswer({ category, info: outcome.data });
        }
      });
    }, delay);
    return () => {
      current = false;
      clearTimeout(timer);
    };
  }, [category, partA, partB, delay]);
  // The parts a kind asks for depend on the identification alone, so the last answer stays while only the parts change.
  return answer !== undefined && answer.category === category && category !== "" ? answer.info : undefined;
}
