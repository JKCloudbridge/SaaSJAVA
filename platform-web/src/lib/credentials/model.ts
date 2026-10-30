import type { components } from "@/lib/api/generated/schema";

export type Credential = components["schemas"]["CredentialView"];

/**
 * When a credential was last used, for a person. The API sends the clock hour of the latest use, or nothing when it was
 * never used. Interface wording only: nothing here decides anything.
 */
export function lastUsedWords(item: Pick<Credential, "lastUsedAt">): string {
  return item.lastUsedAt ? new Date(item.lastUsedAt).toLocaleString() : "Never used";
}

/** How many connections name a credential, in words. */
export function usedByWords(item: Pick<Credential, "usedBy">): string {
  if (item.usedBy === 0) {
    return "No connection";
  }
  return item.usedBy === 1 ? "1 connection" : `${item.usedBy} connections`;
}
