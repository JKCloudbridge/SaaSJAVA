import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { StrictMode } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { CATALOGUE, PROCESS } from "@/test/approvals";
import { fakeApi, json, refusal, type Handler } from "@/test/fakeApi";
import { FIELD_TYPES, ORDER_FIELDS } from "@/test/workflows";
import { ApprovalBuilder } from "./ApprovalBuilder";

const state = vi.hoisted(() => ({ process: "Large__c", replace: vi.fn() }));
vi.mock("next/navigation", () => ({
  useParams: () => ({ objectApiName: "Expense__c", processApiName: state.process }),
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
  state.process = "Large__c";
  state.replace.mockReset();
});

afterEach(() => {
  vi.unstubAllGlobals();
  window.sessionStorage.clear();
  document.cookie = "XSRF-TOKEN=; path=/; max-age=0";
});

const EXPENSE = {
  apiName: "Expense__c",
  label: "Expense",
  pluralLabel: "Expenses",
  description: "",
  kind: "CUSTOM",
  editable: true,
  extensible: true,
  version: 1,
  fields: ORDER_FIELDS,
};

const CHOICES = {
  profiles: [{ id: "p-1", name: "Finance profile" }],
  roles: [{ id: "r-1", name: "Finance role" }],
  groups: [{ id: "g-1", name: "Finance group" }],
};

function answers(extra: Record<string, Handler> = {}): Record<string, Handler> {
  return {
    "GET /api/v1/metadata/approvals/catalogue": () => json({ data: CATALOGUE }),
    "GET /api/v1/metadata/approvals/choices": () => json({ data: CHOICES }),
    "GET /api/v1/metadata/objects/Expense__c": () => json({ data: EXPENSE }),
    "GET /api/v1/metadata/field-types": () => json({ data: FIELD_TYPES }),
    "GET /api/v1/metadata/approvals/Expense__c/Large__c": () => json({ data: PROCESS }),
    ...extra,
  };
}

function palette() {
  return within(screen.getByTestId("palette"));
}

function stepCards() {
  return screen.getAllByTestId("approval-step");
}

async function loaded() {
  await waitFor(() => expect(screen.queryAllByTestId("approval-step").length).toBeGreaterThan(0));
}

describe("ApprovalBuilder", () => {
  it("shows the process the API gave: its label, its criteria and its steps in order, under React strict mode", async () => {
    fakeApi(answers());

    render(
      <StrictMode>
        <ApprovalBuilder />
      </StrictMode>,
    );
    await loaded();

    expect(screen.getByLabelText("Label")).toHaveValue("Large expense");
    expect(screen.getByLabelText("Entry criteria")).toHaveValue("amount__c > 100");
    expect(stepCards()).toHaveLength(2);
    expect(within(stepCards()[0] as HTMLElement).getByLabelText(/Label/)).toHaveValue("Manager");
    expect(screen.queryByTestId("unsaved")).toBeNull();
  });

  it("builds the palette from the catalogue and from nothing else: a step chip, and one chip per kind of rule under its own tab", async () => {
    fakeApi(answers());
    render(<ApprovalBuilder />);
    await loaded();

    expect(palette().getByRole("button", { name: "A step" })).toBeInTheDocument();
    fireEvent.click(palette().getByRole("button", { name: "Who is asked" }));
    expect(within(palette().getByTestId("palette-grid")).getAllByRole("button").map((chip) => chip.textContent))
      .toEqual(["User", "Group", "Owner", "Record user"]);
  });

  it("changes with the catalogue: a kind the API adds is in the palette and a kind it drops is not", async () => {
    const changed = {
      ...CATALOGUE,
      approverKinds: [
        ...CATALOGUE.approverKinds.slice(0, 1),
        { kind: "BRAND_NEW", summary: "Something new", valueRefersTo: "NOTHING" },
      ],
    };
    fakeApi(answers({ "GET /api/v1/metadata/approvals/catalogue": () => json({ data: changed }) }));
    render(<ApprovalBuilder />);
    await loaded();
    fireEvent.click(palette().getByRole("button", { name: "Who is asked" }));

    expect(palette().getByRole("button", { name: "Brand new" })).toBeInTheDocument();
    expect(palette().queryByRole("button", { name: "Owner" })).toBeNull();
  });

  it("adds a step from the palette with the keyboard and marks the draft as unsaved", async () => {
    fakeApi(answers());
    render(<ApprovalBuilder />);
    await loaded();

    fireEvent.keyDown(palette().getByRole("button", { name: "A step" }), { key: "Enter" });

    expect(stepCards()).toHaveLength(3);
    expect(screen.getByTestId("unsaved")).toBeInTheDocument();
  });

  it("says in hints what a step still needs and never decides", async () => {
    fakeApi(answers());
    render(<ApprovalBuilder />);
    await loaded();

    fireEvent.click(palette().getByRole("button", { name: "A step" }));

    expect(screen.getByTestId("steps-unfinished")).toBeInTheDocument();
  });

  it("saves an existing process with the version it read and the steps as the API's shape", async () => {
    const calls = fakeApi(answers({ "PUT /api/v1/metadata/approvals/Expense__c/Large__c": () => json({ data: PROCESS }) }));
    render(<ApprovalBuilder />);
    await loaded();

    fireEvent.change(screen.getByLabelText("Label"), { target: { value: "Large expenses" } });
    fireEvent.click(screen.getByRole("button", { name: "Save the process" }));

    expect(await screen.findByTestId("approval-builder-notice")).toHaveTextContent("The approval process was saved.");
    const sent = JSON.parse(
      calls.find((call) => call.key === "PUT /api/v1/metadata/approvals/Expense__c/Large__c")?.body ?? "{}",
    );
    expect(sent).toMatchObject({ label: "Large expenses", active: true, version: 5, entryCriteria: "amount__c > 100" });
    expect(sent.steps.map((step: { name: string }) => step.name)).toEqual(["Manager", "Finance"]);
    expect(sent.steps[0].approvers).toEqual([{ kind: "OWNER" }]);
    expect(sent.outcomes.approved).toEqual([{ field: "status__c", value: "Approved" }]);
  });

  it("offers a checkbox to skip a step when nobody can decide it, explains it, and sends it with the step", async () => {
    const calls = fakeApi(answers({ "PUT /api/v1/metadata/approvals/Expense__c/Large__c": () => json({ data: PROCESS }) }));
    render(<ApprovalBuilder />);
    await loaded();

    const finance = within(stepCards()[1]!);
    const box = finance.getByLabelText(/Skip this step when nobody can decide it/);
    expect(box).not.toBeChecked();
    expect(finance.getByText(/fallback approver is asked first/)).toBeInTheDocument();
    fireEvent.click(box);
    expect(box).toBeChecked();
    expect(screen.getByTestId("unsaved")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Save the process" }));

    expect(await screen.findByTestId("approval-builder-notice")).toBeInTheDocument();
    const sent = JSON.parse(
      calls.find((call) => call.key === "PUT /api/v1/metadata/approvals/Expense__c/Large__c")?.body ?? "{}",
    );
    expect(sent.steps.map((step: { skipWhenNobody: boolean }) => step.skipWhenNobody)).toEqual([false, true]);
  });

  it("shows a checked box for a step the process says may be skipped", async () => {
    const view = { ...PROCESS, steps: [PROCESS.steps[0]!, { ...PROCESS.steps[1]!, skipWhenNobody: true }] };
    fakeApi(answers({ "GET /api/v1/metadata/approvals/Expense__c/Large__c": () => json({ data: view }) }));
    render(<ApprovalBuilder />);
    await loaded();

    expect(within(stepCards()[0]!).getByLabelText(/Skip this step/)).not.toBeChecked();
    expect(within(stepCards()[1]!).getByLabelText(/Skip this step/)).toBeChecked();
  });

  it("makes a new process with the name typed and goes to it", async () => {
    state.process = "new";
    const calls = fakeApi(
      answers({
        "POST /api/v1/metadata/approvals/Expense__c": () => json({ data: { ...PROCESS, apiName: "Fresh__c" } }, 201),
      }),
    );
    render(<ApprovalBuilder />);
    await waitFor(() => expect(screen.getByLabelText("Name")).toBeInTheDocument());

    fireEvent.change(screen.getByLabelText("Name"), { target: { value: "Fresh" } });
    fireEvent.change(screen.getByLabelText("Label"), { target: { value: "Fresh" } });
    fireEvent.click(screen.getByRole("button", { name: "Add the process" }));

    await waitFor(() => expect(state.replace).toHaveBeenCalledWith("/setup/approvals/Expense__c/Fresh__c"));
    expect(JSON.parse(calls.find((call) => call.key === "POST /api/v1/metadata/approvals/Expense__c")?.body ?? "{}"))
      .toMatchObject({ name: "Fresh", label: "Fresh", active: true, steps: [] });
  });

  it("puts a change into the chosen change set instead of making it live", async () => {
    window.sessionStorage.setItem("platform.workingChangeSet", "set-1");
    const calls = fakeApi(
      answers({ "POST /api/v1/metadata/change-sets/set-1/changes": () => json({ data: {} }, 201) }),
    );
    render(<ApprovalBuilder />);
    await loaded();

    fireEvent.change(screen.getByLabelText("Label"), { target: { value: "Drafted" } });
    fireEvent.click(screen.getByRole("button", { name: "Save the process" }));

    expect(await screen.findByTestId("approval-builder-notice")).toHaveTextContent("added to the change set");
    expect(calls.some((call) => call.key === "PUT /api/v1/metadata/approvals/Expense__c/Large__c")).toBe(false);
    expect(JSON.parse(calls.find((call) => call.key === "POST /api/v1/metadata/change-sets/set-1/changes")?.body ?? "{}"))
      .toMatchObject({ kind: "UPDATE_APPROVAL_PROCESS", objectApiName: "Expense__c", itemApiName: "Large__c" });
  });

  it("shows the API's words and the place when the process is refused", async () => {
    fakeApi(
      answers({
        "PUT /api/v1/metadata/approvals/Expense__c/Large__c": () =>
          json(
            {
              error: {
                code: "VALIDATION_ERROR",
                message: "The approval process is not valid.",
                fields: { "steps[0].approvers": ["Say who is asked."] },
              },
            },
            400,
          ),
      }),
    );
    render(<ApprovalBuilder />);
    await loaded();

    fireEvent.change(screen.getByLabelText("Label"), { target: { value: "Other" } });
    fireEvent.click(screen.getByRole("button", { name: "Save the process" }));

    expect(await screen.findByTestId("approval-builder-problem")).toHaveTextContent("Say who is asked.");
  });

  it("tries the process on the object and shows the API's answer by place, storing nothing", async () => {
    const calls = fakeApi(
      answers({
        "POST /api/v1/metadata/approvals/Expense__c/check": () =>
          json({ data: { outcome: "RUNS", problems: { "steps[0].approvers[0].value": ["This field does not exist."] } } }),
      }),
    );
    render(<ApprovalBuilder />);
    await loaded();

    fireEvent.click(screen.getByRole("button", { name: "Check the process" }));

    await waitFor(() =>
      expect(calls.some((call) => call.key === "POST /api/v1/metadata/approvals/Expense__c/check")).toBe(true),
    );
    expect(await screen.findByText(/This field does not exist\./)).toBeInTheDocument();
  });

  it("shows the API's words when the process cannot be read", async () => {
    fakeApi(
      answers({
        "GET /api/v1/metadata/approvals/Expense__c/Large__c": () => refusal("NOT_FOUND", "This approval process does not exist.", 404),
      }),
    );
    render(<ApprovalBuilder />);

    expect(await screen.findByTestId("approval-builder-failed")).toHaveTextContent("does not exist");
  });
});
