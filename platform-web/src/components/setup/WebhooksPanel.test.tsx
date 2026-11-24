import { act, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import type { ReactNode } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { SessionProvider } from "@/lib/session/SessionProvider";
import type { Webhook, WebhookCatalogue, WebhookDelivery } from "@/lib/webhooks/model";
import { fakeApi, json, refusal, type Handler } from "@/test/fakeApi";
import { currentUrl, setUrl } from "@/test/navigation";
import { WebhooksPanel } from "./WebhooksPanel";

vi.mock("next/navigation", async () => (await import("@/test/navigation")).navigationMock);

const SECRET = "canary-signing-secret-1";

const CATALOGUE: WebhookCatalogue = {
  events: [
    { key: "event-one", title: "First event", description: "What the first event is." },
    { key: "event-two", title: "Second event", description: "What the second event is." },
  ],
  payloadVersion: 1,
  toleranceSeconds: 300,
  maxOverlapHours: 24,
  maxSubscriptions: 50,
  maxObjects: 50,
  acting: "The acting words, in the API's words.",
  stopped: "The stopped guidance, in the API's words.",
  secretOnce: "The secret-once words, in the API's words.",
  duplicates: "The duplicates words, in the API's words.",
  deliveryStates: [
    { key: "state-one", title: "First state", description: "What it means." },
    { key: "DEAD_LETTERED", title: "Set aside", description: "It will not succeed by itself." },
  ],
};

const QUEUE = { pending: 0, deadLettered: 1, done: 4, skipped: 2 };

const HOOK: Webhook = {
  id: "11111111-1111-4111-8111-111111111111",
  name: "reporting-hook",
  address: "https://receiver.example.test/in",
  events: ["event-one"],
  state: "ACTIVE",
  running: true,
  verified: true,
  actsAs: "Integration A",
  actsAsYou: false,
  secretNumber: 2,
  secretMadeAt: "2026-11-01T09:00:00Z",
  circuit: "CLOSED",
  createdAt: "2026-11-01T09:00:00Z",
  version: 3,
  queue: QUEUE,
};

const DELIVERY: WebhookDelivery = {
  id: "d-1",
  eventType: "event-one",
  object: "Employee__c",
  recordId: "22222222-2222-4222-8222-222222222222",
  state: "DONE",
  generation: 1,
  attempts: 1,
  createdAt: "2026-11-20T10:00:00Z",
};

const SET_ASIDE: WebhookDelivery = {
  ...DELIVERY,
  id: "d-2",
  state: "DEAD_LETTERED",
  reason: "a-reason-key",
  reasonWords: "The reason, in the platform's words.",
  attempts: 3,
};

beforeEach(() => {
  document.cookie = "XSRF-TOKEN=test-token; path=/";
  act(() => setUrl("/setup/webhooks"));
});

afterEach(() => {
  vi.unstubAllGlobals();
  document.cookie = "XSRF-TOKEN=; path=/; max-age=0";
});

function api(items: Webhook[] = [HOOK], extra: Record<string, Handler> = {}, one: Webhook = HOOK, rows: WebhookDelivery[] = [DELIVERY, SET_ASIDE]) {
  return fakeApi({
    "GET /api/v1/webhooks": () => json({ data: items }),
    "GET /api/v1/webhooks/catalogue": () => json({ data: CATALOGUE }),
    [`GET /api/v1/webhooks/${one.id}`]: () => json({ data: one }),
    [`GET /api/v1/webhooks/${one.id}/deliveries`]: () => json({ data: { items: rows } }),
    ...extra,
  });
}

async function openHook(name = "reporting-hook") {
  fireEvent.click(await screen.findByRole("button", { name }));
  return screen.findByTestId("webhook");
}

function me(abilities: string[]): Record<string, Handler> {
  return {
    "GET /api/v1/auth/me": () =>
      json({ data: { id: "u1", email: "user-a@example.test", displayName: "User A", platformRoles: [], abilities } }),
  };
}

function show(children: ReactNode) {
  return render(<SessionProvider>{children}</SessionProvider>);
}

function signedIn(abilities: string[], children: ReactNode) {
  api([HOOK], me(abilities), HOOK, [SET_ASIDE]);
  return show(children);
}

describe("WebhooksPanel", () => {
  it("lists the webhooks with the titles of their events from the catalogue, whom each acts as and when it last delivered", async () => {
    api([HOOK, { ...HOOK, id: "22222222-2222-4222-8222-222222222222", name: "other", lastSuccessAt: "2026-11-21T10:00:00Z" }]);
    render(<WebhooksPanel />);

    const rows = await screen.findAllByTestId("webhook-row");

    expect(rows).toHaveLength(2);
    expect(rows[0]).toHaveTextContent("reporting-hook");
    expect(rows[0]).toHaveTextContent("First event");
    expect(rows[0]).toHaveTextContent("Integration A");
    expect(rows[0]).toHaveTextContent("Never");
    expect(rows[0]).toHaveTextContent("Running");
  });

  it("tags a webhook whose member is no longer active and shows the API's guidance from the tag", async () => {
    api([{ ...HOOK, running: false, stoppedReason: "member_not_active", actsAs: undefined }]);
    render(<WebhooksPanel />);

    const row = await screen.findByTestId("webhook-row");
    expect(row).toHaveTextContent("Stopped: its member is no longer active");
    fireEvent.click(within(row).getByRole("button", { name: "What to do" }));

    expect(await screen.findByTestId("webhook-stopped")).toHaveTextContent("The stopped guidance, in the API's words.");
  });

  it("tags a webhook that is failing so that it is seen before the platform switches it off", async () => {
    api([{ ...HOOK, state: "FAILING" }]);
    render(<WebhooksPanel />);

    expect(await screen.findByTestId("webhook-row")).toHaveTextContent("Failing");
  });

  it("opens a webhook: the address, whether it was verified, whom it acts as, the secret's number and the delivery log", async () => {
    api();
    render(<WebhooksPanel />);

    const page = await openHook();

    expect(within(page).getByText("https://receiver.example.test/in")).toBeInTheDocument();
    expect(within(page).getByText("Yes, as it stands now")).toBeInTheDocument();
    expect(within(page).getByText("Integration A")).toBeInTheDocument();
    expect(within(page).getByText("Every object its member may read")).toBeInTheDocument();
    expect(within(page).getByText("Secret 2")).toBeInTheDocument();
    expect(within(page).getByText("The secret-once words, in the API's words.")).toBeInTheDocument();
    expect(within(page).getByText("The duplicates words, in the API's words.")).toBeInTheDocument();
    expect(await within(page).findAllByTestId("webhook-delivery")).toHaveLength(2);
  });

  it("makes a webhook: the events come from the catalogue, the acting words are shown and the form sends what was chosen", async () => {
    const calls = api([], { "POST /api/v1/webhooks": () => json({ data: { detail: HOOK, secret: SECRET } }, 201) });
    render(<WebhooksPanel />);
    await screen.findByTestId("webhooks");

    fireEvent.click(await screen.findByRole("button", { name: "New webhook" }));
    expect(await screen.findByTestId("webhook-acting")).toHaveTextContent("The acting words, in the API's words.");
    fireEvent.change(screen.getByLabelText("Name"), { target: { value: "reporting-hook" } });
    fireEvent.change(screen.getByLabelText("Address"), { target: { value: "https://receiver.example.test/in" } });
    fireEvent.click(screen.getByLabelText(/Second event/));
    fireEvent.change(screen.getByLabelText("Only these objects"), { target: { value: "ObjectA__c\nObjectB__c" } });
    fireEvent.click(screen.getByRole("button", { name: "Make the webhook" }));

    await screen.findByTestId("webhook-secret-dialog");
    const post = calls.find((call) => call.key === "POST /api/v1/webhooks");
    expect(JSON.parse(post?.body ?? "{}")).toMatchObject({
      name: "reporting-hook",
      address: "https://receiver.example.test/in",
      events: ["event-two"],
      objects: ["ObjectA__c", "ObjectB__c"],
      version: 0,
    });
  });

  it("shows the signing secret once, never in the address or in storage, and forgets it when the dialog closes", async () => {
    api([], { "POST /api/v1/webhooks": () => json({ data: { detail: HOOK, secret: SECRET } }, 201) });
    render(<WebhooksPanel />);
    await screen.findByTestId("webhooks");
    fireEvent.click(await screen.findByRole("button", { name: "New webhook" }));
    fireEvent.change(await screen.findByLabelText("Name"), { target: { value: "reporting-hook" } });
    fireEvent.click(screen.getByLabelText(/First event/));
    fireEvent.click(screen.getByRole("button", { name: "Make the webhook" }));

    const dialog = await screen.findByTestId("webhook-secret-dialog");
    expect((within(dialog).getByLabelText("Signing secret") as HTMLInputElement).value).toBe(SECRET);
    expect(within(dialog).getByText(/cannot be shown again/)).toBeInTheDocument();
    expect(currentUrl()).not.toContain(SECRET);
    expect(JSON.stringify(window.localStorage)).not.toContain(SECRET);
    expect(JSON.stringify(window.sessionStorage)).not.toContain(SECRET);

    fireEvent.click(within(dialog).getByRole("button", { name: "I have copied it" }));

    await waitFor(() => expect(screen.queryByTestId("webhook-secret-dialog")).toBeNull());
    expect(document.body.innerHTML).not.toContain(SECRET);
    expect(currentUrl()).not.toContain(SECRET);
  });

  it("shows the API's words and fields when a webhook is refused, and shows no secret", async () => {
    api([], {
      "POST /api/v1/webhooks": () =>
        json({ error: { code: "VALIDATION_ERROR", message: "The request is invalid.", fields: { address: ["The address must name a public host."] } } }, 400),
    });
    render(<WebhooksPanel />);
    await screen.findByTestId("webhooks");
    fireEvent.click(await screen.findByRole("button", { name: "New webhook" }));
    fireEvent.change(await screen.findByLabelText("Name"), { target: { value: "reporting-hook" } });

    fireEvent.click(screen.getByRole("button", { name: "Make the webhook" }));

    expect(await screen.findByTestId("webhooks-problem")).toHaveTextContent("The address must name a public host.");
    expect(screen.queryByTestId("webhook-secret-dialog")).toBeNull();
  });

  it("sends a test and shows the platform's sentence, as a success or as a problem", async () => {
    const calls = api([HOOK], {
      [`POST /api/v1/webhooks/${HOOK.id}/test`]: () =>
        json({ data: { delivered: false, reason: "a-reason", statusClass: "5xx", message: "The address did not answer well.", verified: false } }),
    });
    render(<WebhooksPanel />);
    const page = await openHook();

    fireEvent.click(within(page).getByRole("button", { name: "Send a test" }));

    expect(await screen.findByTestId("webhooks-problem")).toHaveTextContent("The address did not answer well.");
    expect(calls.some((call) => call.key.endsWith("/test"))).toBe(true);
  });

  it("makes a new signing secret with the overlap, shows it once and says what the overlap is for", async () => {
    const calls = api([HOOK], {
      [`POST /api/v1/webhooks/${HOOK.id}/secret/rotate`]: () => json({ data: { detail: HOOK, secret: SECRET } }),
    });
    render(<WebhooksPanel />);
    const page = await openHook();

    fireEvent.click(within(page).getByRole("button", { name: "Make a new signing secret" }));
    const dialog = await screen.findByTestId("webhook-rotate");
    const overlap = within(dialog).getByLabelText("Hours the old secret keeps signing") as HTMLInputElement;
    expect(overlap.max).toBe("24");
    expect(dialog).toHaveTextContent("two signatures");
    fireEvent.change(overlap, { target: { value: "6" } });
    fireEvent.click(within(dialog).getByRole("button", { name: "Make the new secret" }));

    const shown = await screen.findByTestId("webhook-secret-dialog");
    expect((within(shown).getByLabelText("Signing secret") as HTMLInputElement).value).toBe(SECRET);
    const post = calls.find((call) => call.key.endsWith("/secret/rotate"));
    expect(JSON.parse(post?.body ?? "{}")).toEqual({ overlapHours: 6 });
    expect(screen.queryByTestId("webhook-rotate")).toBeNull();
  });

  it("offers to stop the old secret while it overlaps", async () => {
    const overlapping = { ...HOOK, previousSecretUntil: "2026-12-01T09:00:00Z" };
    const calls = api([overlapping], { [`POST /api/v1/webhooks/${HOOK.id}/secret/end-overlap`]: () => json({ data: HOOK }) }, overlapping);
    render(<WebhooksPanel />);
    const page = await openHook();

    fireEvent.click(await within(page).findByRole("button", { name: "Stop the old secret now" }));

    await waitFor(() => expect(calls.some((call) => call.key.endsWith("/end-overlap"))).toBe(true));
  });

  it("switches a webhook off, and offers Change and Switch on when the API says it is off", async () => {
    const calls = api([HOOK], { [`POST /api/v1/webhooks/${HOOK.id}/disable`]: () => json({ data: HOOK }) });
    render(<WebhooksPanel />);
    let page = await openHook();
    expect(within(page).queryByRole("button", { name: "Change" })).toBeNull();

    fireEvent.click(within(page).getByRole("button", { name: "Switch off" }));
    await waitFor(() => expect(calls.some((call) => call.key.endsWith("/disable"))).toBe(true));
    expect(await screen.findByTestId("webhooks-notice")).toHaveTextContent("switched off");

    const off = { ...HOOK, state: "DISABLED", running: false, stoppedReason: "disabled", disabledReason: "switched_off" };
    api([off], {}, off);
    act(() => setUrl("/setup/webhooks"));
    page = await openHook();
    expect(within(page).getByRole("button", { name: "Switch on" })).toBeInTheDocument();
    expect(within(page).getByRole("button", { name: "Change" })).toBeInTheDocument();
    expect(within(page).queryByRole("button", { name: "Switch off" })).toBeNull();
  });

  it("asks once before removing, then removes", async () => {
    const calls = api([HOOK], { [`DELETE /api/v1/webhooks/${HOOK.id}`]: () => json({ data: true }) });
    render(<WebhooksPanel />);
    const page = await openHook();

    fireEvent.click(within(page).getByRole("button", { name: "Remove" }));
    expect(calls.some((call) => call.key.startsWith("DELETE"))).toBe(false);
    fireEvent.click(within(await screen.findByTestId("webhook-remove")).getByRole("button", { name: "Remove the webhook" }));

    await waitFor(() => expect(calls.some((call) => call.key === `DELETE /api/v1/webhooks/${HOOK.id}`)).toBe(true));
  });

  it("changes a webhook carrying the version it read, and shows the API's words when it was changed meanwhile", async () => {
    const off = { ...HOOK, state: "DISABLED", running: false, stoppedReason: "disabled" };
    const calls = api([off], {
      [`PUT /api/v1/webhooks/${HOOK.id}`]: () =>
        refusal("CONCURRENT_MODIFICATION", "The resource was changed by someone else. Reload it and try again.", 409),
    }, off);
    render(<WebhooksPanel />);
    const page = await openHook();

    fireEvent.click(within(page).getByRole("button", { name: "Change" }));
    fireEvent.change(await screen.findByLabelText("Name"), { target: { value: "renamed-hook" } });
    fireEvent.click(screen.getByRole("button", { name: "Save the changes" }));

    expect(await screen.findByTestId("webhooks-problem")).toHaveTextContent("changed by someone else");
    const put = calls.find((call) => call.key.startsWith("PUT"));
    expect(JSON.parse(put?.body ?? "{}")).toMatchObject({ name: "renamed-hook", version: 3 });
  });
});

describe("the delivery log", () => {
  it("filters by the states the catalogue lists and asks the API for the chosen one", async () => {
    const calls = api();
    render(<WebhooksPanel />);
    const page = await openHook();
    const filter = (await within(page).findByLabelText("Show")) as HTMLSelectElement;

    expect(Array.from(filter.options).map((option) => option.textContent)).toEqual(["Every delivery", "First state", "Set aside"]);
    fireEvent.change(filter, { target: { value: "state-one" } });

    await waitFor(() => expect(calls.some((call) => call.key.includes("/deliveries") && call.body === "")).toBe(true));
  });

  it("offers to send a delivery again only for one that was set aside, and only to those who may replay", async () => {
    const calls = api([HOOK], {
      ...me(["webhooks.view", "webhooks.manage", "webhooks.replay"]),
      [`POST /api/v1/webhooks/${HOOK.id}/deliveries/d-2/replay`]: () => json({ data: { replayed: 1, more: false } }),
    });
    show(<WebhooksPanel />);
    const page = await openHook();
    const rows = await within(page).findAllByTestId("webhook-delivery");

    expect(within(rows[0]!).queryByRole("button", { name: "Send again" })).toBeNull();
    expect(rows[1]).toHaveTextContent("The reason, in the platform's words.");
    fireEvent.click(within(rows[1]!).getByRole("button", { name: "Send again" }));

    await waitFor(() => expect(calls.some((call) => call.key.endsWith("/d-2/replay"))).toBe(true));
  });

  it("sends every delivery that was set aside again and says what happened, including that more remain", async () => {
    const calls = api([HOOK], {
      ...me(["webhooks.view", "webhooks.manage", "webhooks.replay"]),
      [`POST /api/v1/webhooks/${HOOK.id}/deliveries/replay`]: () => json({ data: { replayed: 500, more: true } }),
    });
    show(<WebhooksPanel />);
    const page = await openHook();

    fireEvent.click(await within(page).findByRole("button", { name: /that were set aside again/ }));

    await waitFor(() => expect(calls.some((call) => call.key.endsWith("/deliveries/replay"))).toBe(true));
    expect(await within(page).findByText(/More remain/)).toBeInTheDocument();
  });

  it("shows the attempts of a delivery in the platform's words", async () => {
    api([HOOK], {
      [`GET /api/v1/webhooks/${HOOK.id}/deliveries/d-2/attempts`]: () =>
        json({
          data: [
            {
              generation: 1,
              attemptNo: 1,
              startedAt: "2026-11-20T10:00:00Z",
              durationMs: 12,
              outcome: "RETRY",
              reason: "a-reason-key",
              reasonWords: "The reason, in the platform's words.",
              statusClass: "5xx",
              httpStatus: 503,
            },
          ],
        }),
    });
    render(<WebhooksPanel />);
    const page = await openHook();
    const rows = await within(page).findAllByTestId("webhook-delivery");

    fireEvent.click(within(rows[1]!).getByRole("button", { name: "Attempts" }));

    const attempts = await screen.findByTestId("webhook-attempts");
    expect(attempts).toHaveTextContent("Attempt 1 of send 1");
    expect(attempts).toHaveTextContent("answer 503");
    expect(attempts).toHaveTextContent("The reason, in the platform's words.");
  });

  it("shows a skipped delivery without naming a record the API did not name", async () => {
    api([HOOK], {}, HOOK, [{ ...DELIVERY, id: "d-3", state: "SKIPPED", recordId: undefined, reasonWords: "Nothing was sent." }]);
    render(<WebhooksPanel />);
    const page = await openHook();

    const row = await within(page).findByTestId("webhook-delivery");

    expect(row).toHaveTextContent("Nothing was sent.");
    expect(row.textContent).not.toContain("22222222");
  });
});

describe("the abilities", () => {
  it("draws New and the actions only when the API's list of abilities holds webhooks.manage", async () => {
    const view = signedIn(["webhooks.view"], <WebhooksPanel />);
    await screen.findByTestId("webhooks");
    await screen.findByTestId("webhook-row");
    // Until the API's list of abilities has arrived the button is not hidden; once it says there is no right, it goes.
    await waitFor(() => expect(screen.queryByRole("button", { name: "New webhook" })).toBeNull());
    view.unmount();

    signedIn(["webhooks.view", "webhooks.manage"], <WebhooksPanel />);
    expect(await screen.findByRole("button", { name: "New webhook" })).toBeInTheDocument();
  });
});
