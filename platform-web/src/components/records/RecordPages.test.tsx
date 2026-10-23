import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { fakeApi, json, refusal, type Call } from "@/test/fakeApi";
import { shellApi, ShellProviders } from "@/test/shellApi";
import { DEAL_FORM, deal, page } from "@/test/records";
import type { ObjectForm } from "@/lib/records/types";
import { ObjectListPage } from "./ObjectListPage";
import { RecordFormPage } from "./RecordFormPage";

const push = vi.fn();
vi.mock("next/navigation", () => ({
  useRouter: () => ({ push, replace: vi.fn() }),
  usePathname: () => "/",
}));
vi.mock("next/link", () => ({
  default: ({ href, children }: { href: string; children: React.ReactNode }) => <a href={href}>{children}</a>,
}));

const FORM = "GET /api/v1/objects/Deal__c/form";
const QUERY = "POST /api/v1/objects/Deal__c/records/query";
const VIEWS = "GET /api/v1/objects/Deal__c/views";
const RECORD = "GET /api/v1/objects/Deal__c/records/r1";
const CREATE = "POST /api/v1/objects/Deal__c/records";
const PATCH = "PATCH /api/v1/objects/Deal__c/records/r1";
const VALIDATE = "POST /api/v1/objects/Deal__c/records/validate";
const LABELS = "POST /api/v1/objects/Deal__c/lookups/ownerId/labels";

beforeEach(() => {
  push.mockClear();
  document.cookie = "XSRF-TOKEN=test-token; path=/";
});

afterEach(() => {
  vi.unstubAllGlobals();
  document.cookie = "XSRF-TOKEN=; path=/; max-age=0";
});

describe("the list page of an object", () => {
  function renderPage() {
    return render(
      <ShellProviders>
        <ObjectListPage
          objectApiName="Deal__c"
          newHref="/records/Deal__c/new"
          openHref={(id) => `/records/Deal__c/${id}`}
          recordHref={(object, id) => `/records/${object}/${id}`}
          backHref="/"
        />
      </ShellProviders>,
    );
  }

  it("waits for the description before it says anything, then shows the list", async () => {
    shellApi({
      more: {
        [FORM]: () => json({ data: DEAL_FORM }),
        [QUERY]: () => json(page([deal("d1", { name__c: "Acme" })])),
        [VIEWS]: () => json({ data: [] }),
      },
    });
    renderPage();

    expect(screen.getByTestId("list-page-loading")).toBeInTheDocument();
    expect(await screen.findByRole("link", { name: "Acme" })).toBeInTheDocument();
  });

  it("says a page is not available for an object that does not exist or is out of reach, in the same words", async () => {
    shellApi({ more: { [FORM]: () => refusal("NOT_FOUND", "This object does not exist.", 404) } });
    renderPage();

    const note = await screen.findByTestId("list-unavailable");

    expect(note).toHaveTextContent("This page is not available.");
    expect(note).toHaveTextContent("It may have been removed, or it may not be part of your access.");
    expect(note).not.toHaveTextContent("This object does not exist.");
    expect(screen.getByRole("link", { name: "Back to the start" })).toHaveAttribute("href", "/");
  });

  it("shows the API's words and a way to try again for any other failure", async () => {
    let attempts = 0;
    shellApi({
      more: {
        [FORM]: () => (++attempts === 1 ? refusal("SERVICE_UNAVAILABLE", "Try again in a moment.", 503) : json({ data: DEAL_FORM })),
        [QUERY]: () => json(page([])),
        [VIEWS]: () => json({ data: [] }),
      },
    });
    renderPage();

    expect(await screen.findByTestId("list-unavailable")).toHaveTextContent("Try again in a moment.");
    fireEvent.click(screen.getByRole("button", { name: "Try again" }));

    expect(await screen.findByTestId("list-empty")).toBeInTheDocument();
  });
});

describe("the page of one record", () => {
  function renderRecord(recordId?: string) {
    return render(
      <RecordFormPage
        objectApiName="Deal__c"
        recordId={recordId}
        listHref="/records/Deal__c"
        recordHref={(object, id) => `/records/${object}/${id}`}
      />,
    );
  }

  function formApi(form: ObjectForm = DEAL_FORM, more: Record<string, (request: Request) => Response | Promise<Response>> = {}): Call[] {
    return fakeApi({
      [FORM]: () => json({ data: form }),
      [VALIDATE]: () => json({ data: { calculated: {}, cleared: [] } }),
      [LABELS]: () => json({ data: { labels: {} } }),
      ...more,
    });
  }

  it("creates a record, and goes back to the list when it is saved", async () => {
    const calls = formApi(DEAL_FORM, { [CREATE]: () => json({ data: deal("new") }, 201) });
    renderRecord();

    expect(await screen.findByRole("heading", { level: 1, name: "New deal" })).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText(/Name/), { target: { value: "Acme" } });
    fireEvent.click(screen.getByTestId("save-record"));

    await waitFor(() => expect(push).toHaveBeenCalledWith("/records/Deal__c"));
    expect(calls.find((call) => call.key === CREATE)?.body).toBe('{"fields":{"name__c":"Acme"}}');
    expect(screen.queryByTestId("record-loading")).toBeNull();
  });

  it("asks for the description of the object for a new record: no record, the member's own type", async () => {
    const urls: string[] = [];
    formApi(DEAL_FORM, {});
    const original = globalThis.fetch;
    vi.stubGlobal("fetch", async (request: Request) => {
      if (new URL(request.url).pathname === "/api/v1/objects/Deal__c/form") {
        urls.push(new URL(request.url).search);
      }
      return original(request);
    });
    renderRecord();

    await screen.findByRole("heading", { level: 1, name: "New deal" });

    expect(urls).toEqual([""]);
  });

  it("opens a record under the name that names it, with its own description (it asks for it by the record)", async () => {
    const urls: string[] = [];
    formApi(DEAL_FORM, { [RECORD]: () => json({ data: deal("r1", { name__c: "Acme" }) }) });
    const original = globalThis.fetch;
    vi.stubGlobal("fetch", async (request: Request) => {
      if (new URL(request.url).pathname === "/api/v1/objects/Deal__c/form") {
        urls.push(new URL(request.url).search);
      }
      return original(request);
    });
    renderRecord("r1");

    expect(await screen.findByRole("heading", { level: 1, name: "Acme" })).toBeInTheDocument();
    expect(screen.getByLabelText(/Name/)).toHaveValue("Acme");
    expect(urls).toEqual(["?recordId=r1"]);
  });

  it("saves a change with the version that was read and goes back to the list", async () => {
    const calls = formApi(DEAL_FORM, {
      [RECORD]: () => json({ data: deal("r1", { name__c: "Acme" }, { version: 7 }) }),
      [PATCH]: () => json({ data: deal("r1", { name__c: "Acme Ltd" }, { version: 8 }) }),
    });
    renderRecord("r1");
    await screen.findByRole("heading", { level: 1, name: "Acme" });

    fireEvent.change(screen.getByLabelText(/Name/), { target: { value: "Acme Ltd" } });
    fireEvent.click(screen.getByTestId("save-record"));

    await waitFor(() => expect(push).toHaveBeenCalledWith("/records/Deal__c"));
    expect(calls.find((call) => call.key === PATCH)?.body).toBe('{"version":7,"fields":{"name__c":"Acme Ltd"},"clear":[]}');
  });

  it("says a record is not available when it cannot be read, in the same words whatever the reason", async () => {
    formApi(DEAL_FORM, { [RECORD]: () => refusal("NOT_FOUND", "This record does not exist.", 404) });
    renderRecord("r1");

    const note = await screen.findByTestId("record-unavailable");

    expect(note).toHaveTextContent("This page is not available.");
  });

  it("does not show a create form to a member who may not create", async () => {
    formApi({ ...DEAL_FORM, actions: { create: false, update: true, delete: true } });
    renderRecord();

    expect(await screen.findByTestId("record-not-allowed")).toHaveTextContent("You cannot create deals.");
    expect(screen.queryByTestId("record-form")).toBeNull();
  });

  it("describes the object again when another record type is picked, and keeps what was typed", async () => {
    const typed = (type: string, fields: string[]): ObjectForm => ({
      ...DEAL_FORM,
      recordType: type,
      recordTypes: [
        { apiName: "New__c", label: "New business", description: "", defaultType: true, selectable: true },
        { apiName: "Renewal__c", label: "Renewal", description: "", defaultType: false, selectable: true },
      ],
      layout: { source: "LAYOUT", sections: [{ label: "Deal", columns: 1, fields }] },
    });
    const asked: string[] = [];
    fakeApi({
      [FORM]: (request) => {
        const type = new URL(request.url).searchParams.get("recordType");
        asked.push(type ?? "");
        return json({ data: type === "Renewal__c" ? typed("Renewal__c", ["name__c", "closes__c"]) : typed("New__c", ["name__c", "amount__c"]) });
      },
      [VALIDATE]: () => json({ data: { calculated: {}, cleared: [] } }),
    });
    renderRecord();

    await screen.findByLabelText(/Amount/);
    fireEvent.change(screen.getByLabelText(/Name/), { target: { value: "Acme" } });
    fireEvent.change(screen.getByLabelText("Record type"), { target: { value: "Renewal__c" } });

    expect(await screen.findByLabelText(/Closes/)).toBeInTheDocument();
    expect(screen.queryByLabelText(/Amount/)).toBeNull();
    expect(screen.getByLabelText(/Name/)).toHaveValue("Acme");
    expect(asked).toEqual(["", "Renewal__c"]);
  });

  it("can be walked away from: Cancel goes to the list", async () => {
    formApi();
    renderRecord();
    await screen.findByTestId("record-form");

    fireEvent.click(screen.getByRole("button", { name: "Close" }));

    expect(push).toHaveBeenCalledWith("/records/Deal__c");
  });
});

describe("a copy of a record", () => {
  function renderCopy() {
    return render(
      <RecordFormPage
        objectApiName="Deal__c"
        cloneFromId="r1"
        listHref="/records/Deal__c"
        recordHref={(object, id) => `/records/${object}/${id}`}
        savedHref={(id) => `/records/Deal__c/${id}`}
      />,
    );
  }

  function copyApi(): Call[] {
    return fakeApi({
      [FORM]: () => json({ data: DEAL_FORM }),
      [RECORD]: () => json({ data: deal("r1", { name__c: "Acme", stage__c: "WON" }) }),
      [CREATE]: () => json({ data: deal("new") }, 201),
      [VALIDATE]: () => json({ data: { calculated: {}, cleared: [] } }),
      [LABELS]: () => json({ data: { labels: {} } }),
    });
  }

  it("starts the create form from the values of the original and says it is a copy", async () => {
    copyApi();
    renderCopy();

    expect(await screen.findByRole("heading", { level: 1, name: "New deal (a copy)" })).toBeInTheDocument();
    expect(screen.getByLabelText(/Name/)).toHaveValue("Acme");
    expect(screen.getByLabelText(/Stage/)).toHaveValue("WON");
  });

  it("creates a new record from them, never carrying the owner, and opens the new record", async () => {
    const calls = copyApi();
    renderCopy();
    await screen.findByRole("heading", { level: 1, name: "New deal (a copy)" });

    fireEvent.click(screen.getByTestId("save-record"));

    await waitFor(() => expect(push).toHaveBeenCalledWith("/records/Deal__c/new"));
    const sent = JSON.parse(calls.find((call) => call.key === CREATE)?.body ?? "{}") as { fields: Record<string, unknown> };
    expect(sent.fields).toMatchObject({ name__c: "Acme", stage__c: "WON" });
    expect(Object.keys(sent.fields)).not.toContain("ownerId");
  });

  it("falls back to an empty create form when the original cannot be read", async () => {
    fakeApi({
      [FORM]: () => json({ data: DEAL_FORM }),
      [RECORD]: () => refusal("NOT_FOUND", "This record does not exist.", 404),
    });
    renderCopy();

    // Nothing to copy from, so it is an ordinary new record and does not claim to be a copy.
    expect(await screen.findByRole("heading", { level: 1, name: "New deal" })).toBeInTheDocument();
    expect(screen.getByLabelText(/Name/)).toHaveValue("");
  });
});

describe("a list that starts narrowed to one record", () => {
  const OWNER_ID = "11111111-1111-1111-1111-111111111111";

  it("filters by the link, and names the record in the chip instead of showing its identifier", async () => {
    const calls = shellApi({
      more: {
        [FORM]: () => json({ data: DEAL_FORM }),
        [QUERY]: () => json(page([deal("d1", { name__c: "Acme" })])),
        [VIEWS]: () => json({ data: [] }),
        [LABELS]: () => json({ data: { labels: { [OWNER_ID]: "User A" } } }),
      },
    });
    render(
      <ShellProviders>
        <ObjectListPage
          objectApiName="Deal__c"
          openHref={(id) => `/records/Deal__c/${id}`}
          recordHref={(object, id) => `/records/${object}/${id}`}
          backHref="/"
          initialFilter={{ field: "ownerId", value: OWNER_ID }}
        />
      </ShellProviders>,
    );

    expect(await screen.findByText("Owner is User A")).toBeInTheDocument();
    const asked = JSON.parse(calls.find((call) => call.key === QUERY)?.body ?? "{}") as { filter: unknown };
    expect(asked.filter).toEqual({ op: "EQ", field: "ownerId", values: [OWNER_ID] });
    expect(screen.queryByText(new RegExp(OWNER_ID))).toBeNull();
  });

  it("ignores a starting filter on a field the member has no such comparison for", async () => {
    const calls = shellApi({
      more: {
        [FORM]: () => json({ data: DEAL_FORM }),
        [QUERY]: () => json(page([])),
        [VIEWS]: () => json({ data: [] }),
      },
    });
    render(
      <ShellProviders>
        <ObjectListPage
          objectApiName="Deal__c"
          openHref={(id) => `/records/Deal__c/${id}`}
          recordHref={(object, id) => `/records/${object}/${id}`}
          backHref="/"
          initialFilter={{ field: "notes__c", value: OWNER_ID }}
        />
      </ShellProviders>,
    );

    await screen.findByTestId("list-empty");
    const asked = JSON.parse(calls.find((call) => call.key === QUERY)?.body ?? "{}") as { filter?: unknown };
    expect(asked.filter).toBeUndefined();
  });
});
