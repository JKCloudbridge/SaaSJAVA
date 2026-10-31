import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { fakeApi, json, refusal, type Handler } from "@/test/fakeApi";
import { CONNECTION } from "@/test/integrations";
import { IntegrationMonitor } from "./IntegrationMonitor";

beforeEach(() => {
  document.cookie = "XSRF-TOKEN=test-token; path=/";
});

afterEach(() => {
  vi.unstubAllGlobals();
  document.cookie = "XSRF-TOKEN=; path=/; max-age=0";
});

const ACTION = { key: "act-1", label: "Pause everything", effect: "Nothing goes out until you undo this." };

function health(overrides: Record<string, unknown> = {}) {
  return {
    data: {
      name: "link-a",
      label: "Link A",
      connectionState: "S",
      circuit: {
        state: "ST",
        words: "The API says how sending stands.",
        forced: null,
        consecutiveFailures: 2,
        openUntil: null,
        openedAt: null,
        lastSuccessAt: "2026-10-31T10:00:00Z",
        lastFailureAt: "2026-10-31T10:05:00Z",
        lastFailureReason: "any-key",
        lastFailureWords: "The API's words for the last failure.",
        actions: [ACTION],
      },
      limits: {
        ratePerMinute: 600,
        maxConcurrent: 4,
        ratePerMinuteIsDefault: true,
        maxConcurrentIsDefault: true,
        ratePerMinuteCeiling: 6000,
        maxConcurrentCeiling: 32,
      },
      waiting: 3,
      sending: 1,
      waitingRemote: 0,
      deadLettered: 2,
      done: 40,
      oldestOpenSince: "2026-10-31T09:00:00Z",
      lastDoneAt: "2026-10-31T10:00:00Z",
      lastDeadLetterAt: "2026-10-31T10:06:00Z",
      ...overrides,
    },
  };
}

function delivery(id: string, canReplay: boolean, extra: Record<string, unknown> = {}) {
  return {
    id,
    direction: "OUT",
    kind: "UPDATE",
    object: "Order__c",
    state: "X",
    reason: "any-key",
    words: "The API's words for why it was set aside.",
    createdAt: "2026-10-31T09:00:00Z",
    finishedAt: "2026-10-31T10:06:00Z",
    stale: false,
    attempts: 5,
    generation: 1,
    canReplay,
    ...extra,
  };
}

function api(extra: Record<string, Handler> = {}) {
  return fakeApi({
    "GET /api/v1/integrations/link-a/health": () => json(health()),
    "GET /api/v1/integrations/link-a/deliveries": (request) => {
      const states = new URL(request.url).searchParams.getAll("state");
      return json({
        data: states.includes("DEAD_LETTERED")
          ? [delivery("d1", true), delivery("d2", false)]
          : [delivery("w1", false, { state: "P", finishedAt: null, waitingFor: "any-wait", waitingWords: "The API says what it waits for.", nextAttemptAt: "2026-10-31T10:10:00Z", attempts: 1 })],
      });
    },
    ...extra,
  });
}

async function monitor() {
  render(<IntegrationMonitor connection={CONNECTION} />);
  return screen.findByTestId("integration-monitor");
}

describe("IntegrationMonitor", () => {
  it("shows how sending stands in the API's words and the queue as the API counts it", async () => {
    api();
    const page = await monitor();

    expect(await within(page).findByTestId("monitor-circuit")).toHaveTextContent("The API says how sending stands.");
    expect(within(page).getByText("The API's words for the last failure.", { exact: false })).toBeInTheDocument();
    expect(within(page).getByText("40")).toBeInTheDocument();
    expect(within(page).getByTestId("monitor-waiting")).toHaveTextContent("The API says what it waits for.");
    expect(within(page).getByTestId("monitor-set-aside")).toHaveTextContent("The API's words for why it was set aside.");
  });

  it("offers exactly the actions the API lists, asks first with the API's sentence, and sends only its key", async () => {
    const calls = api({
      "POST /api/v1/integrations/link-a/circuit": () =>
        json({ data: { ...health().data.circuit, words: "The API says it is paused.", actions: [] } }),
    });
    const page = await monitor();

    fireEvent.click(await within(page).findByRole("button", { name: "Pause everything" }));
    const dialog = await screen.findByTestId("monitor-confirmation");
    expect(dialog).toHaveTextContent("Nothing goes out until you undo this.");
    expect(calls.some((call) => call.key === "POST /api/v1/integrations/link-a/circuit")).toBe(false);

    fireEvent.click(within(dialog).getByRole("button", { name: "Pause everything" }));
    await waitFor(() => expect(calls.some((call) => call.key === "POST /api/v1/integrations/link-a/circuit")).toBe(true));
    expect(JSON.parse(calls.find((call) => call.key.endsWith("/circuit") && call.key.startsWith("POST"))?.body ?? "{}")).toEqual({
      action: "act-1",
    });
    expect(await screen.findByText("The API says it is paused.")).toBeInTheDocument();
  });

  it("sends nothing when the confirmation is cancelled", async () => {
    const calls = api();
    const page = await monitor();

    fireEvent.click(await within(page).findByRole("button", { name: "Pause everything" }));
    fireEvent.click(within(await screen.findByTestId("monitor-confirmation")).getByRole("button", { name: "Cancel" }));

    expect(screen.queryByTestId("monitor-confirmation")).not.toBeInTheDocument();
    expect(calls.filter((call) => call.key.startsWith("POST"))).toEqual([]);
  });

  it("offers to send a change again only where the API says it may be, and asks first", async () => {
    const calls = api({
      "POST /api/v1/integrations/link-a/deliveries/d1/replay": () => json({ data: delivery("d1", false, { state: "P" }) }),
    });
    const page = await monitor();
    expect(within(page).getAllByRole("button", { name: "Send again" })).toHaveLength(1);

    fireEvent.click(within(page).getByRole("button", { name: "Send again" }));
    expect(calls.some((call) => call.key.endsWith("/replay"))).toBe(false);
    fireEvent.click(within(await screen.findByTestId("monitor-confirmation")).getByRole("button", { name: "Send again" }));

    await waitFor(() => expect(calls.some((call) => call.key === "POST /api/v1/integrations/link-a/deliveries/d1/replay")).toBe(true));
    expect(await screen.findByText("It is back in the queue.")).toBeInTheDocument();
  });

  it("shows the API's own words when sending again is refused", async () => {
    api({
      "POST /api/v1/integrations/link-a/deliveries/d1/replay": () =>
        refusal("CONFLICT", "Only a delivery that was set aside can be sent again.", 409),
    });
    const page = await monitor();

    fireEvent.click(await within(page).findByRole("button", { name: "Send again" }));
    fireEvent.click(within(await screen.findByTestId("monitor-confirmation")).getByRole("button", { name: "Send again" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("Only a delivery that was set aside can be sent again.");
  });

  it("shows the attempts of a change with the API's words, and no payload", async () => {
    api({
      "GET /api/v1/integrations/link-a/deliveries/d1/attempts": () =>
        json({
          data: [
            {
              generation: 1,
              attempt: 1,
              startedAt: "2026-10-31T09:00:00Z",
              durationMs: 40,
              outcome: "RETRY",
              reason: "any-key",
              words: "The API's words for the first attempt.",
              statusClass: "5xx",
              httpStatus: 503,
            },
          ],
        }),
    });
    const page = await monitor();

    const [firstAttempts] = await within(page).findAllByRole("button", { name: "Attempts" });
    fireEvent.click(firstAttempts as HTMLElement);

    expect(await within(page).findByRole("list", { name: "Attempts" })).toHaveTextContent("The API's words for the first attempt.");
  });

  it("sends all the oldest again only after a confirmation that says what happens", async () => {
    const calls = api({
      "POST /api/v1/integrations/link-a/deliveries/replay": () => json({ data: { replayed: 1, remaining: 1 } }),
    });
    const page = await monitor();

    fireEvent.click(await within(page).findByRole("button", { name: "Send all again" }));
    const dialog = await screen.findByTestId("monitor-confirmation");
    expect(dialog).toHaveTextContent("up to 100");
    fireEvent.click(within(dialog).getByRole("button", { name: "Send all again" }));

    await waitFor(() => expect(calls.some((call) => call.key === "POST /api/v1/integrations/link-a/deliveries/replay")).toBe(true));
    expect(await screen.findByText("1 put back in the queue, 1 still set aside.")).toBeInTheDocument();
  });

  it("saves the limits as numbers inside the ceilings the API states", async () => {
    const calls = api({
      "PUT /api/v1/integrations/link-a/limits": () =>
        json({ data: { ratePerMinute: 120, maxConcurrent: 2, ratePerMinuteIsDefault: false, maxConcurrentIsDefault: false, ratePerMinuteCeiling: 6000, maxConcurrentCeiling: 32 } }),
    });
    const page = await monitor();

    const rate = await within(page).findByLabelText(/Sends a minute/);
    expect(rate).toHaveAttribute("max", "6000");
    fireEvent.change(rate, { target: { value: "120" } });
    fireEvent.change(within(page).getByLabelText(/Sends at once/), { target: { value: "2" } });
    fireEvent.click(within(page).getByRole("button", { name: "Save limits" }));

    await waitFor(() => expect(calls.some((call) => call.key === "PUT /api/v1/integrations/link-a/limits")).toBe(true));
    expect(JSON.parse(calls.find((call) => call.key.startsWith("PUT"))?.body ?? "{}")).toEqual({ ratePerMinute: 120, maxConcurrent: 2 });
  });

  it("shows how the files of a connection that picks them up stand, in the API's words", async () => {
    api({
      "GET /api/v1/integrations/link-a/health": () =>
        json(
          health({
            files: {
              lastLookAt: "2026-10-31T10:20:00Z",
              lastResult: "FAILED",
              lastReason: "any-key",
              lastReasonWords: "The API's words for why the last look failed.",
              nextLookAt: "2026-10-31T10:25:00Z",
              filesWaiting: 4,
              filesInProgress: 1,
              filesDone: 30,
              filesSetAside: 2,
            },
          }),
        ),
    });
    const page = await monitor();

    expect(await within(page).findByTestId("monitor-files-reason")).toHaveTextContent("The API's words for why the last look failed.");
    expect(within(page).getByText("Files waiting in the inbox").nextElementSibling).toHaveTextContent("4");
    expect(within(page).getByText("Files set aside").nextElementSibling).toHaveTextContent("2");
  });

  it("draws no file facts for a connection that picks up no files", async () => {
    api();
    const page = await monitor();

    await within(page).findByTestId("monitor-circuit");
    expect(within(page).queryByTestId("monitor-files-reason")).not.toBeInTheDocument();
    expect(within(page).queryByText("Files waiting in the inbox")).not.toBeInTheDocument();
  });
});
