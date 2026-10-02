/**
 * What a person reads when a call ends in a way the page cannot do more about. The API's status decides which; these
 * words only present it.
 */
export const COMMON_TEXT = {
  tooMany: "Too many requests. Wait a while and try again.",
  unavailable: "This is not available right now. Try again in a moment.",
  network: "The server could not be reached. Check your connection and try again.",
  invalidLink: "This link is not valid or has expired. Ask for a new one.",
  passwordsDiffer: "The two passwords are not the same.",
} as const;

/** The text for an answer that is not the one the page was waiting for. */
export function commonFailureText(status: number): string {
  return status === 429 ? COMMON_TEXT.tooMany : COMMON_TEXT.unavailable;
}

/** The first problem the API listed for a field, or undefined. */
export function firstProblem(fields: Record<string, string[]> | undefined, name: string): string | undefined {
  return fields?.[name]?.[0];
}

/** All the problems the API listed for a field as one sentence, or undefined. */
export function problems(fields: Record<string, string[]> | undefined, name: string): string | undefined {
  const list = fields?.[name];
  return list && list.length > 0 ? list.join(" ") : undefined;
}
