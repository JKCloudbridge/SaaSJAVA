import { act, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { Credential } from "@/lib/credentials/model";
import { fakeApi, json, refusal, type Handler } from "@/test/fakeApi";
import { setUrl } from "@/test/navigation";
import { CredentialsPanel } from "./CredentialsPanel";

vi.mock("next/navigation", async () => (await import("@/test/navigation")).navigationMock);

beforeEach(() => {
  document.cookie = "XSRF-TOKEN=test-token; path=/";
  act(() => setUrl("/setup/credentials"));
});

afterEach(() => {
  vi.unstubAllGlobals();
  document.cookie = "XSRF-TOKEN=; path=/; max-age=0";
});

const CREDENTIAL: Credential = {
  name: "link-key",
  version: 2,
  createdAt: "2026-10-01T09:00:00Z",
  updatedAt: "2026-10-20T09:00:00Z",
  lastUsedAt: "2026-10-30T10:00:00Z",
  usedBy: 1,
  canRollBack: true,
};

function api(items: Credential[] = [CREDENTIAL], extra: Record<string, Handler> = {}) {
  return fakeApi({ "GET /api/v1/credentials": () => json({ data: items }), ...extra });
}

async function openCredential(name = "link-key") {
  fireEvent.click(await screen.findByRole("button", { name }));
  return screen.findByTestId("credential");
}

describe("CredentialsPanel", () => {
  it("lists the names with the version, the dates and how many connections use each, and nothing else", async () => {
    api([CREDENTIAL, { ...CREDENTIAL, name: "other-key", version: 1, lastUsedAt: undefined, usedBy: 0, canRollBack: false }]);
    render(<CredentialsPanel />);

    const rows = await screen.findAllByTestId("credential-row");

    expect(rows).toHaveLength(2);
    expect(rows[0]).toHaveTextContent("link-key");
    expect(rows[0]).toHaveTextContent("1 connection");
    expect(rows[1]).toHaveTextContent("Never used");
    expect(rows[1]).toHaveTextContent("No connection");
  });

  it("opens a credential and says its value is never shown: there is nothing to show or copy", async () => {
    api();
    render(<CredentialsPanel />);

    const page = await openCredential();

    expect(within(page).getByText("2")).toBeInTheDocument();
    expect(within(page).getByText(/never shown to anyone/)).toBeInTheDocument();
    expect(within(page).queryByRole("button", { name: /show|copy|reveal/i })).toBeNull();
  });

  it("sends the value in the body only, and the field is empty again the moment the form is sent", async () => {
    const calls = api([], { "PUT /api/v1/credentials/new-key": () => json({ data: { ...CREDENTIAL, name: "new-key" } }) });
    render(<CredentialsPanel />);
    await screen.findByTestId("credentials");

    fireEvent.click(await screen.findByRole("button", { name: "New credential" }));
    fireEvent.change(await screen.findByLabelText("Name"), { target: { value: "new-key" } });
    const field = screen.getByLabelText("Value") as HTMLInputElement;
    expect(field.type).toBe("password");
    expect(field.autocomplete).toBe("new-password");
    fireEvent.change(field, { target: { value: "canary-value-1" } });
    fireEvent.click(screen.getByRole("button", { name: "Save the credential" }));

    expect(field.value).toBe("");
    expect(await screen.findByTestId("credentials-notice")).toHaveTextContent("The credential was saved. It cannot be shown again.");
    const put = calls.find((call) => call.key === "PUT /api/v1/credentials/new-key");
    expect(put?.body).toBe(JSON.stringify({ value: "canary-value-1" }));
    expect(calls.every((call) => !call.key.includes("canary"))).toBe(true);
    expect(document.body.innerHTML).not.toContain("canary-value-1");
  });

  it("replaces a value as a new version and says that connections must be tested again", async () => {
    const calls = api([CREDENTIAL], { "PUT /api/v1/credentials/link-key": () => json({ data: { ...CREDENTIAL, version: 3 } }) });
    render(<CredentialsPanel />);
    const page = await openCredential();

    fireEvent.click(within(page).getByRole("button", { name: "Replace" }));
    fireEvent.change(await screen.findByLabelText("Value"), { target: { value: "canary-value-2" } });
    fireEvent.click(screen.getByRole("button", { name: "Save a new version" }));

    expect(await screen.findByTestId("credentials-notice")).toHaveTextContent("must be tested again");
    expect(calls.find((call) => call.key === "PUT /api/v1/credentials/link-key")?.body).toBe(JSON.stringify({ value: "canary-value-2" }));
    expect(document.body.innerHTML).not.toContain("canary-value-2");
  });

  it("offers to go back one version only when the API says an earlier version is kept", async () => {
    const calls = api([CREDENTIAL, { ...CREDENTIAL, name: "single", canRollBack: false }], {
      "POST /api/v1/credentials/link-key/rollback": () => json({ data: CREDENTIAL }),
    });
    render(<CredentialsPanel />);
    let page = await openCredential();
    fireEvent.click(within(page).getByRole("button", { name: "Go back one version" }));
    await waitFor(() => expect(calls.some((call) => call.key === "POST /api/v1/credentials/link-key/rollback")).toBe(true));
    expect(await screen.findByTestId("credentials-notice")).toHaveTextContent("The version before is the current one again.");

    act(() => setUrl("/setup/credentials"));
    page = await openCredential("single");
    expect(within(page).queryByRole("button", { name: "Go back one version" })).toBeNull();
  });

  it("asks once before removing, then removes", async () => {
    const calls = api([CREDENTIAL], { "DELETE /api/v1/credentials/link-key": () => json({ data: true }) });
    render(<CredentialsPanel />);
    const page = await openCredential();

    fireEvent.click(within(page).getByRole("button", { name: "Remove" }));
    expect(calls.some((call) => call.key.startsWith("DELETE"))).toBe(false);
    const dialog = await screen.findByTestId("credential-remove");
    fireEvent.click(within(dialog).getByRole("button", { name: "Remove the credential" }));

    await waitFor(() => expect(calls.some((call) => call.key === "DELETE /api/v1/credentials/link-key")).toBe(true));
  });

  it("shows the API's own words when a connection that is on keeps a credential from being removed", async () => {
    api([CREDENTIAL], {
      "DELETE /api/v1/credentials/link-key": () =>
        refusal("CONFLICT", "A connection that is switched on uses this credential. Switch it off first.", 409),
    });
    render(<CredentialsPanel />);
    const page = await openCredential();

    fireEvent.click(within(page).getByRole("button", { name: "Remove" }));
    fireEvent.click(within(await screen.findByTestId("credential-remove")).getByRole("button", { name: "Remove the credential" }));

    expect(await screen.findByTestId("credentials-problem")).toHaveTextContent("Switch it off first.");
  });

  it("shows the API's words for a refused value and keeps the field empty", async () => {
    api([], {
      "PUT /api/v1/credentials/new-key": () =>
        json({ error: { code: "VALIDATION_ERROR", message: "The request is invalid.", fields: { value: ["A credential is 1 to 4096 characters."] } } }, 400),
    });
    render(<CredentialsPanel />);
    await screen.findByTestId("credentials");
    fireEvent.click(await screen.findByRole("button", { name: "New credential" }));
    fireEvent.change(await screen.findByLabelText("Name"), { target: { value: "new-key" } });
    fireEvent.change(screen.getByLabelText("Value"), { target: { value: "x" } });

    fireEvent.click(screen.getByRole("button", { name: "Save the credential" }));

    expect(await screen.findByTestId("credentials-problem")).toHaveTextContent("A credential is 1 to 4096 characters.");
    expect((screen.getByLabelText("Value") as HTMLInputElement).value).toBe("");
  });
});
