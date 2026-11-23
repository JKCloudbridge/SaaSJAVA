import type { components } from "@/lib/api/generated/schema";

export type ConnectedApp = components["schemas"]["ConnectedAppView"];
export type ConnectedAppDetail = components["schemas"]["ConnectedAppDetail"];
export type ConnectedAppCatalogue = components["schemas"]["ConnectedAppCatalogue"];
export type ClientSecret = components["schemas"]["ClientSecretView"];

/**
 * The two values of a state the screens must tell apart to offer Switch off or Switch on, and to end only a secret that
 * still works. They are the API's own words for its state, not a list of anything the browser decides.
 */
const SWITCHED_OFF = "DISABLED";
const SECRET_ENDED = "ENDED";

/**
 * The one reason of a stopped application that has guidance of its own in the catalogue (the member it acts as is no
 * longer active). The words of the guidance are the API's (`stoppedNote`); this only says whether to offer them.
 */
const MEMBER_STOPPED = "member_not_active";

/** Whether the administrator switched the application off. */
export function isSwitchedOff(app: Pick<ConnectedApp, "state">): boolean {
  return app.state === SWITCHED_OFF;
}

/** Whether the application stopped because the member it acts as can no longer act. */
export function stoppedForMember(app: Pick<ConnectedApp, "stoppedReason">): boolean {
  return app.stoppedReason === MEMBER_STOPPED;
}

/** Whether a secret has ended and so offers nothing more to do. */
export function secretEnded(secret: Pick<ClientSecret, "state">): boolean {
  return secret.state === SECRET_ENDED;
}

/** A state as the API sent it, for a person: "ACTIVE" is shown as "Active". */
export function stateWords(state: string): string {
  return state.charAt(0).toUpperCase() + state.slice(1).toLowerCase();
}

/** When an application was last used, for a person. Interface wording only. */
export function lastUsedWords(item: { lastUsedAt?: string }): string {
  return item.lastUsedAt ? new Date(item.lastUsedAt).toLocaleString() : "Never used";
}

/** The objects an application is limited to, or the sentence that says it is not. */
export function objectsWords(app: Pick<ConnectedApp, "objects">): string {
  return app.objects && app.objects.length > 0 ? app.objects.join(", ") : "Every object its member may reach";
}

/** The objects typed in a form (one or more per line, or separated by commas); an empty box means no list. */
export function parseObjects(text: string): string[] | undefined {
  const names = text
    .split(/[\s,;]+/)
    .map((name) => name.trim())
    .filter((name) => name.length > 0);
  return names.length === 0 ? undefined : Array.from(new Set(names));
}

/** Objects for the box of a form. */
export function objectsText(objects: readonly string[] | undefined): string {
  return (objects ?? []).join("\n");
}
