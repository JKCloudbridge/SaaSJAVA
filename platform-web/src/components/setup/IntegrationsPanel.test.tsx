import { act, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { fakeApi, json, refusal, type Handler } from "@/test/fakeApi";
import { CONNECTION, KIND, inState } from "@/test/integrations";
import { setUrl } from "@/test/navigation";
import { IntegrationsPanel } from "./IntegrationsPanel";

vi.mock("next/navigation", async () => (await import("@/test/navigation")).navigationMock);

beforeEach(() => {
  document.cookie = "XSRF-TOKEN=test-token; path=/";
  act(() => setUrl("/setup/integrations"));
});

afterEach(() => {
  vi.unstubAllGlobals();
  document.cookie = "XSRF-TOKEN=; path=/; max-age=0";
});

const OBJECTS = {
  data: [{ apiName: "Order__c", label: "Order", pluralLabel: "Orders", kind: "CUSTOM", fieldCount: 3, customFieldCount: 3 }],
};

function api(items = [CONNECTION], extra: Record<string, Handler> = {}) {
  return fakeApi({
    "GET /api/v1/integrations": () => json({ data: items }),
    "GET /api/v1/integrations/connector-types": () => json({ data: [KIND] }),
    "GET /api/v1/metadata/objects": () => json(OBJECTS),
    ...extra,
  });
}

async function openConnection(name = "Link A") {
  fireEvent.click(await screen.findByRole("button", { name }));
  return screen.findByTestId("integration");
}

describe("IntegrationsPanel", () => {
  it("lists the connections with the kind, the address and the API's words for the state", async () => {
    api([CONNECTION, inState("ENABLED", "On", { id: "22222222-2222-4222-8222-222222222222", name: "link-b", label: "Link B" })]);
    render(<IntegrationsPanel />);

    const rows = await screen.findAllByTestId("integration-row");

    expect(rows).toHaveLength(2);
    expect(rows[0]).toHaveTextContent("Link A");
    expect(rows[0]).toHaveTextContent("Service A");
    expect(rows[0]).toHaveTextContent("https://api.example.com");
    expect(rows[0]).toHaveTextContent("Not tested");
    expect(rows[1]).toHaveTextContent("On");
  });

  it("opens a connection with its detail drawn from the connector type's own settings", async () => {
    api([inState("UNTESTED", "Not tested", {
      subscriptions: [{ event: "event-1", object: "Order__c" }],
      scope: [{ object: "Order__c", read: true, create: false, update: true }],
    })]);
    render(<IntegrationsPanel />);

    const page = await openConnection();

    expect(within(page).getByText("Check path")).toBeInTheDocument();
    expect(within(page).getByText("Seconds to wait")).toBeInTheDocument();
    expect(within(page).getByText("Not tested yet.")).toBeInTheDocument();
    expect(within(page).getByTestId("integration-events")).toHaveTextContent("Something happens: Order");
    expect(within(page).getByTestId("integration-scope")).toHaveTextContent("Order: read, change");
  });

  it("tests a connection and shows the API's own words, not a sentence of its own", async () => {
    const calls = api([CONNECTION], {
      "POST /api/v1/integrations/link-a/test": () =>
        json({ data: { result: "PASSED", reason: "ok", words: "The service answered and accepted the connection.", integration: CONNECTION } }),
    });
    render(<IntegrationsPanel />);
    const page = await openConnection();

    fireEvent.click(within(page).getByRole("button", { name: "Test" }));

    expect(await screen.findByTestId("integrations-notice")).toHaveTextContent("The service answered and accepted the connection.");
    expect(calls.some((call) => call.key === "POST /api/v1/integrations/link-a/test")).toBe(true);
  });

  it("shows the last test with the API's sentence and keeps Switch on greyed out until the API says it can", async () => {
    api([inState("UNTESTED", "Not tested", {
      lastTest: { result: "FAILED", reason: "timed_out", words: "The service did not answer in time.", at: "2026-10-30T10:00:00Z" },
    })]);
    render(<IntegrationsPanel />);
    const page = await openConnection();

    expect(within(page).getByText(/The service did not answer in time\./)).toBeInTheDocument();
    expect(within(page).getByRole("button", { name: "Switch on" })).toBeDisabled();
  });

  it("switches a tested connection on with the version it read", async () => {
    const tested = inState("TESTED", "Tested, switched off", { canEnable: true, version: 4 });
    const calls = api([tested], {
      "POST /api/v1/integrations/link-a/enable": () => json({ data: inState("ENABLED", "On", { version: 5 }) }),
    });
    render(<IntegrationsPanel />);
    const page = await openConnection();

    fireEvent.click(within(page).getByRole("button", { name: "Switch on" }));

    expect(await screen.findByTestId("integrations-notice")).toHaveTextContent("The connection is switched on.");
    expect(calls.find((call) => call.key === "POST /api/v1/integrations/link-a/enable")?.body).toBe(JSON.stringify({ version: 4 }));
  });

  it("offers Switch off, not Switch on, for a connection that is on, and greys out Change and Remove as the API says", async () => {
    api([inState("ENABLED", "On", { canChange: false })]);
    render(<IntegrationsPanel />);
    const page = await openConnection();

    expect(within(page).getByRole("button", { name: "Switch off" })).toBeEnabled();
    expect(within(page).queryByRole("button", { name: "Switch on" })).not.toBeInTheDocument();
    expect(within(page).getByRole("button", { name: "Change" })).toBeDisabled();
    expect(within(page).getByRole("button", { name: "Remove" })).toBeDisabled();
  });

  it("makes a connection from a form drawn from the connector type and sends exactly what was typed", async () => {
    const calls = api([], { "POST /api/v1/integrations": () => json({ data: CONNECTION }, 201) });
    render(<IntegrationsPanel />);
    await screen.findByTestId("integrations");

    fireEvent.click(await screen.findByRole("button", { name: "New connection" }));
    fireEvent.change(await screen.findByLabelText("Name"), { target: { value: "link-c" } });
    fireEvent.change(screen.getByLabelText("Label"), { target: { value: "Link C" } });
    fireEvent.change(screen.getByLabelText("Address"), { target: { value: "https://c.example.com" } });
    fireEvent.change(screen.getByLabelText("Seconds to wait"), { target: { value: "7" } });
    expect(screen.getByLabelText("Mode")).toHaveValue("first");
    fireEvent.click(screen.getByRole("button", { name: "Add an event" }));
    fireEvent.change(screen.getByLabelText("Event 1"), { target: { value: "event-1" } });
    fireEvent.change(screen.getByLabelText("Object of event 1"), { target: { value: "Order__c" } });
    fireEvent.click(screen.getByRole("button", { name: "Make the connection" }));

    expect(await screen.findByTestId("integrations-notice")).toHaveTextContent("The connection was made.");
    const sent = JSON.parse(calls.find((call) => call.key === "POST /api/v1/integrations")?.body ?? "{}");
    expect(sent).toMatchObject({
      name: "link-c",
      label: "Link C",
      connectorType: "kind-a",
      baseUrl: "https://c.example.com",
      authKind: "NONE",
      settings: { checkPath: "/", mode: "first", wait: "7" },
      subscriptions: [{ event: "event-1", object: "Order__c" }],
      scope: [],
      version: 0,
    });
  });

  it("shows the API's words for every wrong part of a form, under the form's own place names", async () => {
    api([], {
      "POST /api/v1/integrations": () =>
        json({
          error: {
            code: "VALIDATION_ERROR",
            message: "The request is invalid.",
            fields: { baseUrl: ["The address must start with https://."], "settings.wait": ["Use a number from 1 to 10."] },
          },
        }, 400),
    });
    render(<IntegrationsPanel />);
    await screen.findByTestId("integrations");
    fireEvent.click(await screen.findByRole("button", { name: "New connection" }));
    fireEvent.change(await screen.findByLabelText("Name"), { target: { value: "link-c" } });

    fireEvent.click(screen.getByRole("button", { name: "Make the connection" }));

    const problem = await screen.findByTestId("integrations-problem");
    expect(problem).toHaveTextContent("Address: The address must start with https://.");
    expect(problem).toHaveTextContent("Seconds to wait: Use a number from 1 to 10.");
  });

  it("changes a connection that is off, keeping its name and kind, and sends the version it read", async () => {
    const calls = api([inState("TESTED", "Tested, switched off", { version: 2 })], {
      "PUT /api/v1/integrations/link-a": () => json({ data: CONNECTION }),
    });
    render(<IntegrationsPanel />);
    const page = await openConnection();

    fireEvent.click(within(page).getByRole("button", { name: "Change" }));
    expect(await screen.findByLabelText("Kind")).toBeDisabled();
    expect(screen.queryByLabelText("Name")).not.toBeInTheDocument();
    fireEvent.change(screen.getByLabelText("Address"), { target: { value: "https://moved.example.com" } });
    fireEvent.click(screen.getByRole("button", { name: "Save" }));

    expect(await screen.findByTestId("integrations-notice")).toHaveTextContent("The connection was saved.");
    const sent = JSON.parse(calls.find((call) => call.key === "PUT /api/v1/integrations/link-a")?.body ?? "{}");
    expect(sent).toMatchObject({ name: "link-a", baseUrl: "https://moved.example.com", version: 2 });
  });

  it("shows the API's refusal when a connection that is on is changed behind the screen's back", async () => {
    api([inState("TESTED", "Tested, switched off")], {
      "PUT /api/v1/integrations/link-a": () => refusal("CONFLICT", "Switch the connection off before changing it.", 409),
    });
    render(<IntegrationsPanel />);
    const page = await openConnection();
    fireEvent.click(within(page).getByRole("button", { name: "Change" }));

    fireEvent.click(await screen.findByRole("button", { name: "Save" }));

    expect(await screen.findByTestId("integrations-problem")).toHaveTextContent("Switch the connection off before changing it.");
  });

  it("removes a connection and goes back to the list with the notice", async () => {
    const calls = api([CONNECTION], { "DELETE /api/v1/integrations/link-a": () => json({ data: true }) });
    render(<IntegrationsPanel />);
    const page = await openConnection();

    fireEvent.click(within(page).getByRole("button", { name: "Remove" }));

    expect(await screen.findByTestId("integrations-notice")).toHaveTextContent("The connection was removed.");
    await waitFor(() => expect(calls.some((call) => call.key === "DELETE /api/v1/integrations/link-a")).toBe(true));
  });

  it("offers the credentials the API lists as a choice instead of free text", async () => {
    const calls = api([], {
      "GET /api/v1/credentials": () =>
        json({
          data: [{ name: "link-key", version: 1, createdAt: "2026-10-01T09:00:00Z", updatedAt: "2026-10-01T09:00:00Z", usedBy: 0, canRollBack: false }],
        }),
      "POST /api/v1/integrations": () => json({ data: CONNECTION }, 201),
    });
    render(<IntegrationsPanel />);
    await screen.findByTestId("integrations");

    fireEvent.click(await screen.findByRole("button", { name: "New connection" }));
    const choice = await screen.findByLabelText("Name of the credential");
    expect(choice.tagName).toBe("SELECT");
    fireEvent.change(choice, { target: { value: "link-key" } });
    fireEvent.change(screen.getByLabelText("Name"), { target: { value: "link-c" } });
    fireEvent.click(screen.getByRole("button", { name: "Make the connection" }));

    await waitFor(() => expect(calls.some((call) => call.key === "POST /api/v1/integrations")).toBe(true));
    expect(JSON.parse(calls.find((call) => call.key === "POST /api/v1/integrations")?.body ?? "{}")).toMatchObject({
      credentialName: "link-key",
    });
  });

  it("falls back to typing the name when the API offers no credentials to this person", async () => {
    api([], { "GET /api/v1/credentials": () => refusal("FORBIDDEN", "You are not allowed to perform this action.", 403) });
    render(<IntegrationsPanel />);
    await screen.findByTestId("integrations");

    fireEvent.click(await screen.findByRole("button", { name: "New connection" }));

    expect((await screen.findByLabelText("Name of the credential")).tagName).toBe("INPUT");
  });

  it("shows the API's words when the plan does not include integrations", async () => {
    fakeApi({
      "GET /api/v1/integrations": () => refusal("CONFLICT", "Integrations are not part of this organization's plan.", 409),
      "GET /api/v1/integrations/connector-types": () => refusal("CONFLICT", "no", 409),
      "GET /api/v1/metadata/objects": () => json(OBJECTS),
    });
    render(<IntegrationsPanel />);

    expect(await screen.findByText(/not part of this organization's plan/)).toBeInTheDocument();
  });
});
