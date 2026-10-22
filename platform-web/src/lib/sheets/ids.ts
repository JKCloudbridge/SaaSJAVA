const ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789";

/**
 * A new identifier for a column, a row or a header row (ADR-0173 section 1): a letter for the kind, then ten random
 * characters, never typed by a person and never one that is already used in the sheet. The pattern is the server's
 * (`[A-Za-z0-9_-]{1,40}`), which a prefix of one letter and ten characters always meets.
 *
 * @param prefix one letter: `c` for a column, `r` for a row, `h` for a header row
 * @param taken the identifiers already in the document
 */
export function newId(prefix: string, taken: ReadonlySet<string>): string {
  for (;;) {
    const random = crypto.getRandomValues(new Uint8Array(10));
    const id = prefix + Array.from(random, (byte) => ALPHABET[byte % ALPHABET.length]).join("");
    if (!taken.has(id)) {
      return id;
    }
  }
}
