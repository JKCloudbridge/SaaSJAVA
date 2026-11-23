import type { components } from "@/lib/api/generated/schema";

export type Webhook = components["schemas"]["WebhookView"];
export type WebhookCatalogue = components["schemas"]["WebhookCatalogue"];
export type WebhookDelivery = components["schemas"]["WebhookDeliveryView"];
export type WebhookAttempt = components["schemas"]["WebhookAttemptView"];
export type WebhookTestResult = components["schemas"]["WebhookTestView"];

/**
 * The values of a state or a reason that the screens must tell apart to offer a button that fits (Switch on or Switch off,
 * Replay for a delivery that was set aside, the guidance for a member who can no longer act). They are the API's own
 * words for its state, not a list of anything the browser decides: every list a screen draws (the events, the states of
 * the filter, the sentences for a reason) comes from the API's catalogue and answers.
 */
const SWITCHED_OFF = "DISABLED";
const FAILING = "FAILING";
const SET_ASIDE = "DEAD_LETTERED";
const MEMBER_STOPPED = "member_not_active";

/** Whether the webhook is switched off (by an administrator, by the platform, or because it is new). */
export function isSwitchedOff(webhook: Pick<Webhook, "state">): boolean {
  return webhook.state === SWITCHED_OFF;
}

/** Whether the latest attempts failed while it is still on. */
export function isFailing(webhook: Pick<Webhook, "state">): boolean {
  return webhook.state === FAILING;
}

/** Whether the webhook stopped because the member it acts as can no longer act. */
export function stoppedForMember(webhook: Pick<Webhook, "stoppedReason">): boolean {
  return webhook.stoppedReason === MEMBER_STOPPED;
}

/** Whether a delivery was set aside and so may be sent again. */
export function canReplay(delivery: Pick<WebhookDelivery, "state">): boolean {
  return delivery.state === SET_ASIDE;
}

/** A state as the API sent it, for a person: "ACTIVE" is shown as "Active". */
export function stateWords(state: string): string {
  return state.charAt(0).toUpperCase() + state.slice(1).toLowerCase().replace(/_/g, " ");
}

/** A time for a person, or the words for none. Interface wording only. */
export function whenWords(value: string | undefined, none: string): string {
  return value ? new Date(value).toLocaleString() : none;
}

/** The objects a webhook is limited to, or the sentence that says it is not. */
export function objectsWords(webhook: Pick<Webhook, "objects">): string {
  return webhook.objects && webhook.objects.length > 0 ? webhook.objects.join(", ") : "Every object its member may read";
}

/** The titles of the events a webhook asks for, from the catalogue's own list (a key it does not list is shown as it is). */
export function eventsWords(keys: readonly string[], catalogue: Pick<WebhookCatalogue, "events"> | undefined): string {
  return keys.map((key) => catalogue?.events.find((event) => event.key === key)?.title ?? key).join(", ");
}

/** The title of a state of a delivery from the catalogue's list (a state it does not list is shown as it is). */
export function deliveryStateWords(state: string, catalogue: Pick<WebhookCatalogue, "deliveryStates"> | undefined): string {
  return catalogue?.deliveryStates.find((one) => one.key === state)?.title ?? stateWords(state);
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
