import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { fakeApi, json, refusal, type Call } from "@/test/fakeApi";
import { deal, DEAL_FORM, field } from "@/test/records";
import type { ObjectForm } from "@/lib/records/types";
import { RecordForm } from "./RecordForm";

vi.mock("next/link", () => ({
  default: ({ href, children }: { href: string; children: React.ReactNode }) => <a href={href}>{children}</a>,
}));

const CREATE = "POST /api/v1/objects/Deal__c/records";
const VALIDATE = "POST /api/v1/objects/Deal__c/records/validate";
const ADD = "POST /api/v1/metadata/objects/Deal__c/fields/area__c/values";
const LABELS = "POST /api/v1/objects/Deal__c/lookups/ownerId/labels";

beforeEach(() => {
  document.cookie = "XSRF-TOKEN=test-token; path=/";
});

afterEach(() => {
  vi.unstubAllGlobals();
  document.cookie = "XSRF-TOKEN=; path=/; max-age=0";
});

const KIND = field("kind__c", "PICKLIST", {
  label: "Kind",
  values: [
    { value: "WI", label: "Work", active: true },
    { value: "IMS", label: "Integrated", active: true },
    { value: "CSF", label: "Customer", active: true },
  ],
});

function areaField(extra: Record<string, unknown> = {}) {
  return field("area__c", "PICKLIST", {
    label: "Area",
    values: [
      { value: "PRD", label: "Production", active: true },
      { value: "QLT", label: "Quality", active: true },
      { value: "CUSA", label: "Customer A", active: true },
    ],
    dependency: { controllingField: "kind__c", allowed: { WI: ["PRD", "QLT"], IMS: [], CSF: ["CUSA"] } },
    ...extra,
  });
}

function formWith(area = areaField(), kind = KIND): ObjectForm {
  const base = DEAL_FORM;
  return {
    ...base,
    fields: [...base.fields, kind, area],
    layout: {
      ...base.layout,
      sections: [
        { label: "Deal", columns: 2, fields: ["name__c", "kind__c", "area__c"] },
        ...base.layout.sections.slice(1),
      ],
    },
  };
}

function api(extra: Record<string, () => Response> = {}): Call[] {
  return fakeApi({
    [VALIDATE]: () => json({ data: { calculated: {}, cleared: [] } }),
    [LABELS]: () => json({ data: { labels: {} } }),
    ...extra,
  });
}

function renderForm(form: ObjectForm, record?: ReturnType<typeof deal>) {
  const handlers = { onSaved: vi.fn(), onCancel: vi.fn(), onDeleted: vi.fn(), onRecordTypeChange: vi.fn() };
  render(<RecordForm objectApiName="Deal__c" form={form} record={record} {...handlers} />);
  return handlers;
}

const optionsOf = (label: RegExp) =>
  within(screen.getByLabelText(label))
    .getAllByRole("option")
    .map((option) => option.getAttribute("value"));

describe("a dependent picklist on a form (FA2A)", () => {
  it("offers only the values that go with the chosen value of the other picklist", () => {
    api();
    renderForm(formWith());

    expect(optionsOf(/Area/)).toEqual([""]);
    fireEvent.change(screen.getByLabelText(/Kind/), { target: { value: "WI" } });
    expect(optionsOf(/Area/)).toEqual(["", "PRD", "QLT"]);
    fireEvent.change(screen.getByLabelText(/Kind/), { target: { value: "CSF" } });
    expect(optionsOf(/Area/)).toEqual(["", "CUSA"]);
    fireEvent.change(screen.getByLabelText(/Kind/), { target: { value: "IMS" } });
    expect(optionsOf(/Area/)).toEqual([""]);
  });

  it("clears a chosen value that stops going with the new choice, and sends the emptied field", async () => {
    const calls = api({ [CREATE]: () => json({ data: deal("r1") }, 201) });
    const { onSaved } = renderForm(formWith());

    fireEvent.change(screen.getByLabelText(/Name/), { target: { value: "Acme" } });
    fireEvent.change(screen.getByLabelText(/Kind/), { target: { value: "WI" } });
    fireEvent.change(screen.getByLabelText(/Area/), { target: { value: "PRD" } });
    expect(screen.getByLabelText(/Area/)).toHaveValue("PRD");

    fireEvent.change(screen.getByLabelText(/Kind/), { target: { value: "CSF" } });

    expect(screen.getByLabelText(/Area/)).toHaveValue("");
    fireEvent.click(screen.getByTestId("save-record"));
    await waitFor(() => expect(onSaved).toHaveBeenCalled());
    expect(calls.find((call) => call.key === CREATE)?.body).toBe('{"fields":{"name__c":"Acme","kind__c":"CSF"}}');
  });

  it("keeps a chosen value that still goes with the new choice", () => {
    api();
    renderForm(formWith());

    fireEvent.change(screen.getByLabelText(/Kind/), { target: { value: "WI" } });
    fireEvent.change(screen.getByLabelText(/Area/), { target: { value: "QLT" } });
    fireEvent.change(screen.getByLabelText(/Kind/), { target: { value: "WI" } });

    expect(screen.getByLabelText(/Area/)).toHaveValue("QLT");
  });

  it("still shows the value an old record holds, so looking at it loses nothing", () => {
    api();
    renderForm(formWith(), deal("r1", { name__c: "Old", kind__c: "CSF", area__c: "PRD" }));

    expect(screen.getByLabelText(/Area/)).toHaveValue("PRD");
    expect(optionsOf(/Area/)).toEqual(["", "PRD", "CUSA"]);
  });

  it("shows the server's words when it refuses a pair the screen allowed (the server decides again)", async () => {
    api({
      [CREATE]: () =>
        json(
          {
            error: {
              code: "VALIDATION_ERROR",
              message: "Check the fields.",
              fields: { area__c: ["This value does not go with the chosen value of Kind."] },
            },
          },
          400,
        ),
    });
    renderForm(formWith());

    fireEvent.change(screen.getByLabelText(/Name/), { target: { value: "Acme" } });
    fireEvent.change(screen.getByLabelText(/Kind/), { target: { value: "WI" } });
    fireEvent.change(screen.getByLabelText(/Area/), { target: { value: "PRD" } });
    fireEvent.click(screen.getByTestId("save-record"));

    expect(await screen.findByText("This value does not go with the chosen value of Kind.")).toBeInTheDocument();
  });

  it("offers every value of a form whose description says nothing about a dependency", () => {
    api();
    renderForm(formWith(areaField({ dependency: undefined })));

    expect(optionsOf(/Area/)).toEqual(["", "PRD", "QLT", "CUSA"]);
  });
});

describe("adding a value from the form", () => {
  it("is offered only when the description says the member may", () => {
    api();
    renderForm(formWith());
    expect(screen.queryByRole("button", { name: "Add a value" })).toBeNull();
  });

  it("adds a value with the pairs it goes with, offers it at once and chooses it", async () => {
    const calls = api({
      [ADD]: () =>
        json({ data: { value: "NEWAB", label: "NEWAB", controllingValues: ["WI", "CSF"], added: true } }),
    });
    renderForm(formWith(areaField({ canAddValues: true, codeValues: true })));
    fireEvent.change(screen.getByLabelText(/Kind/), { target: { value: "WI" } });

    fireEvent.click(screen.getByRole("button", { name: "Add a value" }));
    const form = screen.getByTestId("add-value");
    expect(within(form).getByLabelText("Work")).toBeChecked();
    fireEvent.change(within(form).getByLabelText("Code"), { target: { value: "newab" } });
    fireEvent.click(within(form).getByLabelText("Customer"));
    expect(within(form).getByLabelText("Code")).toHaveAttribute("maxlength", "10");
    fireEvent.click(within(form).getByRole("button", { name: "Add" }));

    await waitFor(() => expect(screen.queryByTestId("add-value")).toBeNull());
    expect(JSON.parse(calls.find((call) => call.key === ADD)?.body ?? "{}")).toEqual({
      value: "newab",
      controllingValues: ["WI", "CSF"],
    });
    expect(optionsOf(/Area/)).toEqual(["", "PRD", "QLT", "NEWAB"]);
    expect(screen.getByLabelText(/Area/)).toHaveValue("NEWAB");
    fireEvent.change(screen.getByLabelText(/Kind/), { target: { value: "CSF" } });
    expect(optionsOf(/Area/)).toEqual(["", "CUSA", "NEWAB"]);
  });

  it("does not call the server for an empty code or when no value of the other picklist is ticked", () => {
    const calls = api();
    renderForm(formWith(areaField({ canAddValues: true })));

    fireEvent.click(screen.getByRole("button", { name: "Add a value" }));
    const form = screen.getByTestId("add-value");
    fireEvent.change(within(form).getByLabelText("Value"), { target: { value: "X" } });

    expect(within(form).getByRole("button", { name: "Add" })).toBeDisabled();
    expect(calls.some((call) => call.key === ADD)).toBe(false);
  });

  it("shows the API's words when it refuses and keeps the form open", async () => {
    api({ [ADD]: () => refusal("VALIDATION_ERROR", "Check the value.", 400) });
    renderForm(formWith(areaField({ canAddValues: true })));
    fireEvent.change(screen.getByLabelText(/Kind/), { target: { value: "WI" } });

    fireEvent.click(screen.getByRole("button", { name: "Add a value" }));
    fireEvent.change(within(screen.getByTestId("add-value")).getByLabelText("Value"), { target: { value: "X" } });
    fireEvent.click(within(screen.getByTestId("add-value")).getByRole("button", { name: "Add" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("Check the value.");
    expect(screen.getByTestId("add-value")).toBeInTheDocument();
  });

  it("closes without adding anything when cancelled", () => {
    const calls = api();
    renderForm(formWith(areaField({ canAddValues: true })));

    fireEvent.click(screen.getByRole("button", { name: "Add a value" }));
    fireEvent.click(within(screen.getByTestId("add-value")).getByRole("button", { name: "Cancel" }));

    expect(screen.queryByTestId("add-value")).toBeNull();
    expect(calls.some((call) => call.key === ADD)).toBe(false);
  });
});
