import { act, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import type { ReactNode } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { ConnectedApp, ConnectedAppCatalogue, ConnectedAppDetail } from "@/lib/connectedApps/model";
import { SessionProvider } from "@/lib/session/SessionProvider";
import { fakeApi, json, refusal, type Handler } from "@/test/fakeApi";
import { setUrl, currentUrl } from "@/test/navigation";
import { ConnectedAppsPanel } from "./ConnectedAppsPanel";

vi.mock("next/navigation", async () => (await import("@/test/navigation")).navigationMock);

const SECRET = "canary-secret-value-1";

const CATALOGUE: ConnectedAppCatalogue = {
  scopes: [
    { key: "scope-one", title: "First scope", description: "What the first scope allows." },
    { key: "scope-two", title: "Second scope", description: "What the second scope allows." },
  ],
  tokenPath: "/token-path-from-the-api",
  defaultTokenMinutes: 15,
  minTokenMinutes: 5,
  maxTokenMinutes: 60,
  maxOverlapHours: 24,
  maxObjects: 50,
  actingNote: "The acting note, in the API's words.",
  stoppedNote: "The stopped guidance, in the API's words.",
  tokenRequestExample: "POST /token-path-from-the-api\nHost: <the address of your organization>\n\ngrant_type=client_credentials",
};

const APP: ConnectedApp = {
  id: "11111111-1111-4111-8111-111111111111",
  clientId: "ca_exampleclientidentifier01",
  name: "Reporting link",
  description: "Reads for the reports",
  state: "ACTIVE",
  running: true,
  scopes: ["scope-one"],
  actsAs: "Integration A",
  actsAsYou: false,
  tokenMinutes: 15,
  activeSecrets: 1,
  createdAt: "2026-11-01T09:00:00Z",
  version: 3,
};

const DETAIL: ConnectedAppDetail = {
  app: APP,
  secrets: [
    { id: "s-1", number: 1, state: "ACTIVE", createdAt: "2026-11-01T09:00:00Z", lastUsedAt: "2026-11-20T10:00:00Z" },
    { id: "s-0", number: 2, state: "ENDED", createdAt: "2026-10-01T09:00:00Z", endsAt: "2026-11-01T09:00:00Z" },
  ],
};

beforeEach(() => {
  document.cookie = "XSRF-TOKEN=test-token; path=/";
  act(() => setUrl("/setup/connected-apps"));
});

afterEach(() => {
  vi.unstubAllGlobals();
  document.cookie = "XSRF-TOKEN=; path=/; max-age=0";
});

function api(items: ConnectedApp[] = [APP], extra: Record<string, Handler> = {}, detail: ConnectedAppDetail = DETAIL) {
  return fakeApi({
    "GET /api/v1/connected-apps": () => json({ data: items }),
    "GET /api/v1/connected-apps/catalogue": () => json({ data: CATALOGUE }),
    [`GET /api/v1/connected-apps/${detail.app.id}`]: () => json({ data: detail }),
    ...extra,
  });
}

async function openApp(name = "Reporting link") {
  fireEvent.click(await screen.findByRole("button", { name }));
  return screen.findByTestId("connected-app");
}

function signedIn(abilities: string[], children: ReactNode) {
  fakeApi({
    "GET /api/v1/auth/me": () =>
      json({ data: { id: "u1", email: "user-a@example.test", displayName: "User A", platformRoles: [], abilities } }),
    "GET /api/v1/connected-apps": () => json({ data: [APP] }),
    "GET /api/v1/connected-apps/catalogue": () => json({ data: CATALOGUE }),
  });
  return render(<SessionProvider>{children}</SessionProvider>);
}

describe("ConnectedAppsPanel", () => {
  it("lists the applications with their scopes (by the catalogue's titles), whom each acts as and when it was last used", async () => {
    api([APP, { ...APP, id: "22222222-2222-4222-8222-222222222222", name: "Other", lastUsedAt: "2026-11-21T10:00:00Z" }]);
    render(<ConnectedAppsPanel />);

    const rows = await screen.findAllByTestId("connected-app-row");

    expect(rows).toHaveLength(2);
    expect(rows[0]).toHaveTextContent("Reporting link");
    expect(rows[0]).toHaveTextContent("First scope");
    expect(rows[0]).toHaveTextContent("Integration A");
    expect(rows[0]).toHaveTextContent("Never used");
    expect(rows[0]).toHaveTextContent("Running");
  });

  it("tags an application whose member is no longer active and shows the API's guidance from the tag", async () => {
    api([{ ...APP, running: false, stoppedReason: "member_not_active", actsAs: undefined }]);
    render(<ConnectedAppsPanel />);

    const row = await screen.findByTestId("connected-app-row");
    expect(row).toHaveTextContent("Stopped: its member is no longer active");
    fireEvent.click(within(row).getByRole("button", { name: "What to do" }));

    expect(await screen.findByTestId("connected-app-stopped")).toHaveTextContent("The stopped guidance, in the API's words.");
  });

  it("opens an application: the identifier, whom it acts as, the objects, the secrets and the worked request from the API", async () => {
    api();
    render(<ConnectedAppsPanel />);

    const page = await openApp();

    expect(within(page).getByText("ca_exampleclientidentifier01")).toBeInTheDocument();
    expect(within(page).getByText("Integration A")).toBeInTheDocument();
    expect(within(page).getByText("Every object its member may reach")).toBeInTheDocument();
    expect(within(page).getByText("15 minutes")).toBeInTheDocument();
    const secrets = await within(page).findAllByTestId("connected-app-secret");
    expect(secrets).toHaveLength(2);
    expect(within(secrets[0]!).getByRole("button", { name: "End it now" })).toBeInTheDocument();
    expect(within(secrets[1]!).queryByRole("button", { name: "End it now" })).toBeNull();
    expect(within(page).getByTestId("connected-app-example")).toHaveTextContent("POST /token-path-from-the-api");
    expect(within(page).getByTestId("connected-app-example")).toHaveTextContent("<the address of your organization>");
    expect(page.textContent).not.toMatch(/https?:\/\//);
  });

  it("registers an application: the scopes come from the catalogue, the acting note is shown, and the form sends what was chosen", async () => {
    const calls = api([], {
      "POST /api/v1/connected-apps": () =>
        json({ data: { detail: DETAIL, clientSecret: SECRET } }, 201),
    });
    render(<ConnectedAppsPanel />);
    await screen.findByTestId("connected-apps");

    fireEvent.click(await screen.findByRole("button", { name: "New connected application" }));
    expect(await screen.findByTestId("connected-app-acting")).toHaveTextContent("The acting note, in the API's words.");
    expect(screen.getByLabelText(/First scope/)).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText("Name"), { target: { value: "Reporting link" } });
    fireEvent.click(screen.getByLabelText(/Second scope/));
    fireEvent.change(screen.getByLabelText("Only these objects"), { target: { value: "ObjectA__c\nObjectB__c" } });
    fireEvent.change(screen.getByLabelText("Minutes a token lives"), { target: { value: "20" } });
    fireEvent.click(screen.getByRole("button", { name: "Register the application" }));

    await screen.findByTestId("connected-app-secret-dialog");
    const post = calls.find((call) => call.key === "POST /api/v1/connected-apps");
    expect(JSON.parse(post?.body ?? "{}")).toMatchObject({
      name: "Reporting link",
      scopes: ["scope-two"],
      objects: ["ObjectA__c", "ObjectB__c"],
      tokenMinutes: 20,
    });
  });

  it("shows the secret once, never in the address or in storage, and forgets it when the dialog closes", async () => {
    api([], { "POST /api/v1/connected-apps": () => json({ data: { detail: DETAIL, clientSecret: SECRET } }, 201) });
    render(<ConnectedAppsPanel />);
    await screen.findByTestId("connected-apps");
    fireEvent.click(await screen.findByRole("button", { name: "New connected application" }));
    fireEvent.change(await screen.findByLabelText("Name"), { target: { value: "Reporting link" } });
    fireEvent.click(screen.getByLabelText(/First scope/));
    fireEvent.click(screen.getByRole("button", { name: "Register the application" }));

    const dialog = await screen.findByTestId("connected-app-secret-dialog");
    expect((within(dialog).getByLabelText("Client secret") as HTMLInputElement).value).toBe(SECRET);
    expect(within(dialog).getByText(/cannot be shown again/)).toBeInTheDocument();
    expect(currentUrl()).not.toContain(SECRET);
    expect(JSON.stringify(window.localStorage)).not.toContain(SECRET);
    expect(JSON.stringify(window.sessionStorage)).not.toContain(SECRET);

    fireEvent.click(within(dialog).getByRole("button", { name: "I have copied it" }));

    await waitFor(() => expect(screen.queryByTestId("connected-app-secret-dialog")).toBeNull());
    expect(document.body.innerHTML).not.toContain(SECRET);
    expect(currentUrl()).not.toContain(SECRET);
  });

  it("copies the secret with the clipboard and says so", async () => {
    const writeText = vi.fn(async () => undefined);
    vi.stubGlobal("navigator", { ...navigator, clipboard: { writeText } });
    api([], { "POST /api/v1/connected-apps": () => json({ data: { detail: DETAIL, clientSecret: SECRET } }, 201) });
    render(<ConnectedAppsPanel />);
    await screen.findByTestId("connected-apps");
    fireEvent.click(await screen.findByRole("button", { name: "New connected application" }));
    fireEvent.change(await screen.findByLabelText("Name"), { target: { value: "Reporting link" } });
    fireEvent.click(screen.getByLabelText(/First scope/));
    fireEvent.click(screen.getByRole("button", { name: "Register the application" }));

    const dialog = await screen.findByTestId("connected-app-secret-dialog");
    fireEvent.click(within(dialog).getByRole("button", { name: "Copy" }));

    await waitFor(() => expect(writeText).toHaveBeenCalledWith(SECRET));
    expect(await within(dialog).findByText("Copied.")).toBeInTheDocument();
  });

  it("shows the API's words and fields when a registration is refused, and shows no secret", async () => {
    api([], {
      "POST /api/v1/connected-apps": () =>
        json({ error: { code: "VALIDATION_ERROR", message: "The request is invalid.", fields: { scopes: ["Choose at least one scope from the list."] } } }, 400),
    });
    render(<ConnectedAppsPanel />);
    await screen.findByTestId("connected-apps");
    fireEvent.click(await screen.findByRole("button", { name: "New connected application" }));
    fireEvent.change(await screen.findByLabelText("Name"), { target: { value: "Reporting link" } });

    fireEvent.click(screen.getByRole("button", { name: "Register the application" }));

    expect(await screen.findByTestId("connected-apps-problem")).toHaveTextContent("Choose at least one scope from the list.");
    expect(screen.queryByTestId("connected-app-secret-dialog")).toBeNull();
  });

  it("makes a new secret with the overlap and the end-tokens choice, and shows it once", async () => {
    const calls = api([APP], {
      [`POST /api/v1/connected-apps/${APP.id}/secrets/rotate`]: () =>
        json({ data: { detail: DETAIL, clientSecret: SECRET } }, 201),
    });
    render(<ConnectedAppsPanel />);
    const page = await openApp();

    fireEvent.click(within(page).getByRole("button", { name: "Make a new secret" }));
    const dialog = await screen.findByTestId("connected-app-rotate");
    const overlap = within(dialog).getByLabelText("Hours the older secrets keep working") as HTMLInputElement;
    expect(overlap.max).toBe("24");
    fireEvent.change(overlap, { target: { value: "6" } });
    fireEvent.click(within(dialog).getByLabelText(/Also end the tokens/));
    fireEvent.click(within(dialog).getByRole("button", { name: "Make the new secret" }));

    const shown = await screen.findByTestId("connected-app-secret-dialog");
    expect((within(shown).getByLabelText("Client secret") as HTMLInputElement).value).toBe(SECRET);
    const post = calls.find((call) => call.key.endsWith("/secrets/rotate"));
    expect(JSON.parse(post?.body ?? "{}")).toEqual({ overlapHours: 6, endTokensNow: true });
    expect(screen.queryByTestId("connected-app-rotate")).toBeNull();
  });

  it("ends one secret from the detail page", async () => {
    const calls = api([APP], { [`DELETE /api/v1/connected-apps/${APP.id}/secrets/s-1`]: () => json({ data: DETAIL }) });
    render(<ConnectedAppsPanel />);
    const page = await openApp();
    const secrets = await within(page).findAllByTestId("connected-app-secret");

    fireEvent.click(within(secrets[0]!).getByRole("button", { name: "End it now" }));

    await waitFor(() => expect(calls.some((call) => call.key.endsWith("/secrets/s-1"))).toBe(true));
    expect(await screen.findByTestId("connected-apps-notice")).toHaveTextContent("The secret was ended.");
  });

  it("switches an application off, and offers to switch it on when the API says it is off", async () => {
    const calls = api([APP], { [`POST /api/v1/connected-apps/${APP.id}/disable`]: () => json({ data: DETAIL }) });
    render(<ConnectedAppsPanel />);
    let page = await openApp();

    fireEvent.click(within(page).getByRole("button", { name: "Switch off" }));
    await waitFor(() => expect(calls.some((call) => call.key.endsWith("/disable"))).toBe(true));
    expect(await screen.findByTestId("connected-apps-notice")).toHaveTextContent("switched off");

    const off = { ...DETAIL, app: { ...APP, state: "DISABLED", running: false, stoppedReason: "disabled" } };
    api([off.app], {}, off);
    act(() => setUrl("/setup/connected-apps"));
    page = await openApp();
    expect(within(page).getByRole("button", { name: "Switch on" })).toBeInTheDocument();
    expect(within(page).queryByRole("button", { name: "Switch off" })).toBeNull();
  });

  it("asks once before removing, then removes", async () => {
    const calls = api([APP], { [`DELETE /api/v1/connected-apps/${APP.id}`]: () => json({ data: true }) });
    render(<ConnectedAppsPanel />);
    const page = await openApp();

    fireEvent.click(within(page).getByRole("button", { name: "Remove" }));
    expect(calls.some((call) => call.key.startsWith("DELETE"))).toBe(false);
    fireEvent.click(within(await screen.findByTestId("connected-app-remove")).getByRole("button", { name: "Remove the application" }));

    await waitFor(() => expect(calls.some((call) => call.key === `DELETE /api/v1/connected-apps/${APP.id}`)).toBe(true));
  });

  it("changes an application carrying the version it read, and shows the API's words when it was changed meanwhile", async () => {
    const calls = api([APP], {
      [`PUT /api/v1/connected-apps/${APP.id}`]: () =>
        refusal("CONCURRENT_MODIFICATION", "The resource was changed by someone else. Reload it and try again.", 409),
    });
    render(<ConnectedAppsPanel />);
    const page = await openApp();
    await within(page).findAllByTestId("connected-app-secret");

    fireEvent.click(within(page).getByRole("button", { name: "Change" }));
    fireEvent.change(await screen.findByLabelText("Name"), { target: { value: "Reporting link 2" } });
    fireEvent.click(screen.getByRole("button", { name: "Save the changes" }));

    expect(await screen.findByTestId("connected-apps-problem")).toHaveTextContent("changed by someone else");
    const put = calls.find((call) => call.key.startsWith("PUT"));
    expect(JSON.parse(put?.body ?? "{}")).toMatchObject({ name: "Reporting link 2", version: 3, tokenMinutes: 15 });
    expect((screen.getByLabelText("Name") as HTMLInputElement).value).toBe("Reporting link 2");
  });

  it("draws New and the actions only when the API's list of abilities holds clients.manage", async () => {
    const view = signedIn(["clients.view"], <ConnectedAppsPanel />);
    await screen.findByTestId("connected-apps");
    await screen.findByTestId("connected-app-row");
    // Until the API's list of abilities has arrived the button is not hidden; once it says there is no right, it goes.
    await waitFor(() => expect(screen.queryByRole("button", { name: "New connected application" })).toBeNull());
    view.unmount();

    signedIn(["clients.view", "clients.manage"], <ConnectedAppsPanel />);
    expect(await screen.findByRole("button", { name: "New connected application" })).toBeInTheDocument();
  });
});
