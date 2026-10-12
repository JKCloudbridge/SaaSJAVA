import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { itemText } from "./ChangeSetReport";
import { LayoutPreview, placeholderOf } from "./LayoutPreview";
import { CHANGE_KIND_TEXT, problemText } from "./objectText";
import { describeRule } from "./RecordPagePreview";

function field(apiName: string, type: string, extra: Record<string, unknown> = {}) {
  return {
    apiName, label: apiName, description: "", kind: "CUSTOM", type, required: false, unique: false,
    settings: {}, retired: false, editable: true, version: 0, ...extra,
  };
}

describe("describeRule", () => {
  it("reads closed rules aloud, nested rules in brackets", () => {
    expect(describeRule(undefined)).toBe("");
    expect(describeRule({ ability: "members.view" })).toBe("the person holds the ability members.view");
    expect(describeRule({ field: "a", operator: "IS_EMPTY" })).toBe("a is empty");
    expect(describeRule({ field: "a", operator: "NOT_EQUALS", value: 3 })).toBe("a is not 3");
    expect(
      describeRule({ any: [{ field: "a", operator: "EQUALS", value: "x" }, { all: [{ ability: "k.v" }] }] }),
    ).toBe("(a is x or (the person holds the ability k.v))");
  });
});

describe("placeholderOf", () => {
  it("makes up a value from the kind of field and never from stored data", () => {
    expect(placeholderOf(field("a", "DATE") as never)).toBe("2026-01-31");
    expect(placeholderOf(field("a", "LOOKUP") as never)).toBe("A linked record");
    expect(placeholderOf(undefined)).toBe("Field not found");
    expect(
      placeholderOf(
        field("a", "PICKLIST", { settings: { values: [{ value: "x", label: "Old", active: false }, { value: "y", label: "Open", active: true }] } }) as never,
      ),
    ).toBe("Open");
  });
});

describe("LayoutPreview", () => {
  it("draws two columns, the required mark and a read-only field, and leaves a hidden field out", () => {
    render(
      <LayoutPreview
        title="Details"
        fields={[field("code__c", "TEXT", { required: true }), field("note__c", "TEXT"), field("secret__c", "TEXT")] as never}
        layout={{
          sections: [
            {
              label: "Main",
              columns: 2,
              fields: [{ field: "code__c" }, { field: "note__c", behaviour: "READ_ONLY" }, { field: "secret__c", behaviour: "HIDDEN" }],
            },
          ],
        }}
      />,
    );

    expect(screen.getAllByTestId("preview-field")).toHaveLength(2);
    expect(screen.getByLabelText(/code__c/)).toHaveAttribute("aria-required", "true");
    expect(screen.getByLabelText("note__c")).toBeDisabled();
    expect(screen.queryByLabelText(/secret__c/)).toBeNull();
  });
});

describe("the words for the new kinds of change", () => {
  it("names every kind of Sprint 12 in words", () => {
    expect(CHANGE_KIND_TEXT.CREATE_LAYOUT).toBe("Add page layout");
    expect(CHANGE_KIND_TEXT.UPDATE_APPLICATION).toBe("Change application");
    expect(CHANGE_KIND_TEXT.DELETE_LAYOUT_ASSIGNMENT).toBe("Remove a layout choice");
  });

  it("has words for the record type assignments of Sprint 15A", () => {
    expect(CHANGE_KIND_TEXT.CREATE_RECORD_TYPE_ASSIGNMENT).toBe("Let a profile use a record type");
    expect(CHANGE_KIND_TEXT.UPDATE_RECORD_TYPE_ASSIGNMENT).toBe("Change a profile's default record type");
    expect(CHANGE_KIND_TEXT.DELETE_RECORD_TYPE_ASSIGNMENT).toBe("Stop a profile from using a record type");
    expect(
      itemText({
        action: "ADDED",
        kind: "RECORD_TYPE_ASSIGNMENT",
        objectApiName: "Order__c",
        itemApiName: "Renewal__c~0190c0de-0000-7000-8000-000000000001",
      }),
    ).toBe("Added record type choice Order__c.Renewal__c~0190c0de-0000-7000-8000-000000000001");
  });

  it("has words for the changes of validation rules, in the change sets and in the history", () => {
    expect(CHANGE_KIND_TEXT.CREATE_VALIDATION_RULE).toBe("Add validation rule");
    expect(CHANGE_KIND_TEXT.UPDATE_VALIDATION_RULE).toBe("Change validation rule");
    expect(CHANGE_KIND_TEXT.DELETE_VALIDATION_RULE).toBe("Remove validation rule");
    expect(
      itemText({ action: "ADDED", kind: "VALIDATION_RULE", objectApiName: "Order__c", itemApiName: "NoNegative__c" }),
    ).toBe("Added validation rule Order__c.NoNegative__c");
  });

  it("names the condition, the message and the field of a rule in the problems of the API", () => {
    const response = new Response(null, { status: 400 });
    const error = {
      error: {
        code: "VALIDATION_ERROR",
        message: "Wrong.",
        fields: { expression: ["Line 1, column 3: Oops."], errorMessage: ["Must not be empty."], errorField: ["Give a field."] },
      },
    };

    const text = problemText(error, response);

    expect(text).toContain("Condition: Line 1, column 3: Oops.");
    expect(text).toContain("Message: Must not be empty.");
    expect(text).toContain("Field of the message: Give a field.");
  });

  it("names an application by itself because it belongs to no object", () => {
    expect(itemText({ action: "ADDED", kind: "APPLICATION", objectApiName: "", itemApiName: "Sales__c" })).toBe(
      "Added application Sales__c",
    );
    expect(itemText({ action: "CHANGED", kind: "LAYOUT", objectApiName: "Deal__c", itemApiName: "Details__c" })).toBe(
      "Changed page layout Deal__c.Details__c",
    );
  });

  it("shows the place inside a definition without its first word", () => {
    const response = new Response(null, { status: 400 });
    const error = { error: { code: "VALIDATION_ERROR", message: "Wrong.", fields: { "definition.sections[1].label": ["Needed."] } } };

    expect(problemText(error, response)).toContain("sections[1].label: Needed.");
  });
});
