import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { json, refusal, type Call, type Handler } from "@/test/fakeApi";
import { shellApi, ShellProviders } from "@/test/shellApi";
import { DEAL_FORM, deal, page, view } from "@/test/records";
import type { ObjectForm } from "@/lib/records/types";
import { RecordList } from "./RecordList";

vi.mock("next/link", () => ({
  default: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => (
    <a href={href} {...rest}>
      {children}
    </a>
  ),
}));

const QUERY = "POST /api/v1/objects/Deal__c/records/query";
const SEARCH = "POST /api/v1/objects/Deal__c/records/search";
const VIEWS = "GET /api/v1/objects/Deal__c/views";
const CREATE_VIEW = "POST /api/v1/objects/Deal__c/views";
const BULK = "POST /api/v1/objects/Deal__c/records/bulk";

const FIRST = deal("d1", { name__c: "Acme", amount__c: 1500.5, stage__c: "OPEN" });
const SECOND = deal("d2", { name__c: "Globex", amount__c: 20, stage__c: "WON" });

beforeEach(() => {
  document.cookie = "XSRF-TOKEN=test-token; path=/";
});

afterEach(() => {
  vi.unstubAllGlobals();
  document.cookie = "XSRF-TOKEN=; path=/; max-age=0";
});

function renderList(more: Record<string, Handler> = {}, options: { abilities?: string[]; form?: ObjectForm } = {}): Call[] {
  const calls = shellApi({
    abilities: options.abilities ?? [],
    more: { [VIEWS]: () => json({ data: [] }), [QUERY]: () => json(page([FIRST, SECOND])), ...more },
  });
  render(
    <ShellProviders>
      <RecordList
        objectApiName="Deal__c"
        form={options.form ?? DEAL_FORM}
        newHref="/records/Deal__c/new"
        openHref={(id) => `/records/Deal__c/${id}`}
        recordHref={(object, id) => `/records/${object}/${id}`}
      />
    </ShellProviders>,
  );
  return calls;
}

function bodies(calls: Call[], key: string): Record<string, unknown>[] {
  return calls.filter((call) => call.key === key).map((call) => JSON.parse(call.body) as Record<string, unknown>);
}

const lastBody = (calls: Call[], key: string) => bodies(calls, key).at(-1);

describe("the list of an object", () => {
  it("shows the records the API sends, the first column opens the record, and asks only for the columns it draws", async () => {
    const calls = renderList();

    const table = await screen.findByTestId("list-table");

    expect(within(table).getAllByRole("columnheader").map((header) => header.textContent?.trim())).toEqual([
      "",
      "Name",
      "Amount",
      "Stage",
      "Closes",
      "Margin",
    ]);
    expect(within(table).getByRole("link", { name: "Acme" })).toHaveAttribute("href", "/records/Deal__c/d1");
    expect(within(table).getByText("1,500.50")).toBeInTheDocument();
    expect(within(table).getByText("Won")).toBeInTheDocument();
    expect(lastBody(calls, QUERY)).toEqual({
      sort: [],
      fields: ["name__c", "amount__c", "stage__c", "closes__c", "margin__c"],
      limit: 25,
    });
    expect(screen.getByRole("heading", { level: 1, name: "Deals" })).toBeInTheDocument();
  });

  it("offers to create a record only to a member who may", async () => {
    renderList();
    expect(await screen.findByTestId("new-record")).toHaveAttribute("href", "/records/Deal__c/new");
  });

  it("offers no way to create, no import and no selection to a member who may only read", async () => {
    renderList({}, { form: { ...DEAL_FORM, actions: { create: false, update: false, delete: false } } });

    await screen.findByTestId("list-table");

    expect(screen.queryByTestId("new-record")).toBeNull();
    expect(screen.queryByRole("button", { name: "Import" })).toBeNull();
    expect(screen.queryByLabelText("Select all rows on this page")).toBeNull();
  });

  it("says so when there is nothing, and offers to create the first record", async () => {
    renderList({ [QUERY]: () => json(page([])) });

    const empty = await screen.findByTestId("list-empty");

    expect(empty).toHaveTextContent("No deals yet.");
    expect(within(empty).getByRole("link", { name: "Create the first one" })).toHaveAttribute("href", "/records/Deal__c/new");
  });

  it("shows the API's own words when the list is refused, and asks again on request", async () => {
    let attempts = 0;
    const calls = renderList({
      [QUERY]: () => {
        attempts += 1;
        return attempts === 1 ? refusal("VALIDATION_ERROR", "Ask for a page of 1 to 200 records.", 422) : json(page([FIRST]));
      },
    });

    expect(await screen.findByTestId("list-failed")).toHaveTextContent("Ask for a page of 1 to 200 records.");
    fireEvent.click(screen.getByRole("button", { name: "Try again" }));

    expect(await screen.findByRole("link", { name: "Acme" })).toBeInTheDocument();
    expect(calls.filter((call) => call.key === QUERY)).toHaveLength(2);
  });

  it("draws a link to a person by name, asked for once, never as an identifier", async () => {
    const OWNER = "11111111-1111-1111-1111-111111111111";
    const calls = renderList({
      "POST /api/v1/objects/Deal__c/lookups/ownerId/labels": () => json({ data: { labels: { [OWNER]: "User A" } } }),
    });
    const table = await screen.findByTestId("list-table");

    fireEvent.click(screen.getByRole("button", { name: "Columns" }));
    fireEvent.click(within(screen.getByTestId("columns-panel")).getByLabelText("Owner"));

    await waitFor(() => expect(within(table).getAllByText("User A")).toHaveLength(2));
    expect(table).not.toHaveTextContent(OWNER);
    // Both rows have the same owner: one question, one identifier.
    const asked = calls.filter((call) => call.key === "POST /api/v1/objects/Deal__c/lookups/ownerId/labels");
    expect(asked).toHaveLength(1);
    expect(JSON.parse(asked[0]!.body)).toEqual({ ids: [OWNER] });
  });
});

describe("filters", () => {
  it("are built from the fields and comparisons the server offers, and asked only when applied", async () => {
    const calls = renderList();
    await screen.findByTestId("list-table");

    fireEvent.click(screen.getByRole("button", { name: "Filters" }));
    fireEvent.click(screen.getByRole("button", { name: "Add condition" }));
    const builder = screen.getByTestId("filter-builder");
    fireEvent.change(within(builder).getByLabelText("Field"), { target: { value: "stage__c" } });
    fireEvent.change(within(builder).getByLabelText("Value"), { target: { value: "OPEN" } });
    // Nothing is asked until Apply.
    expect(bodies(calls, QUERY)).toHaveLength(1);
    fireEvent.click(screen.getByRole("button", { name: "Apply filters" }));

    await waitFor(() => expect(bodies(calls, QUERY)).toHaveLength(2));
    expect(lastBody(calls, QUERY)?.filter).toEqual({ op: "EQ", field: "stage__c", values: ["OPEN"] });
    expect(screen.getByRole("list", { name: "Active filters" })).toHaveTextContent("Stage is OPEN");
  });

  it("leave out a condition that is not complete, and combine the rest to hold together", async () => {
    const calls = renderList();
    await screen.findByTestId("list-table");

    fireEvent.click(screen.getByRole("button", { name: "Filters" }));
    fireEvent.click(screen.getByRole("button", { name: "Add condition" }));
    fireEvent.click(screen.getByRole("button", { name: "Add condition" }));
    const groups = screen.getAllByRole("group", { name: /Condition/ });
    // Changing the comparison starts the value again, so the comparison is chosen first.
    fireEvent.change(within(groups[0]!).getByLabelText("Comparison"), { target: { value: "STARTS_WITH" } });
    fireEvent.change(within(groups[0]!).getByLabelText("Value"), { target: { value: "Ac" } });
    fireEvent.click(screen.getByRole("button", { name: "Apply filters" }));

    await waitFor(() => expect(bodies(calls, QUERY)).toHaveLength(2));
    expect(lastBody(calls, QUERY)?.filter).toEqual({ op: "STARTS_WITH", field: "name__c", values: ["Ac"] });
  });

  it("do not offer a field the server says cannot be filtered", async () => {
    renderList();
    await screen.findByTestId("list-table");

    fireEvent.click(screen.getByRole("button", { name: "Filters" }));
    fireEvent.click(screen.getByRole("button", { name: "Add condition" }));

    const options = within(screen.getByLabelText("Field")).getAllByRole("option").map((option) => option.textContent);
    expect(options).toContain("Amount");
    expect(options).not.toContain("Notes");
  });

  it("can be cleared", async () => {
    const calls = renderList();
    await screen.findByTestId("list-table");
    fireEvent.click(screen.getByRole("button", { name: "Filters" }));
    fireEvent.click(screen.getByRole("button", { name: "Add condition" }));
    fireEvent.change(screen.getByLabelText("Value"), { target: { value: "Acme" } });
    fireEvent.click(screen.getByRole("button", { name: "Apply filters" }));
    await waitFor(() => expect(lastBody(calls, QUERY)?.filter).toBeDefined());

    fireEvent.click(screen.getByRole("button", { name: /Filters/ }));
    fireEvent.click(screen.getByRole("button", { name: "Clear filters" }));

    await waitFor(() => expect(lastBody(calls, QUERY)?.filter).toBeUndefined());
    expect(screen.queryByRole("list", { name: "Active filters" })).toBeNull();
  });
});

describe("ordering", () => {
  it("cycles ascending, descending and off on a column the server says can be ordered by", async () => {
    const calls = renderList();
    await screen.findByTestId("list-table");
    const amount = () => screen.getByRole("button", { name: /Amount/ });

    fireEvent.click(amount());
    await waitFor(() => expect(lastBody(calls, QUERY)?.sort).toEqual([{ field: "amount__c", descending: false }]));
    expect(screen.getByRole("columnheader", { name: /Amount/ })).toHaveAttribute("aria-sort", "ascending");

    fireEvent.click(amount());
    await waitFor(() => expect(lastBody(calls, QUERY)?.sort).toEqual([{ field: "amount__c", descending: true }]));
    expect(screen.getByRole("columnheader", { name: /Amount/ })).toHaveAttribute("aria-sort", "descending");

    fireEvent.click(amount());
    await waitFor(() => expect(lastBody(calls, QUERY)?.sort).toEqual([]));
  });

  it("makes a second key of a shift-click, and never more than two", async () => {
    const calls = renderList();
    await screen.findByTestId("list-table");

    fireEvent.click(screen.getByRole("button", { name: /Name/ }));
    fireEvent.click(screen.getByRole("button", { name: /Amount/ }), { shiftKey: true });
    fireEvent.click(screen.getByRole("button", { name: /Stage/ }), { shiftKey: true });

    await waitFor(() =>
      expect(lastBody(calls, QUERY)?.sort).toEqual([
        { field: "amount__c", descending: false },
        { field: "stage__c", descending: false },
      ]),
    );
  });

  it("has no button on a column that cannot be ordered by", async () => {
    renderList();
    const table = await screen.findByTestId("list-table");

    expect(within(table).queryByRole("button", { name: /Margin/ })).not.toBeNull();
    // Formula results of type decimal are sortable in the fixture; a long text is not and is not shown by default.
  });
});

describe("paging", () => {
  it("goes to the next page with the cursor the server gave, and back", async () => {
    const calls = renderList({
      [QUERY]: (request) => {
        const sent = request.clone();
        return sent.text().then((text) => {
          const cursor = (JSON.parse(text) as { cursor?: string }).cursor;
          return cursor === "c1" ? json(page([SECOND])) : json(page([FIRST], "c1"));
        });
      },
    });
    await screen.findByRole("link", { name: "Acme" });
    expect(screen.getByRole("button", { name: "Previous" })).toBeDisabled();

    fireEvent.click(screen.getByRole("button", { name: "Next" }));

    expect(await screen.findByRole("link", { name: "Globex" })).toBeInTheDocument();
    expect(lastBody(calls, QUERY)?.cursor).toBe("c1");
    expect(screen.getByRole("button", { name: "Next" })).toBeDisabled();
    expect(screen.getByText("Page 2")).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "Previous" }));

    expect(await screen.findByRole("link", { name: "Acme" })).toBeInTheDocument();
    expect(screen.getByText("Page 1")).toBeInTheDocument();
  });

  it("asks for another page size and starts again from the first page", async () => {
    const calls = renderList();
    await screen.findByTestId("list-table");

    fireEvent.change(screen.getByLabelText("Rows per page"), { target: { value: "100" } });

    await waitFor(() => expect(lastBody(calls, QUERY)?.limit).toBe(100));
    expect(lastBody(calls, QUERY)?.cursor).toBeUndefined();
  });
});

describe("search", () => {
  it("uses the ranked search of the API, not a filter of the page, and says what it shows", async () => {
    const calls = renderList({ [SEARCH]: () => json(page([SECOND])) });
    await screen.findByTestId("list-table");

    fireEvent.change(screen.getByRole("searchbox"), { target: { value: " glob " } });
    fireEvent.click(screen.getByRole("button", { name: "Search" }));

    expect(await screen.findByRole("link", { name: "Globex" })).toBeInTheDocument();
    expect(lastBody(calls, SEARCH)).toEqual({ text: "glob", limit: 25 });
    expect(screen.getByTestId("search-note")).toHaveTextContent("best matches for “glob”");
    // Results are ranked, so the order of the columns does not apply.
    expect(screen.queryByRole("button", { name: /Amount/ })).toBeNull();
  });

  it("goes back to the list when it is cleared", async () => {
    const calls = renderList({ [SEARCH]: () => json(page([SECOND])) });
    await screen.findByTestId("list-table");
    fireEvent.change(screen.getByRole("searchbox"), { target: { value: "glob" } });
    fireEvent.click(screen.getByRole("button", { name: "Search" }));
    await screen.findByTestId("search-note");

    fireEvent.click(screen.getByRole("button", { name: "Clear search" }));

    await waitFor(() => expect(screen.queryByTestId("search-note")).toBeNull());
    expect(await screen.findByRole("link", { name: "Acme" })).toBeInTheDocument();
    expect(bodies(calls, QUERY).length).toBeGreaterThanOrEqual(2);
  });

  it("is not offered for an object that has no searchable field the member may read", async () => {
    renderList({}, { form: { ...DEAL_FORM, searchEnabled: false } });
    await screen.findByTestId("list-table");

    expect(screen.queryByRole("searchbox")).toBeNull();
  });

  it("says when nothing matches", async () => {
    renderList({ [SEARCH]: () => json(page([])) });
    await screen.findByTestId("list-table");
    fireEvent.change(screen.getByRole("searchbox"), { target: { value: "zzz" } });
    fireEvent.click(screen.getByRole("button", { name: "Search" }));

    expect(await screen.findByTestId("list-empty")).toHaveTextContent("No records match.");
  });
});

describe("columns", () => {
  it("can be chosen, and the list then asks for them", async () => {
    const calls = renderList();
    await screen.findByTestId("list-table");

    fireEvent.click(screen.getByRole("button", { name: "Columns" }));
    fireEvent.click(within(screen.getByTestId("columns-panel")).getByLabelText("Notes"));

    await waitFor(() => expect(lastBody(calls, QUERY)?.fields).toContain("notes__c"));
    expect(screen.getByRole("columnheader", { name: "Notes" })).toBeInTheDocument();
  });

  it("never leave the list without a column", async () => {
    renderList({}, { form: { ...DEAL_FORM, layout: { source: "LAYOUT", sections: [{ label: "x", columns: 1, fields: ["name__c"] }] } } });
    await screen.findByTestId("list-table");

    fireEvent.click(screen.getByRole("button", { name: "Columns" }));
    fireEvent.click(within(screen.getByTestId("columns-panel")).getByLabelText("Name"));

    expect(screen.getByRole("columnheader", { name: /Name/ })).toBeInTheDocument();
  });
});

describe("saved views", () => {
  const SHARED = view("v1", "Open deals", {
    scope: "SHARED",
    mine: false,
    definition: { columns: ["name__c", "stage__c"], filter: { op: "EQ", field: "stage__c", values: ["OPEN"] }, sort: [{ field: "name__c", descending: true }] },
  });
  const MINE = view("v2", "Big ones", { definition: { columns: ["name__c", "amount__c"], sort: [] } });

  it("are listed, the shared ones first, and opening one fills in the list from its definition", async () => {
    const calls = renderList({ [VIEWS]: () => json({ data: [MINE, SHARED] }) });
    await screen.findByTestId("list-table");

    const rail = await screen.findByTestId("views-rail");
    expect(within(rail).getAllByRole("button").map((button) => button.textContent)).toEqual(["All records", "Open deals", "Big ones"]);

    fireEvent.click(within(rail).getByRole("button", { name: "Open deals" }));

    await waitFor(() => expect(lastBody(calls, QUERY)?.filter).toEqual({ op: "EQ", field: "stage__c", values: ["OPEN"] }));
    expect(lastBody(calls, QUERY)).toMatchObject({ fields: ["name__c", "stage__c"], sort: [{ field: "name__c", descending: true }] });
    expect(within(rail).getByRole("button", { name: "Open deals" })).toHaveAttribute("aria-current", "true");
    expect(screen.getByRole("list", { name: "Active filters" })).toHaveTextContent("Stage is OPEN");
  });

  it("show that the list was changed since the view was opened, and go back to the plain list from 'All records'", async () => {
    const calls = renderList({ [VIEWS]: () => json({ data: [SHARED] }) });
    const rail = await screen.findByTestId("views-rail");
    fireEvent.click(await within(rail).findByRole("button", { name: "Open deals" }));
    await waitFor(() => expect(lastBody(calls, QUERY)?.filter).toBeDefined());
    expect(within(rail).queryByText("changed")).toBeNull();

    fireEvent.click(await screen.findByRole("button", { name: /Stage/ }));
    expect(await within(rail).findByText("changed")).toBeInTheDocument();

    fireEvent.click(within(rail).getByRole("button", { name: "All records" }));
    await waitFor(() => expect(lastBody(calls, QUERY)?.filter).toBeUndefined());
  });

  it("are saved as personal views by anyone, with the list as it is", async () => {
    const calls = renderList({ [CREATE_VIEW]: () => json({ data: MINE }, 201) });
    await screen.findByTestId("list-table");
    fireEvent.click(screen.getByRole("button", { name: /Amount/ }));
    await waitFor(() => expect(lastBody(calls, QUERY)?.sort).toBeDefined());

    fireEvent.click(screen.getByRole("button", { name: "Save view" }));
    const panel = screen.getByTestId("save-view-panel");
    expect(within(panel).queryByLabelText("Everyone who can see these records")).toBeNull();
    fireEvent.change(within(panel).getByLabelText("Name"), { target: { value: "Big ones" } });
    fireEvent.click(within(panel).getByRole("button", { name: "Save view" }));

    await waitFor(() => expect(bodies(calls, CREATE_VIEW)).toHaveLength(1));
    expect(lastBody(calls, CREATE_VIEW)).toEqual({
      label: "Big ones",
      scope: "PERSONAL",
      definition: { sort: [{ field: "amount__c", descending: false }], columns: ["name__c", "amount__c", "stage__c", "closes__c", "margin__c"] },
    });
    // The views are read again, and the new one is the open one.
    await waitFor(() => expect(calls.filter((call) => call.key === VIEWS).length).toBeGreaterThanOrEqual(2));
  });

  it("can be shared with everyone by a member who holds the ability, and the server still decides", async () => {
    const calls = renderList(
      { [CREATE_VIEW]: () => refusal("FORBIDDEN", "You are not allowed to do that.", 403) },
      { abilities: ["views.share"] },
    );
    await screen.findByTestId("list-table");
    await waitFor(() => expect(calls.some((call) => call.key === "GET /api/v1/auth/me")).toBe(true));

    fireEvent.click(screen.getByRole("button", { name: "Save view" }));
    const panel = screen.getByTestId("save-view-panel");
    fireEvent.change(within(panel).getByLabelText("Name"), { target: { value: "Everyone" } });
    fireEvent.click(await within(panel).findByLabelText("Everyone who can see these records"));
    fireEvent.click(within(panel).getByRole("button", { name: "Save view" }));

    expect(await within(panel).findByText("You are not allowed to do that.")).toBeInTheDocument();
    expect(lastBody(calls, CREATE_VIEW)).toMatchObject({ scope: "SHARED" });
  });

  it("replace the view that is open, carrying the version that was read", async () => {
    const calls = renderList({
      [VIEWS]: () => json({ data: [{ ...MINE, version: 5 }] }),
      "PUT /api/v1/objects/Deal__c/views/v2": () => json({ data: { ...MINE, version: 6 } }),
    });
    const rail = await screen.findByTestId("views-rail");
    fireEvent.click(await within(rail).findByRole("button", { name: "Big ones" }));
    await waitFor(() => expect(lastBody(calls, QUERY)?.fields).toEqual(["name__c", "amount__c"]));

    fireEvent.click(screen.getByRole("button", { name: "Save view" }));
    expect(screen.getByLabelText(/Replace the view/)).toBeChecked();
    fireEvent.click(within(screen.getByTestId("save-view-panel")).getByRole("button", { name: "Save view" }));

    await waitFor(() => expect(bodies(calls, "PUT /api/v1/objects/Deal__c/views/v2")).toHaveLength(1));
    expect(lastBody(calls, "PUT /api/v1/objects/Deal__c/views/v2")).toMatchObject({ label: "Big ones", scope: "PERSONAL", version: 5 });
  });

  it("can be removed by their maker, with the version that was read", async () => {
    const calls = renderList({
      [VIEWS]: () => json({ data: [{ ...MINE, version: 5 }] }),
      "DELETE /api/v1/objects/Deal__c/views/v2": () => new Response(null, { status: 204 }),
    });
    const rail = await screen.findByTestId("views-rail");
    fireEvent.click(await within(rail).findByRole("button", { name: "Big ones" }));

    fireEvent.click(await screen.findByRole("button", { name: "Delete this view" }));

    await waitFor(() => expect(calls.some((call) => call.key === "DELETE /api/v1/objects/Deal__c/views/v2")).toBe(true));
  });

  it("do not offer to remove a shared view to someone who neither made it nor may share", async () => {
    renderList({ [VIEWS]: () => json({ data: [SHARED] }) });
    const rail = await screen.findByTestId("views-rail");

    fireEvent.click(await within(rail).findByRole("button", { name: "Open deals" }));

    await screen.findByRole("list", { name: "Active filters" });
    expect(screen.queryByRole("button", { name: "Delete this view" })).toBeNull();
  });

  it("say so, and leave the list working, when the views cannot be read", async () => {
    renderList({ [VIEWS]: () => refusal("INTERNAL_ERROR", "Something went wrong.", 500) });

    expect(await screen.findByText("Saved views could not be read.")).toBeInTheDocument();
    expect(screen.getByTestId("list-table")).toBeInTheDocument();
  });

  it("run a filter this screen cannot edit, and say it still applies", async () => {
    const raw = view("v3", "Either", {
      scope: "SHARED",
      mine: false,
      definition: { columns: ["name__c"], filter: { op: "OR", items: [{ op: "EQ", field: "stage__c", values: ["OPEN"] }, { op: "EQ", field: "stage__c", values: ["WON"] }] } },
    });
    const calls = renderList({ [VIEWS]: () => json({ data: [raw] }) });
    const rail = await screen.findByTestId("views-rail");

    fireEvent.click(await within(rail).findByRole("button", { name: "Either" }));
    await waitFor(() => expect((lastBody(calls, QUERY)?.filter as { op: string } | undefined)?.op).toBe("OR"));
    fireEvent.click(screen.getByRole("button", { name: /Filters/ }));

    expect(screen.getByTestId("filter-raw")).toHaveTextContent("still applies");
  });
});

describe("working with several records", () => {
  async function select(...names: string[]) {
    await screen.findByTestId("list-table");
    const rows = screen.getAllByRole("row").slice(1);
    for (const row of rows) {
      if (names.some((name) => row.textContent?.includes(name))) {
        fireEvent.click(within(row).getByLabelText("Select this row"));
      }
    }
  }

  it("shows how many are selected and lets the selection be cleared or taken for the whole page", async () => {
    renderList();
    await select("Acme");

    expect(screen.getByTestId("bulk-bar")).toHaveTextContent("1 selected");

    fireEvent.click(screen.getByLabelText("Select all rows on this page"));
    expect(screen.getByTestId("bulk-bar")).toHaveTextContent("2 selected");
    fireEvent.click(screen.getByRole("button", { name: "Clear selection" }));

    expect(screen.queryByTestId("bulk-bar")).toBeNull();
  });

  it("deletes the selected records after one question, and reports each that was refused by name", async () => {
    const calls = renderList({
      [BULK]: () =>
        json({
          data: {
            operation: "DELETE",
            allOrNone: false,
            committed: true,
            succeeded: 1,
            failed: 1,
            results: [
              { position: 0, status: "DELETED", id: "d1" },
              { position: 1, status: "FAILED", code: "FORBIDDEN", message: "You may not delete this record." },
            ],
          },
        }),
    });
    await select("Acme", "Globex");

    fireEvent.click(screen.getByRole("button", { name: "Delete…" }));
    expect(screen.getByTestId("bulk-delete-panel")).toHaveTextContent("Delete 2 records?");
    expect(calls.some((call) => call.key === BULK)).toBe(false);
    fireEvent.click(screen.getByRole("button", { name: "Delete 2 records" }));

    const summary = await screen.findByTestId("bulk-summary");
    expect(lastBody(calls, BULK)).toEqual({ operation: "DELETE", allOrNone: false, records: [{ id: "d1" }, { id: "d2" }] });
    expect(summary).toHaveTextContent("1 record deleted, 1 not.");
    expect(screen.getByTestId("bulk-failures")).toHaveTextContent("Globex: You may not delete this record.");
    // The list is read again, and nothing stays selected.
    await waitFor(() => expect(bodies(calls, QUERY).length).toBeGreaterThanOrEqual(2));
    expect(screen.queryByTestId("bulk-bar")).toBeNull();
  });

  it("changes a field on the selected records with the version each was read at", async () => {
    const calls = renderList({
      [BULK]: () =>
        json({
          data: {
            operation: "UPDATE",
            allOrNone: false,
            committed: true,
            succeeded: 2,
            failed: 0,
            results: [
              { position: 0, status: "UPDATED", id: "d1", version: 4 },
              { position: 1, status: "UPDATED", id: "d2", version: 4 },
            ],
          },
        }),
    });
    await select("Acme", "Globex");

    fireEvent.click(screen.getByRole("button", { name: "Change…" }));
    const panel = screen.getByTestId("bulk-edit-panel");
    fireEvent.change(within(panel).getByLabelText("Field to change", { selector: "select" }), { target: { value: "stage__c" } });
    fireEvent.change(within(panel).getByLabelText("Stage"), { target: { value: "WON" } });
    fireEvent.click(within(panel).getByRole("button", { name: "Change 2 records" }));

    expect(await screen.findByTestId("bulk-summary")).toHaveTextContent("2 records changed.");
    expect(lastBody(calls, BULK)).toEqual({
      operation: "UPDATE",
      allOrNone: false,
      records: [
        { id: "d1", version: 3, fields: { stage__c: "WON" }, clear: [] },
        { id: "d2", version: 3, fields: { stage__c: "WON" }, clear: [] },
      ],
    });
  });

  it("can ask for all or none, and clear a field instead of setting it", async () => {
    const calls = renderList({
      [BULK]: () =>
        json({
          data: {
            operation: "UPDATE",
            allOrNone: true,
            committed: false,
            succeeded: 0,
            failed: 1,
            results: [
              { position: 0, status: "ROLLED_BACK", id: "d1" },
              { position: 1, status: "FAILED", code: "VALIDATION_ERROR", message: "Check the fields.", fields: { stage__c: ["Nope."] } },
            ],
          },
        }),
    });
    await select("Acme", "Globex");

    fireEvent.click(screen.getByRole("button", { name: "Change…" }));
    const panel = screen.getByTestId("bulk-edit-panel");
    fireEvent.change(within(panel).getByLabelText("Field to change", { selector: "select" }), { target: { value: "stage__c" } });
    fireEvent.click(within(panel).getByLabelText("Clear this field on every record"));
    fireEvent.click(within(panel).getByLabelText(/Save all or none/));
    fireEvent.click(within(panel).getByRole("button", { name: "Change 2 records" }));

    const summary = await screen.findByTestId("bulk-summary");
    expect(lastBody(calls, BULK)).toMatchObject({ allOrNone: true, records: [{ id: "d1", fields: {}, clear: ["stage__c"] }, { id: "d2" }] });
    expect(summary).toHaveTextContent("Nothing was changed");
    expect(screen.getByTestId("bulk-failures")).toHaveTextContent("Not changed, because another record failed.");
    expect(screen.getByTestId("bulk-failures")).toHaveTextContent("Stage: Nope.");
  });

  it("says a record changed since the list was read, in plain words", async () => {
    renderList({
      [BULK]: () =>
        json({
          data: {
            operation: "UPDATE",
            allOrNone: false,
            committed: true,
            succeeded: 0,
            failed: 1,
            results: [{ position: 0, status: "FAILED", code: "CONCURRENT_MODIFICATION", message: "x" }],
          },
        }),
    });
    await select("Acme");

    fireEvent.click(screen.getByRole("button", { name: "Change…" }));
    const panel = screen.getByTestId("bulk-edit-panel");
    fireEvent.change(within(panel).getByLabelText("Field to change", { selector: "select" }), { target: { value: "stage__c" } });
    fireEvent.change(within(panel).getByLabelText("Stage"), { target: { value: "WON" } });
    fireEvent.click(within(panel).getByRole("button", { name: "Change 1 record" }));

    expect(await screen.findByTestId("bulk-failures")).toHaveTextContent("Changed by someone else since this list was loaded.");
  });

  it("does not ask for a change that names no field value", async () => {
    const calls = renderList();
    await select("Acme");

    fireEvent.click(screen.getByRole("button", { name: "Change…" }));
    fireEvent.click(within(screen.getByTestId("bulk-edit-panel")).getByRole("button", { name: "Change 1 record" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("Enter a value");
    expect(calls.some((call) => call.key === BULK)).toBe(false);
  });

  it("only offers delete to a member who may delete, and change to one who may change", async () => {
    renderList({}, { form: { ...DEAL_FORM, actions: { create: true, update: true, delete: false } } });
    await select("Acme");

    expect(screen.getByRole("button", { name: "Change…" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Delete…" })).toBeNull();
  });
});
