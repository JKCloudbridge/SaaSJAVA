import { configure, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { fakeApi, json, refusal, type Call, type Handler } from "@/test/fakeApi";
import { setUrl } from "@/test/navigation";
import { TemplatesPage } from "./TemplatesPage";

vi.mock("next/navigation", async () => (await import("@/test/navigation")).navigationMock);

configure({ asyncUtilTimeout: 5000 });

const ID = "0198f5c0-0000-7000-8000-000000000042";
const VIEWS = "/api/v1/objects/InspectionTemplate/views";
const NONE = {
  state: "NONE",
  activeVersion: 0,
  approvedVersion: 0,
  inReviewVersion: 0,
  draftVersion: 0,
  retiredVersion: 0,
  highestVersion: 0,
};

const text = (apiName: string, label: string) => ({
  apiName,
  label,
  type: "TEXT",
  sortable: true,
  filterOperators: ["EQ", "CONTAINS"],
});

const FORM = {
  data: {
    objectApiName: "InspectionTemplate",
    label: "Template",
    pluralLabel: "Templates",
    layout: { source: "LAYOUT", sections: [] },
    fields: [text("name", "Name"), text("number", "Number"), text("category", "Category"), text("subCategory", "Sub-category")],
    sections: [],
    recordTypes: [],
    actions: { create: true, update: true, delete: false },
  },
};

const view = (id: string, label: string, scope: "PERSONAL" | "SHARED", mine: boolean, definition: unknown = {}) => ({
  id,
  objectApiName: "InspectionTemplate",
  label,
  scope,
  mine,
  definition: { sort: [], columns: [], ...(definition as object) },
  version: 1,
  updatedAt: "2026-10-23T08:00:00Z",
});

const SHARED_VIEW = view("v-shared", "Work instructions", "SHARED", false, {
  filter: { op: "EQ", field: "category", values: ["WI"] },
  sort: [{ field: "number", descending: false }],
  columns: ["number"],
});
const OWN_VIEW = view("v-own", "My drafts", "PERSONAL", true);

interface Setup {
  views?: unknown[];
  extra?: Record<string, Handler>;
}

function setup({ views = [SHARED_VIEW, OWN_VIEW], extra = {} }: Setup = {}): { calls: Call[]; queries: () => Array<Record<string, unknown>> } {
  const queries: Array<Record<string, unknown>> = [];
  const calls = fakeApi({
    "GET /api/v1/objects/InspectionTemplate/form": () => json(FORM),
    [`GET ${VIEWS}`]: () => json({ data: views }),
    "POST /api/v1/templates/query": (request) =>
      request.text().then((body) => {
        queries.push(JSON.parse(body) as Record<string, unknown>);
        return json({
          data: {
            rows: [{ id: ID, name: "Fire exit check", number: "WI/PRD/01", category: "WI", retired: false, lifecycle: NONE }],
            canCreate: true,
            filtered: false,
          },
        });
      }),
    ...extra,
  });
  return { calls, queries: () => queries };
}

const lastQuery = (queries: () => Array<Record<string, unknown>>) => queries()[queries().length - 1];

beforeEach(() => {
  window.localStorage.clear();
  setUrl("/apps/inspection/templates");
});

describe("the title row of a list", () => {
  it("has the view menu at the left, then New, the search box and the settings icon, and no description", async () => {
    setup();
    render(<TemplatesPage />);
    await screen.findByText("Fire exit check");

    const row = screen.getByRole("heading", { level: 1 }).closest(".page-head-row") as HTMLElement;
    expect(within(row).getByTestId("view-menu")).toHaveTextContent("All templates");
    expect(within(row).getByRole("button", { name: "New" })).toBeInTheDocument();
    expect(within(row).getByRole("searchbox")).toBeInTheDocument();
    expect(within(row).getByRole("button", { name: "Templates settings" })).toBeInTheDocument();
    expect(document.querySelector(".page-intro")).toBeNull();
  });

  it("offers Select fields to display and Filter in the settings menu, and no Delete for kept records", async () => {
    setup();
    render(<TemplatesPage />);
    await screen.findByText("Fire exit check");

    fireEvent.click(screen.getByRole("button", { name: "Templates settings" }));

    expect(screen.getByRole("menuitem", { name: "Select fields to display" })).toBeInTheDocument();
    expect(screen.getByRole("menuitem", { name: "Filter" })).toBeInTheDocument();
    expect(screen.queryByRole("menuitem", { name: /Delete/ })).toBeNull();
  });
});

describe("views", () => {
  it("lists the shared and the person's own views, and opening one asks the server with its question", async () => {
    const { queries } = setup();
    render(<TemplatesPage />);
    await screen.findByText("Fire exit check");

    fireEvent.click(screen.getByTestId("view-menu"));
    expect(screen.getByRole("menuitemradio", { name: "All templates", checked: true })).toBeInTheDocument();
    expect(screen.getByText("Shared")).toBeInTheDocument();
    expect(screen.getByText("My views")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("menuitemradio", { name: "Work instructions" }));

    await waitFor(() => expect(lastQuery(queries)?.filter).toEqual({ op: "EQ", field: "category", values: ["WI"] }));
    expect(lastQuery(queries)?.sort).toEqual([{ field: "number", descending: false }]);
    expect(screen.getByTestId("view-menu")).toHaveTextContent("Work instructions");
    // The view named only the number column, so the category and sub-category columns are gone.
    expect(screen.queryByRole("columnheader", { name: /Category/ })).toBeNull();
    expect(screen.getByRole("columnheader", { name: /Number/ })).toBeInTheDocument();
  });

  it("opens again, next time, the view the person had open", async () => {
    window.localStorage.setItem("list-view:InspectionTemplate", "v-shared");
    const { queries } = setup();
    render(<TemplatesPage />);

    await waitFor(() => expect(lastQuery(queries)?.filter).toEqual({ op: "EQ", field: "category", values: ["WI"] }));
    expect(await screen.findByText("Work instructions")).toBeInTheDocument();
  });

  it("saves the list as a new view of the person's own", async () => {
    const saved = view("v-new", "Everything by number", "PERSONAL", true, { sort: [{ field: "number", descending: true }] });
    const { calls } = setup({ extra: { [`POST ${VIEWS}`]: () => json({ data: saved }) } });
    render(<TemplatesPage />);
    await screen.findByText("Fire exit check");
    fireEvent.click(screen.getByRole("button", { name: /^Number/ }));

    fireEvent.click(screen.getByTestId("view-menu"));
    fireEvent.click(screen.getByRole("menuitem", { name: "Save view…" }));
    const dialog = await screen.findByRole("dialog", { name: "Save this view" });
    fireEvent.change(within(dialog).getByLabelText("Name"), { target: { value: "Everything by number" } });
    fireEvent.click(within(dialog).getByRole("button", { name: "Save view" }));

    await waitFor(() => expect(screen.getByTestId("view-menu")).toHaveTextContent("Everything by number"));
    const posted = calls.find((call) => call.key === `POST ${VIEWS}`);
    const body = JSON.parse(posted?.body ?? "{}") as { label: string; scope: string; definition: { sort: unknown[] } };
    expect(body.label).toBe("Everything by number");
    expect(body.scope).toBe("PERSONAL");
    expect(body.definition.sort).toEqual([{ field: "number", descending: true }]);
  });

  it("shows the API's words when a view cannot be saved", async () => {
    setup({ extra: { [`POST ${VIEWS}`]: () => refusal("CONFLICT", "A view with this name exists.", 409) } });
    render(<TemplatesPage />);
    await screen.findByText("Fire exit check");

    fireEvent.click(screen.getByTestId("view-menu"));
    fireEvent.click(screen.getByRole("menuitem", { name: "Save view…" }));
    const dialog = await screen.findByRole("dialog", { name: "Save this view" });
    fireEvent.change(within(dialog).getByLabelText("Name"), { target: { value: "Same" } });
    fireEvent.click(within(dialog).getByRole("button", { name: "Save view" }));

    expect(await within(dialog).findByText("A view with this name exists.")).toBeInTheDocument();
  });

  it("removes a view of the person's own, after asking, and goes back to all templates", async () => {
    const { calls } = setup({ extra: { [`DELETE ${VIEWS}/v-own`]: () => new Response(null, { status: 204 }) } });
    render(<TemplatesPage />);
    await screen.findByText("Fire exit check");
    fireEvent.click(screen.getByTestId("view-menu"));
    fireEvent.click(screen.getByRole("menuitemradio", { name: "My drafts" }));

    fireEvent.click(screen.getByTestId("view-menu"));
    fireEvent.click(screen.getByRole("menuitem", { name: "Remove this view…" }));
    const dialog = await screen.findByRole("dialog", { name: "Remove this view" });
    fireEvent.click(within(dialog).getByRole("button", { name: "Remove view" }));

    await waitFor(() => expect(screen.getByTestId("view-menu")).toHaveTextContent("All templates"));
    expect(calls.some((call) => call.key === `DELETE ${VIEWS}/v-own`)).toBe(true);
  });
});

describe("select fields to display", () => {
  it("moves a field between the two boxes, orders the selected ones and changes the columns", async () => {
    setup();
    render(<TemplatesPage />);
    await screen.findByText("Fire exit check");

    fireEvent.click(screen.getByRole("button", { name: "Templates settings" }));
    fireEvent.click(screen.getByRole("menuitem", { name: "Select fields to display" }));
    const dialog = await screen.findByRole("dialog", { name: "Select fields to display" });
    const selected = within(dialog).getByLabelText("Selected fields") as HTMLSelectElement;
    const available = within(dialog).getByLabelText("Available fields") as HTMLSelectElement;
    expect(Array.from(selected.options).map((option) => option.text)).toEqual(["Number", "Category", "Sub-category"]);
    expect(available.options).toHaveLength(0);

    fireEvent.change(selected, { target: { value: "category" } });
    fireEvent.click(within(dialog).getByRole("button", { name: "‹ Remove" }));
    expect(Array.from(available.options).map((option) => option.text)).toEqual(["Category"]);
    fireEvent.change(within(dialog).getByLabelText("Selected fields"), { target: { value: "subCategory" } });
    fireEvent.click(within(dialog).getByRole("button", { name: "Move up" }));
    fireEvent.click(within(dialog).getByRole("button", { name: "Apply" }));

    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
    expect(screen.queryByRole("columnheader", { name: /^Category/ })).toBeNull();
    const headers = screen.getAllByRole("columnheader").map((header) => header.textContent);
    expect(headers.indexOf("Sub-category")).toBeLessThan(headers.indexOf("Number"));
  });

  it("moves a field with Enter from the keyboard", async () => {
    setup();
    render(<TemplatesPage />);
    await screen.findByText("Fire exit check");
    fireEvent.click(screen.getByRole("button", { name: "Templates settings" }));
    fireEvent.click(screen.getByRole("menuitem", { name: "Select fields to display" }));
    const dialog = await screen.findByRole("dialog", { name: "Select fields to display" });
    const selected = within(dialog).getByLabelText("Selected fields") as HTMLSelectElement;

    fireEvent.change(selected, { target: { value: "number" } });
    fireEvent.keyDown(selected, { key: "Enter" });

    expect(Array.from((within(dialog).getByLabelText("Available fields") as HTMLSelectElement).options).map((o) => o.text)).toEqual([
      "Number",
    ]);
  });
});

describe("filter", () => {
  it("builds conditions from the comparisons the server lists and asks the server with them", async () => {
    const { queries } = setup();
    render(<TemplatesPage />);
    await screen.findByText("Fire exit check");

    fireEvent.click(screen.getByRole("button", { name: "Templates settings" }));
    fireEvent.click(screen.getByRole("menuitem", { name: "Filter" }));
    const dialog = await screen.findByRole("dialog", { name: "Filter" });
    fireEvent.click(within(dialog).getByRole("button", { name: "Add condition" }));
    const comparison = within(dialog).getByLabelText("Comparison") as HTMLSelectElement;
    expect(Array.from(comparison.options).map((option) => option.value)).toEqual(["EQ", "CONTAINS"]);
    fireEvent.change(within(dialog).getByLabelText("Field"), { target: { value: "number" } });
    fireEvent.change(within(dialog).getByLabelText("Comparison"), { target: { value: "CONTAINS" } });
    fireEvent.change(within(dialog).getByLabelText("Value"), { target: { value: "WI/" } });
    fireEvent.click(within(dialog).getByRole("button", { name: "Apply filters" }));

    await waitFor(() =>
      expect(lastQuery(queries)?.filter).toEqual({ op: "CONTAINS", field: "number", values: ["WI/"] }),
    );
    expect(screen.getByTestId("view-menu")).toHaveTextContent("changed");
  });

  it("joins several conditions with and", async () => {
    const { queries } = setup();
    render(<TemplatesPage />);
    await screen.findByText("Fire exit check");
    fireEvent.click(screen.getByRole("button", { name: "Templates settings" }));
    fireEvent.click(screen.getByRole("menuitem", { name: "Filter" }));
    const dialog = await screen.findByRole("dialog", { name: "Filter" });

    fireEvent.click(within(dialog).getByRole("button", { name: "Add condition" }));
    fireEvent.change(within(dialog).getByLabelText("Value"), { target: { value: "one" } });
    fireEvent.click(within(dialog).getByRole("button", { name: "Add condition" }));
    fireEvent.change(within(dialog).getAllByLabelText("Value")[1] as HTMLElement, { target: { value: "two" } });
    fireEvent.click(within(dialog).getByRole("button", { name: "Apply filters" }));

    await waitFor(() => expect((lastQuery(queries)?.filter as { op: string } | undefined)?.op).toBe("AND"));
  });
});

describe("search and order on the server", () => {
  it("sends the search text after a pause and orders by the first click on a header, high to low", async () => {
    const { queries } = setup();
    render(<TemplatesPage />);
    await screen.findByText("Fire exit check");

    fireEvent.change(screen.getByRole("searchbox"), { target: { value: "fire" } });
    await waitFor(() => expect(lastQuery(queries)?.search).toBe("fire"));
    fireEvent.click(screen.getByRole("button", { name: /^Number/ }));

    await waitFor(() => expect(lastQuery(queries)?.sort).toEqual([{ field: "number", descending: true }]));
  });

  it("does not make the derived columns clickable: only fields can be ordered by the server", async () => {
    setup();
    render(<TemplatesPage />);
    await screen.findByText("Fire exit check");

    expect(screen.queryByRole("button", { name: /^Status/ })).toBeNull();
    expect(screen.queryByRole("button", { name: /^Versions/ })).toBeNull();
  });
});
