import { fireEvent, render, screen, within } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { FieldForm } from "./FieldForm";
import type { FieldTypeView, FieldView } from "./objectText";

const TYPES = [
  { type: "TEXT", label: "Text", description: "A line.", settings: ["maxLength"], allowsRequired: true, allowsUnique: true, allowsDefault: true, calculated: false, formulaResult: true },
  { type: "PICKLIST", label: "Picklist", description: "One choice.", settings: ["values", "dependency"], allowsRequired: true, allowsUnique: false, allowsDefault: true, calculated: false, formulaResult: false },
] as FieldTypeView[];

function pick(apiName: string, label: string, codes: string[], extra: Record<string, unknown> = {}): FieldView {
  return {
    apiName,
    label,
    description: "About it.",
    kind: "CUSTOM",
    type: "PICKLIST",
    required: false,
    unique: false,
    settings: { values: codes.map((code) => ({ value: code, label: code, active: true })) },
    retired: false,
    editable: true,
    version: 4,
    ...extra,
  } as FieldView;
}

const KIND = pick("kind__c", "Kind", ["WI", "IMS", "CSF"]);
const AREA = pick("area__c", "Area", ["PRD", "QLT"], {
  settings: {
    values: [
      { value: "PRD", label: "Production", active: true },
      { value: "QLT", label: "Quality", active: true },
    ],
    dependency: { controllingField: "kind__c", allowed: { WI: ["PRD", "QLT"], IMS: [], CSF: [] } },
  },
});

function renderForm(editing: FieldView | undefined, extra: Partial<React.ComponentProps<typeof FieldForm>> = {}) {
  const onUpdate = vi.fn();
  const onCreate = vi.fn();
  render(
    <FieldForm
      types={TYPES}
      objects={[]}
      busy={false}
      onCreate={onCreate}
      onUpdate={onUpdate}
      onCancel={vi.fn()}
      siblings={[KIND, AREA, { ...KIND, apiName: "multi__c", type: "MULTI_PICKLIST" } as FieldView]}
      editing={editing}
      {...extra}
    />,
  );
  return { onUpdate, onCreate };
}

describe("the dependency editor on the field form", () => {
  it("offers the object's other single-value picklists to depend on and draws the grid once one is chosen", () => {
    renderForm(pick("sub__c", "Sub", ["PRD", "QLT"]));

    const select = screen.getByLabelText("The picklist these values depend on");
    expect(within(select).getAllByRole("option").map((option) => option.textContent)).toEqual([
      "Nothing: every value can always be chosen",
      "Kind",
      "Area",
    ]);
    expect(screen.queryByRole("table")).toBeNull();

    fireEvent.change(select, { target: { value: "kind__c" } });

    expect(screen.getAllByTestId("pairs-row")).toHaveLength(3);
    expect(screen.getByTestId("pairs-count")).toHaveTextContent("0 pairs ticked.");
  });

  it("sends the rule with an empty row for a value nothing goes with, and only values that exist", () => {
    const { onUpdate } = renderForm(pick("sub__c", "Sub", ["PRD", "QLT"]));

    fireEvent.change(screen.getByLabelText("The picklist these values depend on"), { target: { value: "kind__c" } });
    fireEvent.click(screen.getByLabelText("WI: PRD"));
    fireEvent.click(screen.getByLabelText("CSF: QLT"));
    fireEvent.submit(screen.getByRole("form", { name: "Change the field" }));

    expect(onUpdate).toHaveBeenCalledTimes(1);
    const sent = onUpdate.mock.calls[0]?.[0];
    expect(sent.settings.dependency).toEqual({
      controllingField: "kind__c",
      allowed: { WI: ["PRD"], IMS: [], CSF: ["QLT"] },
    });
    expect(sent.version).toBe(4);
  });

  it("shows the pairs of a field that depends already, and all or none ticks a whole row", () => {
    renderForm(AREA);

    expect(screen.getByLabelText("WI: Production")).toBeChecked();
    expect(screen.getByLabelText("IMS: Production")).not.toBeChecked();
    expect(screen.getByTestId("pairs-count")).toHaveTextContent("2 pairs ticked.");

    const imsRow = screen.getAllByTestId("pairs-row")[1] as HTMLElement;
    fireEvent.click(within(imsRow).getByRole("button", { name: "All" }));
    expect(screen.getByLabelText("IMS: Production")).toBeChecked();
    expect(screen.getByLabelText("IMS: Quality")).toBeChecked();
    fireEvent.click(within(imsRow).getByRole("button", { name: "None" }));
    expect(screen.getByLabelText("IMS: Production")).not.toBeChecked();
  });

  it("sends no dependency when it depends on nothing, and clears the pairs when another field is chosen", () => {
    const { onUpdate } = renderForm(AREA);

    fireEvent.change(screen.getByLabelText("The picklist these values depend on"), { target: { value: "" } });
    fireEvent.submit(screen.getByRole("form", { name: "Change the field" }));

    expect(onUpdate.mock.calls[0]?.[0].settings.dependency).toBeUndefined();
  });

  it("does not offer a multi-select picklist as the field to depend on, nor itself", () => {
    renderForm(AREA);

    const options = within(screen.getByLabelText("The picklist these values depend on"))
      .getAllByRole("option")
      .map((option) => option.textContent);
    expect(options).toEqual(["Nothing: every value can always be chosen", "Kind"]);
  });

  it("filters a long list of controlling values", () => {
    const many = pick("big__c", "Big", Array.from({ length: 14 }, (_, index) => `C${index}`));
    renderForm(pick("sub__c", "Sub", ["PRD"]), { siblings: [many] });
    fireEvent.change(screen.getByLabelText("The picklist these values depend on"), { target: { value: "big__c" } });

    expect(screen.getAllByTestId("pairs-row")).toHaveLength(14);
    fireEvent.change(screen.getByLabelText("Filter by Big"), { target: { value: "c13" } });

    expect(screen.getAllByTestId("pairs-row")).toHaveLength(1);
  });

  it("offers no editor at all when the object has no other picklist", () => {
    renderForm(pick("sub__c", "Sub", ["PRD"]), { siblings: [] });

    expect(screen.queryByTestId("pairs-editor")).toBeNull();
  });
});

describe("a platform picklist whose list the organization owns", () => {
  const OWNED = pick("sub__c", "Sub-category", ["PRD"], {
    kind: "STANDARD",
    editable: false,
    organizationValues: true,
    codeValues: true,
    indexed: false,
    externalId: false,
    searchable: false,
    settings: {
      values: [{ value: "PRD", label: "Production", active: true }],
      dependency: { controllingField: "kind__c", allowed: { WI: ["PRD"], IMS: [], CSF: [] } },
    },
  });

  it("changes only the values and the pairs: the label is shown, the controlling field is the platform's", () => {
    renderForm(OWNED, { ownsList: true });

    expect(screen.getByLabelText("Label")).toBeDisabled();
    expect(screen.getByLabelText("Description")).toBeDisabled();
    expect(screen.queryByLabelText("The picklist these values depend on")).toBeNull();
    expect(screen.getByTestId("pairs-editor")).toHaveTextContent("The platform chose that field");
    expect(screen.queryByText("Required")).toBeNull();
    expect(screen.queryByLabelText("Default value")).toBeNull();
    expect(screen.getByLabelText("Value 1")).toBeDisabled();
    expect(screen.getByText(/short code of up to 10/)).toBeInTheDocument();
  });

  it("sends a new value with its pairs and the platform's flags back unchanged", () => {
    const { onUpdate } = renderForm(OWNED, { ownsList: true });

    fireEvent.click(screen.getByRole("button", { name: "Add a value" }));
    fireEvent.change(screen.getByLabelText("Value 2"), { target: { value: "NEW" } });
    fireEvent.click(screen.getByLabelText("IMS: NEW"));
    fireEvent.submit(screen.getByRole("form", { name: "Change the field" }));

    const sent = onUpdate.mock.calls[0]?.[0];
    expect(sent.settings.values.map((one: { value: string }) => one.value)).toEqual(["PRD", "NEW"]);
    expect(sent.settings.dependency.allowed).toEqual({ WI: ["PRD"], IMS: ["NEW"], CSF: [] });
    expect(sent).toMatchObject({ indexed: false, externalId: false, searchable: false, version: 4 });
    expect(screen.getByLabelText("Value 2")).toHaveAttribute("maxlength", "10");
  });
});
