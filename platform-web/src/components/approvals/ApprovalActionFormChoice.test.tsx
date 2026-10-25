import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { REQUEST_ID } from "@/test/approvals";
import { fakeApi, json } from "@/test/fakeApi";
import { ApprovalActionForm } from "./ApprovalActionForm";

const TEMPLATE = "0198f5c0-0000-7000-8000-000000000042";
const APPROVE = `POST /api/v1/approvals/instances/${REQUEST_ID}/approve`;
const REJECT = `POST /api/v1/approvals/instances/${REQUEST_ID}/reject`;
const OPTIONS = `GET /api/v1/templates/${TEMPLATE}/suggestion-options`;
const LISTS = {
  data: {
    locations: [
      {
        value: "PLANT1",
        label: "Plant 1",
        departments: [{ value: "PRD", label: "Production", configured: true }],
      },
      { value: "HEAD", label: "Head office", departments: [] },
    ],
  },
};

beforeEach(() => {
  document.cookie = "XSRF-TOKEN=test-token; path=/";
});

afterEach(() => {
  vi.unstubAllGlobals();
  document.cookie = "XSRF-TOKEN=; path=/; max-age=0";
});

function form(kind: "approve" | "reject", object = "InspectionTemplate", onDone = vi.fn()) {
  render(
    <ApprovalActionForm
      kind={kind}
      requestId={REQUEST_ID}
      version={3}
      explain="This is what will happen."
      asksForChoice
      objectApiName={object}
      recordId={TEMPLATE}
      onDone={onDone}
      onClose={vi.fn()}
    />,
  );
  return { onDone };
}

describe("an approval at a step that asks for a choice", () => {
  it("cannot be pressed until a location is chosen, and sends the choice with the approval", async () => {
    const calls = fakeApi({ [OPTIONS]: () => json(LISTS), [APPROVE]: () => json({ data: {} }) });
    const { onDone } = form("approve");

    await screen.findByText("Plant 1");
    expect(screen.getByRole("button", { name: "Approve" })).toBeDisabled();
    fireEvent.click(screen.getByRole("checkbox", { name: "Plant 1" }));
    expect(screen.getByRole("button", { name: "Approve" })).toBeEnabled();
    fireEvent.click(screen.getByRole("checkbox", { name: "Production" }));
    fireEvent.click(screen.getByRole("button", { name: "Approve" }));

    await waitFor(() => expect(onDone).toHaveBeenCalledWith("Approve: done."));
    expect(JSON.parse(calls.find((call) => call.key === APPROVE)?.body ?? "{}")).toEqual({
      version: 3,
      choices: [
        { key: "location", value: "PLANT1" },
        { key: "department", value: "PLANT1/PRD" },
      ],
    });
  });

  it("sends a location alone when no department is ticked, because a department is optional", async () => {
    const calls = fakeApi({ [OPTIONS]: () => json(LISTS), [APPROVE]: () => json({ data: {} }) });
    const { onDone } = form("approve");

    await screen.findByText("Head office");
    fireEvent.click(screen.getByRole("checkbox", { name: "Head office" }));
    fireEvent.click(screen.getByRole("button", { name: "Approve" }));

    await waitFor(() => expect(onDone).toHaveBeenCalled());
    expect(JSON.parse(calls.find((call) => call.key === APPROVE)?.body ?? "{}").choices).toEqual([
      { key: "location", value: "HEAD" },
    ]);
  });

  it("goes back to disabled when the location is unticked", async () => {
    fakeApi({ [OPTIONS]: () => json(LISTS) });
    form("approve");

    await screen.findByText("Plant 1");
    const plant = screen.getByRole("checkbox", { name: "Plant 1" });
    fireEvent.click(plant);
    fireEvent.click(plant);
    expect(screen.getByRole("button", { name: "Approve" })).toBeDisabled();
  });

  it("asks for nothing when rejecting at the same step", async () => {
    const calls = fakeApi({ [REJECT]: () => json({ data: {} }) });
    const { onDone } = form("reject");

    expect(screen.queryByTestId("audience-choice")).not.toBeInTheDocument();
    fireEvent.change(screen.getByLabelText(/Comment \(required\)/), { target: { value: "No" } });
    fireEvent.click(screen.getByRole("button", { name: "Reject" }));

    await waitFor(() => expect(onDone).toHaveBeenCalled());
    expect(JSON.parse(calls.find((call) => call.key === REJECT)?.body ?? "{}")).toEqual({ version: 3, comment: "No" });
  });

  it("will not let a screen approve a step whose choice it has no form for", () => {
    fakeApi();
    form("approve", "Expense__c");

    expect(screen.getByTestId("approval-choice-unavailable")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Approve" })).toBeDisabled();
  });
});
