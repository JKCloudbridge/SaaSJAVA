import { act, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { StrictMode } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { fakeApi, json, type Handler } from "@/test/fakeApi";
import { setUrl } from "@/test/navigation";
import { TabsPanel } from "./TabsPanel";

vi.mock("next/navigation", async () => (await import("@/test/navigation")).navigationMock);

beforeEach(() => {
  document.cookie = "XSRF-TOKEN=test-token; path=/";
  window.sessionStorage.clear();
  act(() => setUrl("/setup/tabs"));
});

afterEach(() => {
  vi.unstubAllGlobals();
  window.sessionStorage.clear();
  document.cookie = "XSRF-TOKEN=; path=/; max-age=0";
});

const MAP_TAB = {
  apiName: "Map__c", label: "Field map", description: "", version: 4, definition: { kind: "CUSTOM", customPage: "FieldMap__c" },
};
const MAP_PAGE = {
  apiName: "FieldMap__c", label: "Field map", description: "", version: 2,
  definition: { frontend: { assetId: "0198a1b2-0000-7000-8000-0000000000a1", sha256: "abcdef0123456789", size: 2048 } },
};
const ASSET = { created: true, id: "0198a1b2-0000-7000-8000-0000000000b2", sha256: "feedfeedfeed", size: 12 };

function answers(extra: Record<string, Handler> = {}) {
  return {
    "GET /api/v1/metadata/tabs": () => json({ data: [MAP_TAB] }),
    "GET /api/v1/metadata/custom-pages": () => json({ data: [MAP_PAGE] }),
    "GET /api/v1/metadata/objects": () =>
      json({ data: [{ apiName: "Account", label: "Account" }, { apiName: "Deal__c", label: "Deal" }] }),
    ...extra,
  };
}

function script(text = "console.log(1)") {
  return new File([text], "map.js", { type: "text/javascript" });
}

function form(name: string) {
  return within(screen.getByRole("form", { name }));
}

describe("TabsPanel", () => {
  it("lists a tab for every object and the custom tabs with the page they show, under React strict mode", async () => {
    fakeApi(answers());

    render(
      <StrictMode>
        <TabsPanel />
      </StrictMode>,
    );

    const rows = await screen.findAllByTestId("tab-row");
    expect(rows).toHaveLength(3);
    expect(rows[0]).toHaveTextContent("Account");
    expect(rows[0]).toHaveTextContent("Object");
    // An object tab needs nothing made, so it does not open; a custom tab does.
    expect(within(rows[0] as HTMLElement).queryByRole("button")).toBeNull();
    expect(rows[2]).toHaveTextContent("Custom");
    expect(rows[2]).toHaveTextContent("Field map");
    expect(within(rows[2] as HTMLElement).getByRole("button", { name: "Field map" })).toBeInTheDocument();
  });

  it("filters the list by what is typed", async () => {
    fakeApi(answers());
    render(<TabsPanel />);
    await screen.findAllByTestId("tab-row");

    fireEvent.change(screen.getByLabelText("Search tabs"), { target: { value: "custom" } });
    expect(screen.getAllByTestId("tab-row")).toHaveLength(1);
    fireEvent.change(screen.getByLabelText("Search tabs"), { target: { value: "nothing like it" } });
    expect(screen.queryAllByTestId("tab-row")).toHaveLength(0);
    expect(screen.getByText("Nothing matches “nothing like it”.")).toBeInTheDocument();
  });

  it("makes a tab with a new custom page: stores the script, makes the page, then the tab", async () => {
    const calls = fakeApi(
      answers({
        "POST /api/v1/metadata/custom-page-assets": () => json({ data: ASSET }, 201),
        "POST /api/v1/metadata/custom-pages": () => json({ data: MAP_PAGE }, 201),
        "POST /api/v1/metadata/tabs": () => json({ data: MAP_TAB }, 201),
      }),
    );
    render(<TabsPanel />);
    await screen.findAllByTestId("tab-row");

    fireEvent.click(screen.getByRole("button", { name: "New tab" }));
    const add = form("New tab");
    fireEvent.change(add.getByLabelText("Name"), { target: { value: "Gauge" } });
    fireEvent.change(add.getByLabelText("Label"), { target: { value: "Gauge board" } });
    fireEvent.change(add.getByLabelText("Script file"), { target: { files: [script()] } });
    fireEvent.change(add.getByLabelText(/Reference for server code/), { target: { value: "gauge-service" } });
    fireEvent.click(screen.getByRole("button", { name: "Add the tab" }));

    expect(await screen.findByTestId("tabs-notice")).toHaveTextContent("The tab was added.");
    const order = calls.map((call) => call.key).filter((key) => key.startsWith("POST"));
    expect(order).toEqual([
      "POST /api/v1/metadata/custom-page-assets",
      "POST /api/v1/metadata/custom-pages",
      "POST /api/v1/metadata/tabs",
    ]);
    const page = JSON.parse(calls.find((call) => call.key === "POST /api/v1/metadata/custom-pages")?.body ?? "{}");
    expect(page).toEqual({
      name: "Gauge",
      label: "Gauge board",
      description: "",
      definition: {
        frontend: { assetId: ASSET.id, sha256: ASSET.sha256, size: ASSET.size },
        backend: { reference: "gauge-service" },
      },
    });
    const tab = JSON.parse(calls.find((call) => call.key === "POST /api/v1/metadata/tabs")?.body ?? "{}");
    expect(tab.definition).toEqual({ kind: "CUSTOM", customPage: "Gauge__c" });
    expect(tab.name).toBe("Gauge");
  });

  it("sends the script as it is, as text, with the forgery token", async () => {
    const seen: { type: string | null; token: string | null }[] = [];
    const calls = fakeApi(
      answers({
        "POST /api/v1/metadata/custom-page-assets": (request) => {
          seen.push({ type: request.headers.get("Content-Type"), token: request.headers.get("X-XSRF-TOKEN") });
          return json({ data: ASSET }, 201);
        },
        "POST /api/v1/metadata/custom-pages": () => json({ data: MAP_PAGE }, 201),
        "POST /api/v1/metadata/tabs": () => json({ data: MAP_TAB }, 201),
      }),
    );
    render(<TabsPanel />);
    await screen.findAllByTestId("tab-row");

    fireEvent.click(screen.getByRole("button", { name: "New tab" }));
    const add = form("New tab");
    fireEvent.change(add.getByLabelText("Name"), { target: { value: "Gauge" } });
    fireEvent.change(add.getByLabelText("Label"), { target: { value: "Gauge" } });
    fireEvent.change(add.getByLabelText("Script file"), { target: { files: [script("let x = 1;")] } });
    fireEvent.click(screen.getByRole("button", { name: "Add the tab" }));

    await waitFor(() => expect(seen).toHaveLength(1));
    expect(seen[0]).toEqual({ type: "text/javascript", token: "test-token" });
    expect(calls.find((call) => call.key === "POST /api/v1/metadata/custom-page-assets")?.body).toBe("let x = 1;");
  });

  it("asks for the script file before it makes a new custom page, and shows the words of the API when the script is refused", async () => {
    const calls = fakeApi(
      answers({
        "POST /api/v1/metadata/custom-page-assets": () =>
          json({ error: { code: "PAYLOAD_TOO_LARGE", message: "The script is larger than the most allowed." } }, 413),
      }),
    );
    render(<TabsPanel />);
    await screen.findAllByTestId("tab-row");
    fireEvent.click(screen.getByRole("button", { name: "New tab" }));
    const add = form("New tab");
    fireEvent.change(add.getByLabelText("Name"), { target: { value: "Gauge" } });
    fireEvent.change(add.getByLabelText("Label"), { target: { value: "Gauge" } });

    fireEvent.click(screen.getByRole("button", { name: "Add the tab" }));
    expect(await screen.findByTestId("tabs-problem")).toHaveTextContent("Choose the script file");

    fireEvent.change(add.getByLabelText("Script file"), { target: { files: [script()] } });
    fireEvent.click(screen.getByRole("button", { name: "Add the tab" }));
    await waitFor(() => expect(screen.getByTestId("tabs-problem")).toHaveTextContent("larger than the most allowed"));
    expect(calls.some((call) => call.key === "POST /api/v1/metadata/custom-pages")).toBe(false);
  });

  it("makes a tab for a custom page that exists, with no upload", async () => {
    const calls = fakeApi(answers({ "POST /api/v1/metadata/tabs": () => json({ data: MAP_TAB }, 201) }));
    render(<TabsPanel />);
    await screen.findAllByTestId("tab-row");

    fireEvent.click(screen.getByRole("button", { name: "New tab" }));
    const add = form("New tab");
    fireEvent.change(add.getByLabelText("Name"), { target: { value: "Again" } });
    fireEvent.change(add.getByLabelText("Label"), { target: { value: "Again" } });
    fireEvent.change(add.getByLabelText("Custom page"), { target: { value: "FieldMap__c" } });
    expect(add.queryByLabelText("Script file")).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "Add the tab" }));

    expect(await screen.findByTestId("tabs-notice")).toHaveTextContent("The tab was added.");
    expect(calls.some((call) => call.key === "POST /api/v1/metadata/custom-page-assets")).toBe(false);
    const tab = JSON.parse(calls.find((call) => call.key === "POST /api/v1/metadata/tabs")?.body ?? "{}");
    expect(tab.definition).toEqual({ kind: "CUSTOM", customPage: "FieldMap__c" });
  });

  it("changes a tab with the version it read and removes one", async () => {
    const calls = fakeApi(
      answers({
        "PUT /api/v1/metadata/tabs/Map__c": () => json({ data: MAP_TAB }),
        "DELETE /api/v1/metadata/tabs/Map__c": () => new Response(null, { status: 204 }),
      }),
    );
    render(<TabsPanel />);
    const rows = await screen.findAllByTestId("tab-row");

    fireEvent.click(within(rows[2] as HTMLElement).getByRole("button", { name: "Field map" }));
    fireEvent.click(await screen.findByRole("button", { name: "Change" }));
    fireEvent.change(form("Change the tab").getByLabelText("Label"), { target: { value: "Map of fields" } });
    fireEvent.click(screen.getByRole("button", { name: "Save the tab" }));
    expect(await screen.findByTestId("tabs-notice")).toHaveTextContent("The tab was saved.");
    const sent = JSON.parse(calls.find((call) => call.key === "PUT /api/v1/metadata/tabs/Map__c")?.body ?? "{}");
    expect(sent).toEqual({
      label: "Map of fields", description: "", version: 4, definition: { kind: "CUSTOM", customPage: "FieldMap__c" },
    });

    fireEvent.click(await screen.findByRole("button", { name: "Remove" }));
    await waitFor(() => expect(calls.some((call) => call.key === "DELETE /api/v1/metadata/tabs/Map__c")).toBe(true));
    expect(await screen.findByTestId("tabs")).toBeInTheDocument();
  });

  it("replaces the script of a page and removes a page", async () => {
    const calls = fakeApi(
      answers({
        "POST /api/v1/metadata/custom-page-assets": () => json({ data: ASSET }, 201),
        "PUT /api/v1/metadata/custom-pages/FieldMap__c": () => json({ data: MAP_PAGE }),
        "DELETE /api/v1/metadata/custom-pages/FieldMap__c": () => new Response(null, { status: 204 }),
      }),
    );
    render(<TabsPanel />);
    const row = await screen.findByTestId("custom-page-row");
    expect(row).toHaveTextContent("abcdef0123");
    expect(row).toHaveTextContent("2.0 KB");

    fireEvent.click(within(row).getByRole("button", { name: "Replace the script" }));
    fireEvent.change(within(row).getByLabelText("New script for Field map"), { target: { files: [script("new")] } });

    expect(await screen.findByTestId("tabs-notice")).toHaveTextContent("The script of the custom page was replaced.");
    const sent = JSON.parse(calls.find((call) => call.key === "PUT /api/v1/metadata/custom-pages/FieldMap__c")?.body ?? "{}");
    expect(sent.version).toBe(2);
    expect(sent.definition.frontend).toEqual({ assetId: ASSET.id, sha256: ASSET.sha256, size: ASSET.size });

    fireEvent.click(within(await screen.findByTestId("custom-page-row")).getByRole("button", { name: "Remove" }));
    await waitFor(() => expect(calls.some((call) => call.key === "DELETE /api/v1/metadata/custom-pages/FieldMap__c")).toBe(true));
  });

  it("records a new tab in the chosen change set instead of making it live", async () => {
    window.sessionStorage.setItem("platform.workingChangeSet", "set-1");
    const calls = fakeApi(answers({ "POST /api/v1/metadata/change-sets/set-1/changes": () => json({ data: {} }, 201) }));
    render(<TabsPanel />);
    await screen.findAllByTestId("tab-row");

    fireEvent.click(screen.getByRole("button", { name: "New tab" }));
    const add = form("New tab");
    fireEvent.change(add.getByLabelText("Name"), { target: { value: "Again" } });
    fireEvent.change(add.getByLabelText("Label"), { target: { value: "Again" } });
    fireEvent.change(add.getByLabelText("Custom page"), { target: { value: "FieldMap__c" } });
    fireEvent.click(screen.getByRole("button", { name: "Add the tab" }));

    expect(await screen.findByTestId("tabs-notice")).toHaveTextContent("goes live when the set is published");
    expect(calls.some((call) => call.key === "POST /api/v1/metadata/tabs")).toBe(false);
    const sent = JSON.parse(calls.find((call) => call.key.endsWith("/changes"))?.body ?? "{}");
    expect(sent.kind).toBe("CREATE_TAB");
    expect(sent.createTab.name).toBe("Again");
  });
});
