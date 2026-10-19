import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { StrictMode } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { dragAndDrop } from "@/test/dragDrop";
import { fakeApi, json, refusal, type Handler } from "@/test/fakeApi";
import { CATALOGUE, FIELD_TYPES, ORDER, WATCH } from "@/test/workflows";
import { WorkflowBuilder } from "./WorkflowBuilder";

const state = vi.hoisted(() => ({ workflow: "Watch__c", replace: vi.fn() }));
vi.mock("next/navigation", () => ({
  useParams: () => ({ objectApiName: "Order__c", workflowApiName: state.workflow }),
  useRouter: () => ({ replace: state.replace, push: vi.fn() }),
  useSearchParams: () => new URLSearchParams(),
}));
vi.mock("next/link", () => ({
  default: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => (
    <a href={href} {...rest}>
      {children}
    </a>
  ),
}));

beforeEach(() => {
  document.cookie = "XSRF-TOKEN=test-token; path=/";
  window.sessionStorage.clear();
  state.workflow = "Watch__c";
  state.replace.mockReset();
});

afterEach(() => {
  vi.unstubAllGlobals();
  window.sessionStorage.clear();
  document.cookie = "XSRF-TOKEN=; path=/; max-age=0";
});

const TASK = {
  ...ORDER,
  apiName: "Task__c",
  label: "Task",
  fields: [
    { ...ORDER.fields[1], apiName: "title__c", label: "title" },
    { ...ORDER.fields[1], apiName: "order__c", label: "order", type: "LOOKUP" },
  ],
};

function answers(extra: Record<string, Handler> = {}): Record<string, Handler> {
  return {
    "GET /api/v1/metadata/workflows/action-types": () => json({ data: CATALOGUE }),
    "GET /api/v1/metadata/objects": () =>
      json({ data: [{ apiName: "Order__c", label: "Order" }, { apiName: "Task__c", label: "Task" }] }),
    "GET /api/v1/metadata/field-types": () => json({ data: FIELD_TYPES }),
    "GET /api/v1/metadata/objects/Order__c": () => json({ data: ORDER }),
    "GET /api/v1/metadata/objects/Task__c": () => json({ data: TASK }),
    "GET /api/v1/metadata/workflows/Order__c/Watch__c": () => json({ data: WATCH }),
    ...extra,
  };
}

function palette() {
  return within(screen.getByTestId("palette"));
}

function actionCards() {
  return screen.getAllByTestId("workflow-action");
}

async function loaded() {
  await waitFor(() => expect(screen.queryAllByTestId("workflow-action").length).toBeGreaterThan(0));
}

describe("WorkflowBuilder", () => {
  it("shows the workflow the API gave: its trigger, its condition and its actions in order, under React strict mode", async () => {
    fakeApi(answers());

    render(
      <StrictMode>
        <WorkflowBuilder />
      </StrictMode>,
    );
    await loaded();

    expect(screen.getByLabelText("Label")).toHaveValue("Watch");
    expect(screen.getByLabelText("Condition")).toHaveValue("quantity__c > 1");
    expect(screen.getByRole("radio", { name: "When field moves" })).toHaveAttribute("aria-checked", "true");
    expect(screen.getByLabelText(/status/)).toBeChecked();
    expect(actionCards()).toHaveLength(2);
    expect(within(actionCards()[0] as HTMLElement).getByLabelText("Name of the action")).toHaveValue("Mark");
    expect(screen.queryByTestId("unsaved")).toBeNull();
  });

  it("builds the palette, the kinds of trigger and the limits from the catalogue and from nothing else", async () => {
    fakeApi(answers());
    render(<WorkflowBuilder />);
    await loaded();

    expect(palette().getAllByRole("button").map((chip) => chip.textContent)).toEqual([
      "Set fields",
      "Make record",
      "Tell someone",
      "Signal",
    ]);
    expect(screen.getAllByRole("radio").map((radio) => radio.textContent)).toEqual([
      "When made",
      "When field moves",
      "Every morning",
      "When saving",
    ]);
    expect(screen.getByText(/At most 7 actions in a workflow, 9 fields in an action, 321 characters/)).toBeInTheDocument();
    expect(screen.getByText(/up to 3 in a row/)).toBeInTheDocument();
    expect(within(actionCards()[0] as HTMLElement).getByLabelText("Attempts")).toHaveAttribute("max", "4");
  });

  it("changes with the catalogue: a type the API adds is in the palette and a type it drops is not", async () => {
    const changed = { ...CATALOGUE, actionTypes: [...CATALOGUE.actionTypes.slice(0, 1), { type: "BRAND_NEW", summary: "New.", assignments: "NONE", settings: [], phase: "AFTER_SAVE" }] };
    fakeApi(answers({ "GET /api/v1/metadata/workflows/action-types": () => json({ data: changed }) }));
    render(<WorkflowBuilder />);
    await loaded();

    expect(palette().getByRole("button", { name: "Brand new" })).toBeInTheDocument();
    expect(palette().queryByRole("button", { name: "Signal" })).toBeNull();
  });

  it("adds an action by dragging it in at a place and by the keyboard, and each is the same change", async () => {
    fakeApi(answers());
    render(<WorkflowBuilder />);
    await loaded();

    dragAndDrop(palette().getByRole("button", { name: "Signal" }), actionCards()[1] as HTMLElement);
    fireEvent.keyDown(palette().getByRole("button", { name: "Tell someone" }), { key: "Enter" });

    const names = actionCards().map((card) => (within(card).getByLabelText("Name of the action") as HTMLInputElement).value);
    expect(names).toEqual(["Mark", "Step3", "Tell", "Step4"]);
    expect(screen.getByTestId("unsaved")).toBeInTheDocument();
  });

  it("offers a setting that names an object or a field from the lists the API gave, and a closed list as a choice", async () => {
    fakeApi(answers());
    render(<WorkflowBuilder />);
    await loaded();

    fireEvent.click(palette().getByRole("button", { name: "Make record" }));
    fireEvent.click(palette().getByRole("button", { name: "Tell someone" }));
    const make = actionCards()[2] as HTMLElement;
    const tell = actionCards()[3] as HTMLElement;

    const target = within(make).getByLabelText(/Target/) as HTMLSelectElement;
    expect([...target.options].map((option) => option.textContent)).toEqual(["Choose…", "Order", "Task"]);
    fireEvent.change(target, { target: { value: "Task__c" } });
    await waitFor(() => expect(within(make).getByLabelText(/Link field/)).toBeInTheDocument());
    const link = within(make).getByLabelText(/Link field/) as HTMLSelectElement;
    await waitFor(() => expect([...link.options].map((option) => option.textContent)).toContain("title"));
    expect([...(within(tell).getByLabelText(/Which/) as HTMLSelectElement).options].map((option) => option.value)).toEqual(
      ["", "PLAIN_NOTICE"],
    );
    expect([...(within(tell).getByLabelText(/Who/) as HTMLSelectElement).options].map((option) => option.textContent)).toEqual(
      ["Choose…", "id", "status", "note", "quantity", "total"],
    );
  });

  it("offers for an assignment only the fields the API says can be written, of the object the action sets", async () => {
    fakeApi(answers());
    render(<WorkflowBuilder />);
    await loaded();

    fireEvent.click(within(actionCards()[0] as HTMLElement).getByRole("button", { name: "Add a field" }));

    const rows = within(actionCards()[0] as HTMLElement).getAllByRole("group", { name: /Field \d/ });
    const options = [...(within(rows[2] as HTMLElement).getByLabelText("Field to set") as HTMLSelectElement).options].map(
      (option) => option.textContent,
    );
    // The system field, the formula (calculated by the API's own list of types) and the retired field are not offered.
    expect(options).toEqual(["Choose a field", "status", "note", "quantity"]);
  });

  it("says in hints what an action still needs and never decides", async () => {
    fakeApi(answers());
    render(<WorkflowBuilder />);
    await loaded();

    fireEvent.click(palette().getByRole("button", { name: "Tell someone" }));

    expect(within(actionCards()[2] as HTMLElement).getByTestId("action-hints")).toHaveTextContent("Needs who.");
    expect(screen.getByTestId("actions-unfinished")).toHaveTextContent("1 action is not finished yet.");
  });

  it("saves an existing workflow with the version it read and says the API's word that it was saved", async () => {
    const calls = fakeApi(answers({ "PUT /api/v1/metadata/workflows/Order__c/Watch__c": () => json({ data: WATCH }) }));
    render(<WorkflowBuilder />);
    await loaded();

    fireEvent.change(screen.getByLabelText("Label"), { target: { value: "Watch more" } });
    fireEvent.click(screen.getByRole("button", { name: "Save the workflow" }));

    expect(await screen.findByTestId("workflow-builder-notice")).toHaveTextContent("The workflow was saved.");
    const sent = JSON.parse(calls.find((call) => call.key === "PUT /api/v1/metadata/workflows/Order__c/Watch__c")?.body ?? "{}");
    expect(sent).toMatchObject({ label: "Watch more", active: true, version: 7, condition: "quantity__c > 1" });
    expect(sent.actions[0].assignments).toEqual([
      { field: "note__c", value: "seen" },
      { field: "status__c", fromField: "note__c" },
    ]);
    expect(sent.trigger).toEqual({ kind: "WHEN_FIELD_MOVES", fields: ["status__c"] });
  });

  it("puts the change into the chosen change set instead of making it live", async () => {
    window.sessionStorage.setItem("platform.workingChangeSet", "set-1");
    const calls = fakeApi(answers({ "POST /api/v1/metadata/change-sets/set-1/changes": () => json({ data: {} }, 201) }));
    render(<WorkflowBuilder />);
    await loaded();

    fireEvent.click(screen.getByRole("button", { name: "Save the workflow" }));

    expect(await screen.findByTestId("workflow-builder-notice")).toHaveTextContent("added to the change set");
    expect(calls.some((call) => call.key === "PUT /api/v1/metadata/workflows/Order__c/Watch__c")).toBe(false);
    const change = JSON.parse(calls.find((call) => call.key === "POST /api/v1/metadata/change-sets/set-1/changes")?.body ?? "{}");
    expect(change).toMatchObject({ kind: "UPDATE_WORKFLOW", objectApiName: "Order__c", itemApiName: "Watch__c" });
  });

  it("makes a new workflow with the name typed and goes to its builder", async () => {
    state.workflow = "new";
    const calls = fakeApi(answers({ "POST /api/v1/metadata/workflows/Order__c": () => json({ data: { ...WATCH, apiName: "Fresh__c" } }, 201) }));
    render(<WorkflowBuilder />);
    await waitFor(() => expect(screen.getByLabelText("Name")).toBeInTheDocument());

    fireEvent.change(screen.getByLabelText("Name"), { target: { value: "Fresh" } });
    fireEvent.change(screen.getByLabelText("Label"), { target: { value: "Fresh" } });
    fireEvent.click(palette().getByRole("button", { name: "Signal" }));
    fireEvent.change(within(actionCards()[0] as HTMLElement).getByLabelText(/Event/), { target: { value: "a.b" } });
    fireEvent.click(screen.getByRole("button", { name: "Add the workflow" }));

    await waitFor(() => expect(state.replace).toHaveBeenCalledWith("/setup/workflows/Order__c/Fresh__c"));
    const sent = JSON.parse(calls.find((call) => call.key === "POST /api/v1/metadata/workflows/Order__c")?.body ?? "{}");
    expect(sent).toMatchObject({ name: "Fresh", label: "Fresh", active: true, trigger: { kind: "WHEN_MADE" } });
    expect(sent.actions).toEqual([{ type: "SIGNAL", name: "Step1", settings: { event: "a.b" } }]);
  });

  it("shows the API's own words for a refusal, with the place, and keeps the draft", async () => {
    const calls = fakeApi(answers({
      "PUT /api/v1/metadata/workflows/Order__c/Watch__c": () =>
        json({ error: { code: "VALIDATION_ERROR", message: "The request is not valid.", fields: { "actions[0].settings.target": ["The organization has no object with this API name."] }, requestId: "r" } }, 400),
    }));
    render(<WorkflowBuilder />);
    await loaded();

    fireEvent.click(screen.getByRole("button", { name: "Save the workflow" }));

    const problem = await screen.findByTestId("workflow-builder-problem");
    expect(problem).toHaveTextContent("The request is not valid.");
    expect(problem).toHaveTextContent("The organization has no object with this API name.");
    expect(calls.length).toBeGreaterThan(0);
    expect(screen.getByLabelText("Label")).toHaveValue("Watch");
  });

  it("shows a refusal to switch on exactly as the API words it and decides nothing itself", async () => {
    fakeApi(answers({
      "PUT /api/v1/metadata/workflows/Order__c/Watch__c": () => refusal("CONFLICT", "Workflows are not part of this organization's plan.", 409),
    }));
    render(<WorkflowBuilder />);
    await loaded();

    fireEvent.click(screen.getByRole("button", { name: "Save the workflow" }));

    expect(await screen.findByTestId("workflow-builder-problem")).toHaveTextContent("not part of this organization's plan");
  });

  it("tries the actions on the object and shows each problem under the action it is about", async () => {
    const calls = fakeApi(answers({
      "POST /api/v1/metadata/workflows/Order__c/check": () =>
        json({ data: { outcome: "RUNS", actionProblems: { "actions[0].assignments[1].fromField": ["The value of this field does not fit the field it is copied to."], actions: ["Name at least one field."] } } }),
    }));
    render(<WorkflowBuilder />);
    await loaded();

    fireEvent.click(screen.getByRole("button", { name: "Check the actions" }));

    const problems = await within(actionCards()[0] as HTMLElement).findByTestId("action-problems");
    expect(problems).toHaveTextContent("assignments[1].fromField: The value of this field does not fit");
    expect(within(actionCards()[1] as HTMLElement).queryByTestId("action-problems")).toBeNull();
    expect(screen.getByTestId("workflow-check-general")).toHaveTextContent("Name at least one field.");
    const sent = JSON.parse(calls.find((call) => call.key === "POST /api/v1/metadata/workflows/Order__c/check")?.body ?? "{}");
    expect(sent.condition).toBe("quantity__c > 1");
    expect(sent.actions).toHaveLength(2);
  });

  it("says nothing was saved when the actions fit", async () => {
    fakeApi(answers({ "POST /api/v1/metadata/workflows/Order__c/check": () => json({ data: { outcome: "RUNS", actionProblems: {} } }) }));
    render(<WorkflowBuilder />);
    await loaded();

    fireEvent.click(screen.getByRole("button", { name: "Check the actions" }));

    expect(await screen.findByTestId("workflow-check-ok")).toHaveTextContent("Nothing was saved.");
  });

  it("shows the API's words when the catalogue cannot be read", async () => {
    fakeApi({ "GET /api/v1/metadata/workflows/action-types": () => refusal("FORBIDDEN", "You may not view workflows.", 403) });

    render(<WorkflowBuilder />);

    expect(await screen.findByTestId("workflow-builder-failed")).toHaveTextContent("You may not view workflows.");
  });

  it("offers only the actions of the phase of the trigger, and drops those that no longer fit when the phase changes", async () => {
    fakeApi(answers());
    render(<WorkflowBuilder />);
    await loaded();

    expect(palette().queryByRole("button", { name: "Tidy up" })).toBeNull();
    expect(actionCards().length).toBeGreaterThan(0);

    fireEvent.click(screen.getByRole("radio", { name: "When saving" }));

    expect(palette().getByRole("button", { name: "Tidy up" })).toBeInTheDocument();
    expect(palette().queryByRole("button", { name: "Signal" })).toBeNull();
    expect(screen.queryAllByTestId("workflow-action")).toHaveLength(0);
    expect(screen.getByTestId("trigger-hints")).toHaveTextContent("exactly one action");

    fireEvent.keyDown(palette().getByRole("button", { name: "Tidy up" }), { key: "Enter" });
    expect(actionCards()).toHaveLength(1);
    expect(screen.queryByTestId("trigger-hints")).toBeNull();
    expect(screen.getByLabelText(/Switched on/)).toBeInTheDocument();
    expect(screen.getByText(/it runs inside every save/)).toBeInTheDocument();
  });
});
