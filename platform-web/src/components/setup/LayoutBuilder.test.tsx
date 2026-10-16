import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { StrictMode } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { dragAndDrop } from "@/test/dragDrop";
import { fakeApi, json, refusal } from "@/test/fakeApi";
import { LayoutBuilder } from "./LayoutBuilder";

const state = vi.hoisted(() => ({ layout: "Details__c", replace: vi.fn() }));
vi.mock("next/navigation", () => ({
  useParams: () => ({ objectApiName: "Deal__c", layoutApiName: state.layout }),
  useRouter: () => ({ replace: state.replace, push: vi.fn() }),
}));

beforeEach(() => {
  document.cookie = "XSRF-TOKEN=test-token; path=/";
  window.sessionStorage.clear();
  state.layout = "Details__c";
  state.replace.mockReset();
});

afterEach(() => {
  vi.unstubAllGlobals();
  window.sessionStorage.clear();
  document.cookie = "XSRF-TOKEN=; path=/; max-age=0";
});

const BASE = "/api/v1/metadata/objects/Deal__c";

function field(apiName: string, extra: Record<string, unknown> = {}) {
  return {
    apiName, label: apiName, description: "", kind: "CUSTOM", type: "TEXT", required: false, unique: false,
    settings: {}, retired: false, editable: true, version: 0, ...extra,
  };
}

const DEAL = {
  apiName: "Deal__c", label: "Deal", pluralLabel: "Deals", description: "", kind: "CUSTOM", editable: true,
  extensible: true, version: 0,
  fields: [
    field("id", { kind: "SYSTEM", editable: false }),
    field("code__c", { label: "Code", required: true }),
    field("stage__c", { label: "Stage" }),
    field("note__c", { label: "Note" }),
  ],
};
const DETAILS = {
  apiName: "Details__c", objectApiName: "Deal__c", label: "Details", description: "The main form", version: 3,
  definition: {
    sections: [
      { label: "Main", columns: 2, fields: [{ field: "code__c", behaviour: "REQUIRED" }] },
      { label: "More", fields: [] },
    ],
  },
};

function answers(extra: Record<string, () => Response> = {}) {
  return {
    [`GET ${BASE}`]: () => json({ data: DEAL }),
    [`GET ${BASE}/layouts/Details__c`]: () => json({ data: DETAILS }),
    ...extra,
  };
}

function chip(name: string) {
  return within(screen.getByTestId("palette")).getByRole("button", { name });
}

function sectionsOf() {
  return screen.getAllByTestId("layout-section");
}

describe("LayoutBuilder", () => {
  it("shows the layout in sections, offers the fields that are not on it yet, and warns about a required field that is missing", async () => {
    fakeApi(answers());

    render(
      <StrictMode>
        <LayoutBuilder />
      </StrictMode>,
    );

    const sections = await waitFor(() => {
      const found = sectionsOf();
      expect(found).toHaveLength(2);
      return found;
    });
    expect(within(sections[0] as HTMLElement).getByLabelText("Label of section 1")).toHaveValue("Main");
    expect(within(sections[0] as HTMLElement).getAllByTestId("layout-field")).toHaveLength(1);
    // The field that is placed is dimmed in the palette; the others can be added; the system field is not offered.
    expect(chip("Code *")).toHaveAttribute("aria-disabled", "true");
    expect(chip("Stage")).not.toHaveAttribute("aria-disabled");
    expect(within(screen.getByTestId("palette")).queryByText("Record ID")).toBeNull();
    expect(screen.queryByTestId("layout-missing-required")).toBeNull();
    expect(screen.queryByTestId("unsaved")).toBeNull();
  });

  it("adds a field to the chosen section by dragging it from the palette, and saves what the API checks", async () => {
    const calls = fakeApi(answers({ [`PUT ${BASE}/layouts/Details__c`]: () => json({ data: DETAILS }) }));
    render(<LayoutBuilder />);
    await screen.findAllByTestId("layout-section");

    dragAndDrop(chip("Stage"), within(sectionsOf()[1] as HTMLElement).getByTestId("layout-section-items"));

    expect(await screen.findByTestId("unsaved")).toBeInTheDocument();
    expect(within(sectionsOf()[1] as HTMLElement).getAllByTestId("layout-field")).toHaveLength(1);
    expect(chip("Stage")).toHaveAttribute("aria-disabled", "true");

    fireEvent.change(screen.getByLabelText("Behaviour of Stage"), { target: { value: "READ_ONLY" } });
    fireEvent.click(screen.getByRole("button", { name: "Save the page layout" }));

    expect(await screen.findByTestId("layout-builder-notice")).toHaveTextContent("The page layout was saved.");
    const sent = JSON.parse(calls.find((call) => call.key === `PUT ${BASE}/layouts/Details__c`)?.body ?? "{}");
    expect(sent.version).toBe(3);
    expect(sent.definition).toEqual({
      sections: [
        { label: "Main", columns: 2, fields: [{ field: "code__c", behaviour: "REQUIRED" }] },
        { label: "More", columns: 1, fields: [{ field: "stage__c", behaviour: "READ_ONLY" }] },
      ],
    });
  });

  it("adds a field with the keyboard to the section last chosen, and moves it to another section", async () => {
    fakeApi(answers());
    render(<LayoutBuilder />);
    await screen.findAllByTestId("layout-section");

    fireEvent.focus(within(sectionsOf()[1] as HTMLElement).getByLabelText("Label of section 2"));
    fireEvent.keyDown(chip("Note"), { key: "Enter" });

    expect(within(sectionsOf()[1] as HTMLElement).getAllByTestId("layout-field")).toHaveLength(1);

    fireEvent.change(screen.getByLabelText("Section of Note"), {
      target: { value: (screen.getByLabelText("Section of Note") as HTMLSelectElement).options[0]?.value },
    });
    expect(within(sectionsOf()[0] as HTMLElement).getAllByTestId("layout-field")).toHaveLength(2);
    expect(within(sectionsOf()[1] as HTMLElement).queryAllByTestId("layout-field")).toHaveLength(0);
  });

  it("starts a new section when a field is dropped on the add control, and reorders and removes sections", async () => {
    fakeApi(answers());
    render(<LayoutBuilder />);
    await screen.findAllByTestId("layout-section");

    dragAndDrop(chip("Note"), screen.getByRole("button", { name: /Add a section/ }));
    expect(sectionsOf()).toHaveLength(3);
    expect(within(sectionsOf()[2] as HTMLElement).getAllByTestId("layout-field")).toHaveLength(1);

    fireEvent.click(screen.getByRole("button", { name: "Move section 1 down" }));
    expect(within(sectionsOf()[1] as HTMLElement).getByLabelText("Label of section 2")).toHaveValue("Main");

    fireEvent.click(screen.getByRole("button", { name: "Take out section 3" }));
    expect(sectionsOf()).toHaveLength(2);
    // The field of the section that was taken out is back in the palette.
    expect(chip("Note")).not.toHaveAttribute("aria-disabled");
  });

  it("takes a field out and puts it back in the palette", async () => {
    fakeApi(answers());
    render(<LayoutBuilder />);
    await screen.findAllByTestId("layout-section");

    fireEvent.click(screen.getByRole("button", { name: "Take out Code" }));

    expect(screen.queryAllByTestId("layout-field")).toHaveLength(0);
    expect(chip("Code *")).not.toHaveAttribute("aria-disabled");
    expect(screen.getByTestId("layout-missing-required")).toHaveTextContent("Not on it yet: Code");
  });

  it("makes a new layout, sends the draft in the shape of the API and moves on to the layout it made", async () => {
    state.layout = "new";
    const calls = fakeApi({
      [`GET ${BASE}`]: () => json({ data: DEAL }),
      [`POST ${BASE}/layouts`]: () => json({ data: { ...DETAILS, apiName: "Main__c", label: "Main" } }, 201),
    });
    render(<LayoutBuilder />);
    await screen.findAllByTestId("layout-section");

    fireEvent.change(screen.getByLabelText("Name"), { target: { value: "Main" } });
    fireEvent.change(screen.getByLabelText("Label"), { target: { value: "Main" } });
    fireEvent.click(chip("Code *"));
    fireEvent.change(screen.getByLabelText("Columns of section 1"), { target: { value: "2" } });
    fireEvent.click(screen.getByRole("button", { name: "Add the page layout" }));

    expect(await screen.findByTestId("layout-builder-notice")).toHaveTextContent("The page layout was added.");
    const sent = JSON.parse(calls.find((call) => call.key === `POST ${BASE}/layouts`)?.body ?? "{}");
    expect(sent.name).toBe("Main");
    expect(sent.definition).toEqual({
      sections: [{ label: "Details", columns: 2, fields: [{ field: "code__c", behaviour: "EDITABLE" }] }],
    });
    expect(state.replace).toHaveBeenCalledWith("/setup/objects/Deal__c/layouts/Main__c");
  });

  it("puts the change into the chosen change set instead of making it live", async () => {
    window.sessionStorage.setItem("platform.workingChangeSet", "set-1");
    const calls = fakeApi(answers({ "POST /api/v1/metadata/change-sets/set-1/changes": () => json({ data: {} }, 201) }));
    render(<LayoutBuilder />);
    await screen.findAllByTestId("layout-section");

    fireEvent.click(chip("Note"));
    fireEvent.click(screen.getByRole("button", { name: "Save the page layout" }));

    expect(await screen.findByTestId("layout-builder-notice")).toHaveTextContent("goes live when the set is published");
    expect(calls.some((call) => call.key === `PUT ${BASE}/layouts/Details__c`)).toBe(false);
    const sent = JSON.parse(calls.find((call) => call.key.endsWith("/changes"))?.body ?? "{}");
    expect(sent.kind).toBe("UPDATE_LAYOUT");
    expect(sent.itemApiName).toBe("Details__c");
    expect(sent.updateLayout.version).toBe(3);
  });

  it("shows the words of the API with the place that is wrong, and keeps the draft", async () => {
    fakeApi(
      answers({
        [`PUT ${BASE}/layouts/Details__c`]: () =>
          json(
            {
              error: {
                code: "VALIDATION_ERROR",
                message: "The request has problems.",
                fields: { "definition.sections": ["Show the required field code__c."] },
              },
            },
            400,
          ),
      }),
    );
    render(<LayoutBuilder />);
    await screen.findAllByTestId("layout-section");
    fireEvent.click(screen.getByRole("button", { name: "Take out Code" }));

    fireEvent.click(screen.getByRole("button", { name: "Save the page layout" }));

    expect(await screen.findByTestId("layout-builder-problem")).toHaveTextContent("Show the required field code__c.");
    expect(screen.getByTestId("unsaved")).toBeInTheDocument();
  });

  it("previews the draft with sample values", async () => {
    fakeApi(answers());
    render(<LayoutBuilder />);
    await screen.findAllByTestId("layout-section");

    fireEvent.click(chip("Stage"));
    fireEvent.click(screen.getByRole("button", { name: "Preview" }));

    const preview = await screen.findByTestId("layout-preview");
    expect(within(preview).getAllByTestId("preview-field")).toHaveLength(2);
  });

  it("asks before leaving with unsaved changes", async () => {
    fakeApi(answers());
    render(<LayoutBuilder />);
    await screen.findAllByTestId("layout-section");

    expect(screen.getByRole("link", { name: "Back" })).toHaveAttribute("href", "/setup/objects/Deal__c?section=layouts");
    fireEvent.click(chip("Stage"));
    fireEvent.click(screen.getByRole("button", { name: "Cancel" }));

    const confirm = screen.getByRole("group", { name: "Discard the changes?" });
    expect(within(confirm).getByRole("link", { name: "Yes, discard" })).toHaveAttribute(
      "href",
      "/setup/objects/Deal__c?section=layouts",
    );
    fireEvent.click(within(confirm).getByRole("button", { name: "Keep editing" }));
    expect(screen.queryByRole("group", { name: "Discard the changes?" })).toBeNull();
  });

  it("shows what the API answered for a layout that does not exist", async () => {
    fakeApi({
      [`GET ${BASE}`]: () => json({ data: DEAL }),
      [`GET ${BASE}/layouts/Details__c`]: () => refusal("NOT_FOUND", "There is no such page layout.", 404),
    });
    render(<LayoutBuilder />);

    expect(await screen.findByTestId("layout-builder-failed")).toHaveTextContent("There is no such page layout.");
  });
});
