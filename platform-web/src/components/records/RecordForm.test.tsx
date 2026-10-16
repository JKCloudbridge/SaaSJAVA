import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import type { ComponentProps } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { fakeApi, json, refusal, type Call, type Handler } from "@/test/fakeApi";
import { DEAL_FORM, deal } from "@/test/records";
import type { ObjectForm } from "@/lib/records/types";
import { RecordForm } from "./RecordForm";

vi.mock("next/link", () => ({
  default: ({ href, children }: { href: string; children: React.ReactNode }) => <a href={href}>{children}</a>,
}));

const OWNER = "11111111-1111-1111-1111-111111111111";
const CREATE = "POST /api/v1/objects/Deal__c/records";
const VALIDATE = "POST /api/v1/objects/Deal__c/records/validate";
const RECORD = "GET /api/v1/objects/Deal__c/records/r1";
const PATCH = "PATCH /api/v1/objects/Deal__c/records/r1";
const DELETE = "DELETE /api/v1/objects/Deal__c/records/r1";
const LABELS = "POST /api/v1/objects/Deal__c/lookups/ownerId/labels";

beforeEach(() => {
  document.cookie = "XSRF-TOKEN=test-token; path=/";
});

afterEach(() => {
  vi.unstubAllGlobals();
  vi.useRealTimers();
  document.cookie = "XSRF-TOKEN=; path=/; max-age=0";
});

/** What a form sends: nothing is judged by the test beyond what the page puts on the wire. */
function api(extra: Record<string, Handler> = {}): Call[] {
  return fakeApi({
    [VALIDATE]: () => json({ data: { calculated: {}, cleared: [] } }),
    [LABELS]: () => json({ data: { labels: { [OWNER]: "User A" } } }),
    ...extra,
  });
}

function bodyOf(calls: Call[], key: string, which = 0): string {
  return calls.filter((call) => call.key === key)[which]?.body ?? "";
}

function renderForm(props: Partial<ComponentProps<typeof RecordForm>> = {}) {
  const handlers = {
    onSaved: vi.fn(),
    onCancel: vi.fn(),
    onDeleted: vi.fn(),
    onRecordTypeChange: vi.fn(),
  };
  render(<RecordForm objectApiName="Deal__c" form={DEAL_FORM} {...handlers} {...props} />);
  return handlers;
}

const SAVED = deal("r1", { name__c: "Acme" });

describe("the form is drawn from the description", () => {
  it("shows the blocks of the layout in order, with a required field marked", () => {
    api();
    renderForm();

    const headings = screen.getAllByRole("heading", { level: 2 }).map((heading) => heading.textContent);
    expect(headings).toEqual(["Deal", "More"]);
    expect(screen.getByText("(required)")).toBeInTheDocument();
  });

  it("shows only what can be typed on a new record, and a calculated value only once there is one", () => {
    api();
    renderForm();

    expect(screen.getByLabelText(/Name/)).toBeInTheDocument();
    expect(screen.getByLabelText(/Amount/)).toBeInTheDocument();
    expect(screen.queryByText("Margin")).toBeNull();
    expect(screen.queryByText("Owner")).toBeNull();
    expect(screen.queryByText("Created at")).toBeNull();
  });

  it("shows a record's read-only fields with their values, the owner by name", async () => {
    api();
    renderForm({ record: deal("r1", { name__c: "Acme", margin__c: 12.5 }) });

    expect(screen.getByTestId("value-margin__c")).toHaveTextContent("12.5");
    await waitFor(() => expect(screen.getByTestId("value-ownerId")).toHaveTextContent("User A"));
    expect(screen.getByTestId("value-ownerId")).not.toHaveTextContent(OWNER);
  });

  it("fills the controls with the record's values", () => {
    api();
    renderForm({ record: deal("r1", { name__c: "Acme", amount__c: 1500.5, stage__c: "WON" }) });

    expect(screen.getByLabelText(/Name/)).toHaveValue("Acme");
    expect(screen.getByLabelText(/Amount/)).toHaveValue("1500.5");
    expect(screen.getByLabelText(/Stage/)).toHaveValue("WON");
  });

  it("offers no save to a member who may only look, and says so", () => {
    api();
    renderForm({ record: deal("r1"), form: { ...DEAL_FORM, actions: { create: false, update: false, delete: false } } });

    expect(screen.queryByTestId("save-record")).toBeNull();
    expect(screen.getByText("You can look at this record but not change it.")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Delete" })).toBeNull();
  });
});

describe("creating a record", () => {
  it("sends only what was typed, with a number exactly as typed, and reports the saved record", async () => {
    const calls = api({ [CREATE]: () => json({ data: SAVED }, 201) });
    const { onSaved } = renderForm();

    fireEvent.change(screen.getByLabelText(/Name/), { target: { value: "Acme" } });
    fireEvent.change(screen.getByLabelText(/Amount/), { target: { value: "1500.50" } });
    fireEvent.change(screen.getByLabelText(/Stage/), { target: { value: "WON" } });
    fireEvent.click(screen.getByTestId("save-record"));

    await waitFor(() => expect(onSaved).toHaveBeenCalledWith(SAVED));
    expect(bodyOf(calls, CREATE)).toBe('{"fields":{"name__c":"Acme","amount__c":1500.50,"stage__c":"WON"}}');
  });

  it("does not send a field the person left alone or typed and then emptied again", async () => {
    const calls = api({ [CREATE]: () => json({ data: SAVED }, 201) });
    const { onSaved } = renderForm();

    fireEvent.change(screen.getByLabelText(/Name/), { target: { value: "Acme" } });
    fireEvent.change(screen.getByLabelText(/Amount/), { target: { value: "5" } });
    fireEvent.change(screen.getByLabelText(/Amount/), { target: { value: "" } });
    fireEvent.click(screen.getByTestId("save-record"));

    await waitFor(() => expect(onSaved).toHaveBeenCalled());
    expect(bodyOf(calls, CREATE)).toBe('{"fields":{"name__c":"Acme"}}');
  });

  it("does not call the server for a required field left empty: it says what is missing, beside the field", async () => {
    const calls = api();
    renderForm();

    fireEvent.click(screen.getByTestId("save-record"));

    expect(await screen.findByText("Enter a value for this field.")).toBeInTheDocument();
    expect(screen.getByLabelText(/Name/)).toHaveAttribute("aria-invalid", "true");
    expect(calls.some((call) => call.key === CREATE)).toBe(false);
  });

  it("does not call the server for a number that cannot be right", async () => {
    const calls = api();
    renderForm();

    fireEvent.change(screen.getByLabelText(/Name/), { target: { value: "Acme" } });
    fireEvent.change(screen.getByLabelText(/Amount/), { target: { value: "12.345" } });
    fireEvent.click(screen.getByTestId("save-record"));

    expect(await screen.findByText("Has more than the 2 decimals this field allows.")).toBeInTheDocument();
    expect(calls.some((call) => call.key === CREATE)).toBe(false);
  });

  it("puts the server's problems beside their fields, and the ones about the record as a whole on top", async () => {
    api({
      [CREATE]: () =>
        json(
          {
            error: {
              code: "VALIDATION_ERROR",
              message: "Check the fields.",
              fields: { name__c: ["This value is already in use. This field must be unique."], fields: ["You may not do that."] },
            },
          },
          422,
        ),
    });
    const { onSaved } = renderForm();

    fireEvent.change(screen.getByLabelText(/Name/), { target: { value: "Acme" } });
    fireEvent.click(screen.getByTestId("save-record"));

    expect(await screen.findByText("This value is already in use. This field must be unique.")).toBeInTheDocument();
    expect(screen.getByTestId("form-problems")).toHaveTextContent("You may not do that.");
    expect(onSaved).not.toHaveBeenCalled();
    // Typing in the field takes its problem away: the person is working on it.
    fireEvent.change(screen.getByLabelText(/Name/), { target: { value: "Acme 2" } });
    expect(screen.queryByText("This value is already in use. This field must be unique.")).toBeNull();
  });

  it("shows the API's own words for any other refusal", async () => {
    api({ [CREATE]: () => refusal("FORBIDDEN", "You are not allowed to do that.", 403) });
    renderForm();

    fireEvent.change(screen.getByLabelText(/Name/), { target: { value: "Acme" } });
    fireEvent.click(screen.getByTestId("save-record"));

    expect(await screen.findByTestId("form-failure")).toHaveTextContent("You are not allowed to do that.");
  });

  it("says nothing was reached when the server cannot be", async () => {
    fakeApi({
      [VALIDATE]: () => json({ data: { calculated: {}, cleared: [] } }),
      [CREATE]: () => {
        throw new TypeError("offline");
      },
    });
    renderForm();

    fireEvent.change(screen.getByLabelText(/Name/), { target: { value: "Acme" } });
    fireEvent.click(screen.getByTestId("save-record"));

    expect(await screen.findByTestId("form-failure")).toHaveTextContent("The server could not be reached");
  });
});

describe("what the server would say, before Save", () => {
  it("points out a refusal beside its field a moment after the person stops typing, and saves nothing", async () => {
    const calls = api({
      [VALIDATE]: () =>
        json({ error: { code: "VALIDATION_ERROR", message: "Check.", fields: { amount__c: ["Amount must not be negative."] } } }, 422),
    });
    renderForm();

    fireEvent.change(screen.getByLabelText(/Name/), { target: { value: "Acme" } });
    fireEvent.change(screen.getByLabelText(/Amount/), { target: { value: "-5" } });

    expect(await screen.findByText("Amount must not be negative.", {}, { timeout: 3000 })).toBeInTheDocument();
    expect(JSON.parse(bodyOf(calls, VALIDATE))).toEqual({ fields: { name__c: "Acme", amount__c: -5 }, clear: [] });
    expect(calls.some((call) => call.key === CREATE)).toBe(false);
  });

  it("asks for an existing record with its identifier, and asks nothing before anything changed", async () => {
    const calls = api();
    renderForm({ record: deal("r1", { name__c: "Acme" }) });

    await new Promise((resolve) => setTimeout(resolve, 900));
    expect(calls.some((call) => call.key === VALIDATE)).toBe(false);

    fireEvent.change(screen.getByLabelText(/Stage/), { target: { value: "WON" } });

    await waitFor(() => expect(calls.some((call) => call.key === VALIDATE)).toBe(true), { timeout: 3000 });
    expect(JSON.parse(bodyOf(calls, VALIDATE))).toEqual({ recordId: "r1", fields: { stage__c: "WON" }, clear: [] });
  });

  it("shows the result a formula will have, and says it is not saved yet", async () => {
    api({ [VALIDATE]: () => json({ data: { calculated: { margin__c: 12.5 }, cleared: [] } }) });
    renderForm();

    fireEvent.change(screen.getByLabelText(/Name/), { target: { value: "Acme" } });

    await waitFor(() => expect(screen.getByTestId("value-margin__c")).toHaveTextContent("12.5"), { timeout: 3000 });
    expect(screen.getByTestId("value-margin__c")).toHaveTextContent("will be calculated when saved");
  });

  it("shows a formula that would end up empty as empty, not as the old result", async () => {
    api({ [VALIDATE]: () => json({ data: { calculated: {}, cleared: ["margin__c"] } }) });
    renderForm({ record: deal("r1", { name__c: "Acme", margin__c: 12.5 }) });

    fireEvent.change(screen.getByLabelText(/Name/), { target: { value: "Acme 2" } });

    await waitFor(() => expect(screen.getByTestId("value-margin__c")).toHaveTextContent("—"), { timeout: 3000 });
  });

  it("does not ask while a value cannot be right: the hint is enough", async () => {
    const calls = api();
    renderForm();

    fireEvent.change(screen.getByLabelText(/Amount/), { target: { value: "abc" } });
    await new Promise((resolve) => setTimeout(resolve, 900));

    expect(calls.some((call) => call.key === VALIDATE)).toBe(false);
  });
});

describe("changing a record", () => {
  const record = deal("r1", { name__c: "Acme", amount__c: 1500.5, stage__c: "OPEN", notes__c: "n" }, { version: 3 });

  it("sends the version that was read and only the fields that changed", async () => {
    const calls = api({ [PATCH]: () => json({ data: deal("r1", { stage__c: "WON" }, { version: 4 }) }) });
    const { onSaved } = renderForm({ record });

    fireEvent.change(screen.getByLabelText(/Stage/), { target: { value: "WON" } });
    fireEvent.click(screen.getByTestId("save-record"));

    await waitFor(() => expect(onSaved).toHaveBeenCalled());
    expect(bodyOf(calls, PATCH)).toBe('{"version":3,"fields":{"stage__c":"WON"},"clear":[]}');
  });

  it("clears a field the person emptied", async () => {
    const calls = api({ [PATCH]: () => json({ data: record }) });
    const { onSaved } = renderForm({ record });

    fireEvent.change(screen.getByLabelText(/Notes/), { target: { value: "" } });
    fireEvent.click(screen.getByTestId("save-record"));

    await waitFor(() => expect(onSaved).toHaveBeenCalled());
    expect(bodyOf(calls, PATCH)).toBe('{"version":3,"fields":{},"clear":["notes__c"]}');
  });

  it("does not make a save that changes nothing", async () => {
    const calls = api();
    renderForm({ record });

    fireEvent.click(screen.getByTestId("save-record"));

    expect(await screen.findByText("There is nothing to save: you have not changed anything.")).toBeInTheDocument();
    expect(calls.some((call) => call.key === PATCH)).toBe(false);
  });

  it("moves the record to another type only when the person picked one", async () => {
    const typed: ObjectForm = {
      ...DEAL_FORM,
      recordType: "Renewal__c",
      recordTypes: [
        { apiName: "New__c", label: "New business", description: "", defaultType: true, selectable: true },
        { apiName: "Renewal__c", label: "Renewal", description: "", defaultType: false, selectable: true },
      ],
    };
    const calls = api({ [PATCH]: () => json({ data: record }) });
    const { onSaved } = renderForm({ record: { ...record, recordType: "New__c" }, form: typed });

    fireEvent.click(screen.getByTestId("save-record"));

    await waitFor(() => expect(onSaved).toHaveBeenCalled());
    expect(bodyOf(calls, PATCH)).toContain('"recordType":"Renewal__c"');
  });
});

describe("a record that was changed by someone else while it was open", () => {
  const base = deal("r1", { name__c: "Acme", stage__c: "OPEN" }, { version: 3 });

  /** The first save is refused because the record moved; the record is then read, and a later save works. */
  function conflicted(latest: ReturnType<typeof deal>) {
    let saves = 0;
    return api({
      [PATCH]: () => {
        saves += 1;
        return saves === 1 ? refusal("CONCURRENT_MODIFICATION", "The record was changed by someone else.", 409) : json({ data: latest });
      },
      [RECORD]: () => json({ data: latest }),
    });
  }

  it("says what happened, keeps the person's work and shows what the other change touched", async () => {
    const calls = conflicted(deal("r1", { name__c: "Acme", stage__c: "WON" }, { version: 4 }));
    renderForm({ record: base });

    fireEvent.change(screen.getByLabelText(/Name/), { target: { value: "Acme Ltd" } });
    fireEvent.click(screen.getByTestId("save-record"));

    const panel = await screen.findByTestId("conflict-panel");
    expect(panel).toHaveTextContent("This record was changed while you were editing it");
    expect(screen.getByTestId("conflict-theirs")).toHaveTextContent("Stage");
    expect(screen.getByTestId("conflict-kept")).toHaveTextContent("Name");
    expect(calls.filter((call) => call.key === PATCH)).toHaveLength(1);
    // Nothing can be saved over the other change from here.
    expect(screen.getByTestId("save-record")).toBeDisabled();
  });

  it("reloads and reapplies: the newer record is the base, the person's change stays on top, and the next save carries the new version", async () => {
    const calls = conflicted(deal("r1", { name__c: "Acme", stage__c: "WON" }, { version: 4 }));
    const { onSaved } = renderForm({ record: base });

    fireEvent.change(screen.getByLabelText(/Name/), { target: { value: "Acme Ltd" } });
    fireEvent.click(screen.getByTestId("save-record"));
    fireEvent.click(await screen.findByRole("button", { name: "Reload and reapply my changes" }));

    expect(await screen.findByText(/Your changes are now on top of the newest version/)).toBeInTheDocument();
    expect(screen.getByLabelText(/Stage/)).toHaveValue("WON");
    expect(screen.getByLabelText(/Name/)).toHaveValue("Acme Ltd");
    expect(screen.queryByTestId("conflict-panel")).toBeNull();

    fireEvent.click(screen.getByTestId("save-record"));

    await waitFor(() => expect(onSaved).toHaveBeenCalled());
    expect(bodyOf(calls, PATCH, 1)).toBe('{"version":4,"fields":{"name__c":"Acme Ltd"},"clear":[]}');
  });

  it("lets the person choose where both changed the same field, and keeps what they choose", async () => {
    conflicted(deal("r1", { name__c: "Acme B", stage__c: "OPEN" }, { version: 4 }));
    renderForm({ record: base });

    fireEvent.change(screen.getByLabelText(/Name/), { target: { value: "Acme A" } });
    fireEvent.click(screen.getByTestId("save-record"));

    const clashes = await screen.findByTestId("conflict-clashes");
    expect(within(clashes).getByLabelText(/Keep mine/)).toBeChecked();
    fireEvent.click(within(clashes).getByLabelText(/Use the newer value: Acme B/));
    fireEvent.click(screen.getByRole("button", { name: "Reload and reapply my changes" }));

    await waitFor(() => expect(screen.getByLabelText(/Name/)).toHaveValue("Acme B"));
  });

  it("keeps the person's own value when they choose to", async () => {
    conflicted(deal("r1", { name__c: "Acme B", stage__c: "OPEN" }, { version: 4 }));
    renderForm({ record: base });

    fireEvent.change(screen.getByLabelText(/Name/), { target: { value: "Acme A" } });
    fireEvent.click(screen.getByTestId("save-record"));
    fireEvent.click(await screen.findByRole("button", { name: "Reload and reapply my changes" }));

    await waitFor(() => expect(screen.getByLabelText(/Name/)).toHaveValue("Acme A"));
  });

  it("can discard the person's changes and take the newer record", async () => {
    conflicted(deal("r1", { name__c: "Acme B", stage__c: "WON" }, { version: 4 }));
    renderForm({ record: base });

    fireEvent.change(screen.getByLabelText(/Name/), { target: { value: "Acme A" } });
    fireEvent.click(screen.getByTestId("save-record"));
    fireEvent.click(await screen.findByRole("button", { name: "Discard my changes and reload" }));

    await waitFor(() => expect(screen.getByLabelText(/Name/)).toHaveValue("Acme B"));
    expect(screen.getByText(/Your changes were discarded/)).toBeInTheDocument();
  });

  it("says so when the record is gone or no longer visible, and saves nothing", async () => {
    api({
      [PATCH]: () => refusal("CONCURRENT_MODIFICATION", "Changed.", 409),
      [RECORD]: () => refusal("NOT_FOUND", "This record does not exist.", 404),
    });
    renderForm({ record: base });

    fireEvent.change(screen.getByLabelText(/Name/), { target: { value: "Acme Ltd" } });
    fireEvent.click(screen.getByTestId("save-record"));

    expect(await screen.findByTestId("form-failure")).toHaveTextContent("removed, or you can no longer see it");
    expect(screen.queryByTestId("conflict-panel")).toBeNull();
  });
});

describe("the other things a form does", () => {
  it("asks before it deletes, deletes only when told to, and reports it", async () => {
    const calls = api({ [DELETE]: () => new Response(null, { status: 204 }) });
    const { onDeleted } = renderForm({ record: deal("r1") });

    fireEvent.click(screen.getByRole("button", { name: "Delete" }));
    expect(calls.some((call) => call.key === DELETE)).toBe(false);
    fireEvent.click(screen.getByRole("button", { name: "Yes, delete" }));

    await waitFor(() => expect(onDeleted).toHaveBeenCalled());
  });

  it("can be walked away from after asking to delete", () => {
    const calls = api();
    renderForm({ record: deal("r1") });

    fireEvent.click(screen.getByRole("button", { name: "Delete" }));
    fireEvent.click(screen.getByRole("button", { name: "Keep it" }));

    expect(screen.getByRole("button", { name: "Delete" })).toBeInTheDocument();
    expect(calls.some((call) => call.key === DELETE)).toBe(false);
  });

  it("shows the API's words when a delete is refused", async () => {
    api({ [DELETE]: () => refusal("FORBIDDEN", "You may not delete this.", 403) });
    renderForm({ record: deal("r1") });

    fireEvent.click(screen.getByRole("button", { name: "Delete" }));
    fireEvent.click(screen.getByRole("button", { name: "Yes, delete" }));

    expect(await screen.findByTestId("form-failure")).toHaveTextContent("You may not delete this.");
  });

  it("offers the record types the member may use and tells the page when one is picked", () => {
    api();
    const typed: ObjectForm = {
      ...DEAL_FORM,
      recordType: "New__c",
      recordTypes: [
        { apiName: "New__c", label: "New business", description: "", defaultType: true, selectable: true },
        { apiName: "Renewal__c", label: "Renewal", description: "", defaultType: false, selectable: true },
        { apiName: "Closed__c", label: "Closed", description: "", defaultType: false, selectable: false },
      ],
    };
    const { onRecordTypeChange } = renderForm({ form: typed });

    const select = screen.getByLabelText("Record type");
    expect(within(select).getAllByRole("option").map((option) => option.textContent)).toEqual(["New business", "Renewal"]);
    fireEvent.change(select, { target: { value: "Renewal__c" } });

    expect(onRecordTypeChange).toHaveBeenCalledWith("Renewal__c");
  });

  it("closes without a word when nothing was changed, and offers to cancel when something was", () => {
    api();
    const { onCancel } = renderForm();

    expect(screen.getByRole("button", { name: "Close" })).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText(/Name/), { target: { value: "x" } });
    fireEvent.click(screen.getByRole("button", { name: "Cancel" }));

    expect(onCancel).toHaveBeenCalled();
  });

  it("puts a problem of a field that is not on the screen at the top, naming the field", async () => {
    const hidden: ObjectForm = {
      ...DEAL_FORM,
      layout: { source: "LAYOUT", sections: [{ label: "Deal", columns: 1, fields: ["name__c"] }] },
    };
    api({
      [CREATE]: () =>
        json({ error: { code: "VALIDATION_ERROR", message: "Check.", fields: { stage__c: ["Choose one of the active values of the list."] } } }, 422),
    });
    renderForm({ form: hidden });

    fireEvent.change(screen.getByLabelText(/Name/), { target: { value: "Acme" } });
    fireEvent.click(screen.getByTestId("save-record"));

    expect(await screen.findByTestId("form-problems")).toHaveTextContent("Stage: Choose one of the active values of the list.");
  });
});

describe("what the record page adds to the form", () => {
  it("gives the form an id and leaves out its own buttons when the page offers them", () => {
    api();
    renderForm({ record: deal("r1"), formId: "page-form", hideActions: true });

    expect(screen.getByTestId("record-form")).toHaveAttribute("id", "page-form");
    expect(screen.queryByTestId("save-record")).toBeNull();
    expect(screen.queryByRole("button", { name: "Close" })).toBeNull();
  });

  it("starts a new record from a template: what a person typed is sent, the owner never is", async () => {
    const calls = api({ [CREATE]: () => json({ data: SAVED }, 201) });
    renderForm({ template: deal("r1", { name__c: "Acme", stage__c: "WON" }) });

    expect(screen.getByLabelText(/Name/)).toHaveValue("Acme");
    fireEvent.click(screen.getByTestId("save-record"));

    await waitFor(() => expect(bodyOf(calls, CREATE)).not.toBe(""));
    const sent = JSON.parse(bodyOf(calls, CREATE)) as { fields: Record<string, unknown> };
    expect(sent.fields).toMatchObject({ name__c: "Acme", stage__c: "WON" });
    expect(Object.keys(sent.fields)).not.toContain("ownerId");
  });
});
