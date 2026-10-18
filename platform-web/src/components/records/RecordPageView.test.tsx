import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NO_ACTIONS as NO_APPROVAL_ACTIONS } from "@/test/approvals";
import { fakeApi, json, refusal, type Call, type Handler } from "@/test/fakeApi";
import { ShellProviders } from "@/test/shellApi";
import { DEAL_FORM, deal, field, page } from "@/test/records";
import type { ObjectForm, RecordPageDescriptor } from "@/lib/records/types";
import { RecordPageView } from "./RecordPageView";

const push = vi.fn();
vi.mock("next/navigation", () => ({
  useRouter: () => ({ push, replace: vi.fn() }),
  usePathname: () => "/",
}));
vi.mock("next/link", () => ({
  default: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => (
    <a href={href} {...rest}>
      {children}
    </a>
  ),
}));

const PAGE = "GET /api/v1/objects/Deal__c/record-page";
const FORM = "GET /api/v1/objects/Deal__c/form";
const RECORD = "GET /api/v1/objects/Deal__c/records/r1";
const PATCH = "PATCH /api/v1/objects/Deal__c/records/r1";
const DELETE = "DELETE /api/v1/objects/Deal__c/records/r1";
const VALIDATE = "POST /api/v1/objects/Deal__c/records/validate";
const LABELS = "POST /api/v1/objects/Deal__c/lookups/ownerId/labels";
const FILES = "GET /api/v1/objects/Deal__c/records/r1/files";
const CONTACT_FORM = "GET /api/v1/objects/Contact__c/form";
const CONTACT_QUERY = "POST /api/v1/objects/Contact__c/records/query";
const APPROVAL = "GET /api/v1/objects/Deal__c/records/r1/approval";

const DETAILS = { kind: "DETAILS", label: "Details", columns: [] } as const;
const RELATED = {
  kind: "RELATED_LIST",
  label: "Contacts",
  object: "Contact__c",
  field: "deal__c",
  columns: ["name__c"],
  limit: 2,
} as const;

/** A resolved page as the API describes it: a header, two tabs and every action. */
function descriptor(extra: Partial<RecordPageDescriptor> = {}): RecordPageDescriptor {
  return {
    layout: "Deal_layout__c",
    header: { titleField: "name__c", highlightFields: ["amount__c", "stage__c"] },
    above: [],
    tabs: [
      { label: "Content", blocks: [{ ...DETAILS, columns: [] }] },
      { label: "Related Records", blocks: [{ ...RELATED, columns: [...RELATED.columns] }] },
    ],
    below: [],
    sidebar: [],
    actions: [
      { action: "EDIT", label: "Edit" },
      { action: "DELETE", label: "Delete" },
      { action: "CLONE", label: "Clone" },
      { action: "SHARE", label: "Share" },
    ],
    ...extra,
  };
}

const CONTACTS: ObjectForm = {
  ...DEAL_FORM,
  objectApiName: "Contact__c",
  label: "Contact",
  pluralLabel: "Contacts",
  titleField: "name__c",
  fields: [field("name__c", "TEXT", { label: "Name", required: true })],
};

beforeEach(() => {
  push.mockClear();
  document.cookie = "XSRF-TOKEN=test-token; path=/";
});

afterEach(() => {
  vi.unstubAllGlobals();
  document.cookie = "XSRF-TOKEN=; path=/; max-age=0";
});

function api(more: Record<string, Handler> = {}, form: ObjectForm = DEAL_FORM, described = descriptor()): Call[] {
  return fakeApi({
    [PAGE]: () => json({ data: described }),
    [FORM]: () => json({ data: form }),
    [RECORD]: () => json({ data: deal("r1", { name__c: "Acme" }, { version: 7 }) }),
    [VALIDATE]: () => json({ data: { calculated: {}, cleared: [] } }),
    [LABELS]: () => json({ data: { labels: {} } }),
    [FILES]: () => json({ data: [] }),
    [CONTACT_FORM]: () => json({ data: CONTACTS }),
    [APPROVAL]: () =>
      json({ data: { available: false, locked: false, processes: [], actions: NO_APPROVAL_ACTIONS } }),
    ...more,
  });
}

function renderPage(options: { withNew?: boolean } = {}) {
  return render(
    <ShellProviders>
      <RecordPageView
        objectApiName="Deal__c"
        recordId="r1"
        listHref="/records/Deal__c"
        recordHref={(object, id) => `/records/${object}/${id}`}
        newHref={options.withNew === false ? undefined : "/records/Deal__c/new"}
      />
    </ShellProviders>,
  );
}

describe("the record page: reading", () => {
  it("shows the record as text first, under its name, with the fields the page puts in the header", async () => {
    api();
    renderPage();

    expect(await screen.findByRole("heading", { level: 1, name: "Acme" })).toBeInTheDocument();
    expect(await screen.findByTestId("record-details")).toBeInTheDocument();
    expect(screen.queryByTestId("record-form")).toBeNull();
    expect(screen.getByTestId("value-name__c")).toHaveTextContent("Acme");
    // The header card and the layout both show the amount.
    expect(screen.getAllByText("Amount")).toHaveLength(2);
  });

  it("asks for the form with the layout the page names, and only after it knows it", async () => {
    const urls: string[] = [];
    api();
    const original = globalThis.fetch;
    vi.stubGlobal("fetch", async (request: Request) => {
      if (new URL(request.url).pathname === "/api/v1/objects/Deal__c/form") {
        urls.push(new URL(request.url).search);
      }
      return original(request);
    });
    renderPage();

    await screen.findByTestId("record-details");

    expect(urls).toEqual(["?recordId=r1&layout=Deal_layout__c"]);
  });

  it("offers the actions the server says the member may use, in one group", async () => {
    api();
    renderPage();
    await screen.findByTestId("record-details");

    const group = screen.getByTestId("record-actions");

    expect(within(group).getByTestId("page-edit")).toHaveTextContent("Edit");
    expect(within(group).getByTestId("page-clone")).toHaveAttribute("href", "/records/Deal__c/new?cloneFrom=r1");
    expect(within(group).getByTestId("page-share")).toBeInTheDocument();
    expect(within(group).getByTestId("page-delete")).toBeInTheDocument();
  });

  it("leaves out an action the page does not offer and one the member's object permission does not allow", async () => {
    api({}, { ...DEAL_FORM, actions: { create: false, update: false, delete: true } });
    renderPage();
    await screen.findByTestId("record-details");

    expect(screen.queryByTestId("page-edit")).toBeNull();
    expect(screen.queryByTestId("page-clone")).toBeNull();
    expect(screen.queryByTestId("page-share")).toBeNull();
    expect(screen.getByTestId("page-delete")).toBeInTheDocument();
  });

  it("offers no Clone where the screen has no create address", async () => {
    api();
    renderPage({ withNew: false });
    await screen.findByTestId("record-details");

    expect(screen.queryByTestId("page-clone")).toBeNull();
  });

  it("says the page is not available, in the same words, when the record cannot be read", async () => {
    api({ [RECORD]: () => refusal("NOT_FOUND", "This record does not exist.", 404) });
    renderPage();

    expect(await screen.findByTestId("record-unavailable")).toHaveTextContent("This page is not available.");
  });
});

describe("the record page: editing", () => {
  it("turns the same layout into the form on Edit, with Save and Cancel in the header", async () => {
    api();
    renderPage();
    await screen.findByTestId("record-details");

    fireEvent.click(screen.getByTestId("page-edit"));

    expect(await screen.findByTestId("record-form")).toBeInTheDocument();
    expect(screen.queryByTestId("record-details")).toBeNull();
    expect(screen.getByTestId("page-save")).toBeInTheDocument();
    expect(screen.getByTestId("page-cancel")).toBeInTheDocument();
    expect(screen.queryByTestId("page-edit")).toBeNull();
    // The form's own row of buttons is not drawn twice.
    expect(screen.queryByTestId("save-record")).toBeNull();
  });

  it("saves only what changed, with the version that was read, and goes back to reading the saved record", async () => {
    const calls = api({
      [PATCH]: () => json({ data: deal("r1", { name__c: "Acme Ltd" }, { version: 8 }) }),
    });
    renderPage();
    await screen.findByTestId("record-details");
    fireEvent.click(screen.getByTestId("page-edit"));

    fireEvent.change(await screen.findByLabelText(/Name/), { target: { value: "Acme Ltd" } });
    fireEvent.click(screen.getByTestId("page-save"));

    expect(await screen.findByRole("heading", { level: 1, name: "Acme Ltd" })).toBeInTheDocument();
    expect(await screen.findByTestId("record-details")).toBeInTheDocument();
    expect(calls.find((call) => call.key === PATCH)?.body).toBe('{"version":7,"fields":{"name__c":"Acme Ltd"},"clear":[]}');
    expect(push).not.toHaveBeenCalled();
  });

  it("goes back to reading on Cancel and drops what was typed", async () => {
    const calls = api();
    renderPage();
    await screen.findByTestId("record-details");
    fireEvent.click(screen.getByTestId("page-edit"));
    fireEvent.change(await screen.findByLabelText(/Name/), { target: { value: "Nope" } });

    fireEvent.click(screen.getByTestId("page-cancel"));

    expect(await screen.findByTestId("record-details")).toBeInTheDocument();
    expect(screen.getByTestId("value-name__c")).toHaveTextContent("Acme");
    expect(calls.some((call) => call.key === PATCH)).toBe(false);
  });
});

describe("the record page: delete", () => {
  it("asks first, then deletes and goes back to the list", async () => {
    const calls = api({ [DELETE]: () => new Response(null, { status: 204 }) });
    renderPage();
    await screen.findByTestId("record-details");

    fireEvent.click(screen.getByTestId("page-delete"));
    expect(calls.some((call) => call.key === DELETE)).toBe(false);
    fireEvent.click(screen.getByRole("button", { name: "Yes, delete" }));

    await waitFor(() => expect(push).toHaveBeenCalledWith("/records/Deal__c"));
    expect(calls.some((call) => call.key === DELETE)).toBe(true);
  });

  it("keeps the record when the person changes their mind", async () => {
    const calls = api();
    renderPage();
    await screen.findByTestId("record-details");

    fireEvent.click(screen.getByTestId("page-delete"));
    fireEvent.click(screen.getByRole("button", { name: "Keep it" }));

    expect(screen.getByTestId("page-delete")).toBeInTheDocument();
    expect(calls.some((call) => call.key === DELETE)).toBe(false);
  });

  it("shows the server's words when the delete is refused", async () => {
    api({ [DELETE]: () => refusal("FORBIDDEN", "You may not delete this record.", 403) });
    renderPage();
    await screen.findByTestId("record-details");

    fireEvent.click(screen.getByTestId("page-delete"));
    fireEvent.click(screen.getByRole("button", { name: "Yes, delete" }));

    expect(await screen.findByTestId("page-failure")).toHaveTextContent("You may not delete this record.");
    expect(push).not.toHaveBeenCalled();
  });
});

describe("the record page: tabs and regions", () => {
  it("shows the first tab, and a related list in the next one with only as many rows as the page says", async () => {
    const bodies: string[] = [];
    api({
      [CONTACT_QUERY]: async (request) => {
        bodies.push(await request.text());
        return json(page([deal("c1", { name__c: "Ann" }), deal("c2", { name__c: "Bob" })], "next"));
      },
    });
    renderPage();
    await screen.findByTestId("record-details");
    expect(screen.queryByTestId("related-list-table")).toBeNull();

    fireEvent.click(screen.getByRole("tab", { name: "Related Records" }));

    const table = await screen.findByTestId("related-list-table");
    expect(within(table).getByText("Ann")).toBeInTheDocument();
    expect(within(table).getByText("Bob")).toBeInTheDocument();
    expect(JSON.parse(bodies[0] as string)).toEqual({
      filter: { op: "EQ", field: "deal__c", values: ["r1"] },
      fields: ["name__c"],
      limit: 2,
    });
  });

  it("links to the whole list, narrowed to this record, only when there are more rows than shown", async () => {
    api({ [CONTACT_QUERY]: () => json(page([deal("c1", { name__c: "Ann" })], "next")) });
    renderPage();
    await screen.findByTestId("record-details");
    fireEvent.click(screen.getByRole("tab", { name: "Related Records" }));

    expect(await screen.findByTestId("related-view-all")).toHaveAttribute("href", "/records/Contact__c?where=deal__c:r1");
  });

  it("shows no 'view all' when everything fits", async () => {
    api({ [CONTACT_QUERY]: () => json(page([deal("c1", { name__c: "Ann" })])) });
    renderPage();
    await screen.findByTestId("record-details");
    fireEvent.click(screen.getByRole("tab", { name: "Related Records" }));

    await screen.findByTestId("related-list-table");
    expect(screen.queryByTestId("related-view-all")).toBeNull();
  });

  it("says there are no related records when there are none", async () => {
    api({ [CONTACT_QUERY]: () => json(page([])) });
    renderPage();
    await screen.findByTestId("record-details");
    fireEvent.click(screen.getByRole("tab", { name: "Related Records" }));

    expect(await screen.findByText("No related records.")).toBeInTheDocument();
  });

  it("draws the blocks written outside the tabs: before them, after them and in the side region", async () => {
    api(
      { [CONTACT_QUERY]: () => json(page([deal("c1", { name__c: "Ann" })])) },
      DEAL_FORM,
      descriptor({
        above: [{ kind: "RELATED_LIST", label: "Above list", object: "Contact__c", field: "deal__c", columns: ["name__c"], limit: 1 }],
        tabs: [{ label: "Content", blocks: [{ ...DETAILS, columns: [] }] }],
        below: [{ kind: "RELATED_LIST", label: "Below list", object: "Contact__c", field: "deal__c", columns: ["name__c"], limit: 1 }],
        sidebar: [{ kind: "RELATED_LIST", label: "Side list", object: "Contact__c", field: "deal__c", columns: ["name__c"], limit: 1 }],
      }),
    );
    renderPage();

    expect(await screen.findByRole("heading", { name: "Above list" })).toBeInTheDocument();
    expect(await screen.findByRole("heading", { name: "Below list" })).toBeInTheDocument();
    expect(within(await screen.findByRole("complementary", { name: "Side" })).getByRole("heading", { name: "Side list" })).toBeInTheDocument();
    // One tab needs no strip.
    expect(screen.queryByRole("tablist")).toBeNull();
  });

  it("still shows the record when every block of the page was left out for this member", async () => {
    api({}, DEAL_FORM, descriptor({ tabs: [] }));
    renderPage();

    expect(await screen.findByTestId("record-details")).toBeInTheDocument();
  });

  it("keeps a half-filled form when another tab is looked at and back", async () => {
    api({ [CONTACT_QUERY]: () => json(page([])) });
    renderPage();
    await screen.findByTestId("record-details");
    fireEvent.click(screen.getByTestId("page-edit"));
    fireEvent.change(await screen.findByLabelText(/Name/), { target: { value: "Typed" } });

    fireEvent.click(screen.getByRole("tab", { name: "Related Records" }));
    fireEvent.click(screen.getByRole("tab", { name: "Content" }));

    expect(screen.getByLabelText(/Name/)).toHaveValue("Typed");
  });
});
