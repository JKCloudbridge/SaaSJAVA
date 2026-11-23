import { fireEvent, render, screen, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { MyApplication, NavigationEntry } from "@/lib/applications/entries";
import { ApplicationGrid, SHOW_GROUP_FILTERS, SHOW_RECENT, type GridStatus } from "./ApplicationGrid";

function page(label: string, pageKey: string, placeholder = false): NavigationEntry {
  return { type: "PLATFORM", label, pageKey, available: true, placeholder: placeholder ? true : undefined };
}

const QUALITY: MyApplication = {
  apiName: "quality",
  label: "Quality Management System",
  iconKey: "clipboard",
  group: "Leadership",
  items: [page("Home", "home"), page("Document Control", "templates"), page("Internal Audit", "internal-audit", true)],
};
const STORES: MyApplication = {
  apiName: "stores",
  label: "Stores",
  iconKey: "store",
  group: "Supply chain",
  items: [page("Home", "home"), page("FIFO", "fifo", true), page("Shelf Life", "shelf-life", true)],
};
const PURCHASING: MyApplication = {
  apiName: "purchasing",
  label: "Purchasing",
  iconKey: "cart",
  group: "Supply chain",
  items: [page("Home", "home"), page("Supplier Selection", "supplier-selection", true)],
};

const READY: GridStatus = { kind: "ready" };

function renderGrid(
  props: Partial<{
    applications: MyApplication[];
    status: GridStatus;
    current: string;
    canSetUp: boolean;
  }> = {},
) {
  return render(
    <ApplicationGrid
      applications={props.applications ?? [QUALITY, STORES, PURCHASING]}
      status={props.status ?? READY}
      current={props.current}
      canSetUp={props.canSetUp ?? false}
    />,
  );
}

function tilesOf(): HTMLElement[] {
  return screen.getAllByRole("link").filter((link) => link.classList.contains("app-tile"));
}

afterEach(() => {
  window.localStorage.clear();
  vi.restoreAllMocks();
});

describe("the grid of applications", () => {
  it("draws one link tile for each application, in the order the API gave", () => {
    renderGrid();

    const tiles = tilesOf();

    expect(tiles.map((tile) => tile.getAttribute("href"))).toEqual([
      "/apps/quality",
      "/apps/stores",
      "/apps/purchasing",
    ]);
    expect(within(tiles[0]!).getByText("Quality Management System")).toBeInTheDocument();
    expect(within(tiles[0]!).getByText("3 tabs")).toBeInTheDocument();
  });

  it("tags only the application whose every function is still being prepared", () => {
    renderGrid();

    expect(within(screen.getByRole("link", { name: /Stores/ })).getByText("Coming soon")).toBeInTheDocument();
    expect(within(screen.getByRole("link", { name: /Quality/ })).queryByText("Coming soon")).toBeNull();
  });

  it("marks the application the person came from", () => {
    renderGrid({ current: "stores" });

    const stores = screen.getByRole("link", { name: /Stores/ });

    expect(stores).toHaveAttribute("aria-current", "page");
    expect(within(stores).getByText("Open now")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: /Purchasing/ })).not.toHaveAttribute("aria-current");
  });

  it("filters as the person types and says how many are shown", () => {
    renderGrid();

    fireEvent.change(screen.getByRole("searchbox", { name: "Search applications" }), { target: { value: "purch" } });

    expect(screen.queryByRole("link", { name: /Stores/ })).toBeNull();
    expect(screen.getByRole("link", { name: /Purchasing/ })).toBeInTheDocument();
    expect(screen.getByRole("status")).toHaveTextContent("Showing 1 of 3 applications");
  });

  it("finds an application by the name of one of its tabs", () => {
    renderGrid();

    fireEvent.change(screen.getByRole("searchbox"), { target: { value: "shelf" } });

    expect(screen.getByRole("link", { name: /Stores/ })).toBeInTheDocument();
    expect(screen.queryByRole("link", { name: /Purchasing/ })).toBeNull();
  });

  it("says plainly when nothing matches and offers to show all", () => {
    renderGrid();

    fireEvent.change(screen.getByRole("searchbox"), { target: { value: "zzz" } });
    expect(screen.getByTestId("grid-no-match")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Show all" }));

    expect(tilesOf()).toHaveLength(3);
  });

  it.skipIf(!SHOW_GROUP_FILTERS)("narrows to one group with the chips, and back with All", () => {
    renderGrid();

    fireEvent.click(screen.getByRole("button", { name: "Supply chain" }));
    expect(screen.queryByRole("link", { name: /Quality/ })).toBeNull();
    expect(screen.getByRole("button", { name: "Supply chain" })).toHaveAttribute("aria-pressed", "true");

    fireEvent.click(screen.getByRole("button", { name: "All" }));
    expect(screen.getByRole("link", { name: /Quality/ })).toBeInTheDocument();
  });

  it("moves between the tiles with the arrow keys, Home and End", () => {
    renderGrid();
    const tiles = tilesOf();
    tiles[0]!.focus();

    fireEvent.keyDown(tiles[0]!, { key: "ArrowRight" });
    expect(document.activeElement).toBe(tiles[1]);
    fireEvent.keyDown(tiles[1]!, { key: "End" });
    expect(document.activeElement).toBe(tiles[2]);
    fireEvent.keyDown(tiles[2]!, { key: "Home" });
    expect(document.activeElement).toBe(tiles[0]);
    fireEvent.keyDown(tiles[0]!, { key: "ArrowLeft" });
    expect(document.activeElement).toBe(tiles[0]);
  });

  it("moves down a row by the number of tiles that sit on the first row", () => {
    renderGrid();
    const tiles = tilesOf();
    // jsdom draws nothing: pretend the grid has two columns.
    tiles.forEach((tile, index) => Object.defineProperty(tile, "offsetTop", { value: Math.floor(index / 2) * 100 }));
    tiles[0]!.focus();

    fireEvent.keyDown(tiles[0]!, { key: "ArrowDown" });

    expect(document.activeElement).toBe(tiles[2]);
  });

  it("focuses the search box on the slash key", () => {
    renderGrid();

    fireEvent.keyDown(document.body, { key: "/" });

    expect(document.activeElement).toBe(screen.getByRole("searchbox"));
  });

  it.skipIf(!SHOW_RECENT)("offers the applications opened last above the grid, and forgets the ones the person no longer has", () => {
    window.localStorage.setItem("platform.recent-applications", JSON.stringify(["gone", "stores"]));
    renderGrid();

    const recent = document.querySelector(".app-grid-recent") as HTMLElement;

    expect(within(recent).getByRole("link", { name: /Stores/ })).toBeInTheDocument();
    expect(within(recent).queryByText("gone")).toBeNull();
  });

  it("remembers an application when it is opened", () => {
    renderGrid();

    fireEvent.click(screen.getByRole("link", { name: /Purchasing/ }));

    expect(window.localStorage.getItem("platform.recent-applications")).toBe(JSON.stringify(["purchasing"]));
  });

  it("offers New application and Manage applications only to a person who may set things up", () => {
    const { unmount } = renderGrid({ canSetUp: false });
    expect(screen.queryByRole("link", { name: /New application/ })).toBeNull();
    expect(screen.queryByRole("link", { name: /Manage applications/ })).toBeNull();
    unmount();

    renderGrid({ canSetUp: true });
    expect(screen.getByRole("link", { name: /New application/ })).toHaveAttribute("href", "/setup/applications/new");
    expect(screen.getByRole("link", { name: /Manage applications/ })).toHaveAttribute("href", "/setup/applications");
  });

  it("shows tiles shaped like the real ones while the list is on its way", () => {
    renderGrid({ applications: [], status: { kind: "loading" } });

    expect(screen.getByTestId("grid-loading")).toBeInTheDocument();
    expect(screen.getByRole("status")).toHaveTextContent("Loading your applications");
  });

  it("shows no error text when the list could not be had", () => {
    renderGrid({ applications: [], status: { kind: "ready" } });

    expect(screen.queryByRole("alert")).toBeNull();
    expect(screen.queryByText(/could not be loaded/i)).toBeNull();
  });

  it("draws the tiles in the order the API gave them and holds no order of its own (FA13A)", () => {
    const tile = (apiName: string, label: string, group: string, tabs: NavigationEntry[]): MyApplication => ({
      apiName,
      label,
      iconKey: "folder",
      group,
      items: tabs,
    });
    const documents = tile("document-management", "Document Management", "Leadership", [
      page("Home", "home"),
      page("Document Control", "templates"),
    ]);
    const capa = tile("capa", "CAPA", "Operations", [page("Home", "home")]);
    const audit = tile("audit-management", "Audit Management", "Leadership", [page("Home", "home")]);
    const supplier = tile("supplier-management", "Supplier Management", "Supply chain", [
      page("Home", "home"),
      page("Supplier Performance Monitoring", "supplier-performance-monitoring", true),
      page("Supplier Portal", "supplier-portal", true),
    ]);
    // Management Core has real pages since MC1 (ADR-0257): it is no longer "coming soon".
    const core = tile("management-core", "Management Core", "Leadership", [
      page("Home", "home"),
      page("Document Control", "templates"),
      page("Change requests", "change-requests"),
      page("Record Control", "fill-ins"),
      page("Report", "fill-in-report"),
      page("KPI management", "kpi-management", true),
    ]);

    renderGrid({ applications: [documents, capa, audit, supplier, core, QUALITY, STORES, PURCHASING] });

    const names = tilesOf().map((link) => link.getAttribute("href"));
    expect(names).toEqual([
      "/apps/document-management",
      "/apps/capa",
      "/apps/audit-management",
      "/apps/supplier-management",
      "/apps/management-core",
      "/apps/quality",
      "/apps/stores",
      "/apps/purchasing",
    ]);
    expect(within(tilesOf()[3]!).getByText("3 tabs")).toBeInTheDocument();
    expect(within(tilesOf()[1]!).getByText("Coming soon")).toBeInTheDocument();
    expect(within(tilesOf()[0]!).queryByText("Coming soon")).toBeNull();
    expect(within(tilesOf()[4]!).getByText("6 tabs")).toBeInTheDocument();
    expect(within(tilesOf()[4]!).queryByText("Coming soon")).toBeNull();
  });
});
