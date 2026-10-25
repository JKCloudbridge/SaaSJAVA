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
  it("cannot be pressed until a location is added, and sends the choice with the approval", async () => {
    const calls = fakeApi({ [OPTIONS]: () => json(LISTS), [APPROVE]: () => json({ data: {} }) });
    const { onDone } = form("approve");

    fireEvent.change(await screen.findByLabelText("Location"), { target: { value: "PLANT1" } });
    expect(screen.getByRole("button", { name: "Approve" })).toBeDisabled();
    fireEvent.click(screen.getByRole("checkbox", { name: "Production" }));
    fireEvent.click(screen.getByRole("button", { name: "Add the ticked departments" }));
    expect(screen.getByText("Plant 1 · Production")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Approve" })).toBeEnabled();
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

  it("adds a location alone when no department is ticked, because a department is optional", async () => {
    const calls = fakeApi({ [OPTIONS]: () => json(LISTS), [APPROVE]: () => json({ data: {} }) });
    const { onDone } = form("approve");

    fireEvent.change(await screen.findByLabelText("Location"), { target: { value: "HEAD" } });
    fireEvent.click(screen.getByRole("button", { name: "Add the location" }));
    expect(screen.getByRole("button", { name: "Remove Head office" })).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Approve" }));

    await waitFor(() => expect(onDone).toHaveBeenCalled());
    expect(JSON.parse(calls.find((call) => call.key === APPROVE)?.body ?? "{}").choices).toEqual([
      { key: "location", value: "HEAD" },
    ]);
  });

  it("lets another location be added, and Remove takes one out; with none left it is disabled again", async () => {
    fakeApi({ [OPTIONS]: () => json(LISTS) });
    form("approve");

    const select = await screen.findByLabelText("Location");
    fireEvent.change(select, { target: { value: "HEAD" } });
    fireEvent.click(screen.getByRole("button", { name: "Add the location" }));
    fireEvent.change(select, { target: { value: "PLANT1" } });
    fireEvent.click(screen.getByRole("button", { name: "Add the location" }));
    expect(screen.getAllByRole("button", { name: /^Remove / })).toHaveLength(2);
    fireEvent.click(screen.getByRole("button", { name: "Remove Head office" }));
    fireEvent.click(screen.getByRole("button", { name: "Remove Plant 1" }));
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

describe("an approval of a change request (FA8)", () => {
  const REVIEW = "0198f5c0-0000-7000-8000-0000000000b1";
  const BY_REVIEW = `GET /api/v1/change-requests/by-review/${REVIEW}`;
  const request = (extra: Record<string, unknown> = {}) => ({
    data: { id: "q-1", templateId: TEMPLATE, title: "x", status: "OPEN", comments: [], ...extra },
  });

  function change(asksForChoice: boolean, lastStep: boolean, onDone = vi.fn()) {
    render(
      <ApprovalActionForm
        kind="approve"
        requestId={REQUEST_ID}
        version={3}
        explain="This is what will happen."
        asksForChoice={asksForChoice}
        lastStep={lastStep}
        objectApiName="InspectionChangeRequest"
        recordId={REVIEW}
        onDone={onDone}
        onClose={vi.fn()}
      />,
    );
    return { onDone };
  }

  it("asks nothing at an ordinary step: the person approves at once", async () => {
    const calls = fakeApi({ [APPROVE]: () => json({ data: {} }) });
    const { onDone } = change(false, false);

    expect(screen.queryByTestId("publish-choice")).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Approve" })).toBeEnabled();
    fireEvent.click(screen.getByRole("button", { name: "Approve" }));

    await waitFor(() => expect(onDone).toHaveBeenCalled());
    expect(JSON.parse(calls.find((call) => call.key === APPROVE)?.body ?? "{}")).toEqual({ version: 3 });
  });

  it("asks for the suggested audience at a marked step that is not the last", async () => {
    fakeApi({ [BY_REVIEW]: () => json(request()), [OPTIONS]: () => json(LISTS) });
    change(true, false);

    expect(await screen.findByTestId("audience-choice")).toBeInTheDocument();
    expect(screen.queryByTestId("publish-choice")).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Approve" })).toBeDisabled();
  });

  it("opens the Publish window at the last step, marked or not, and approving sends the audience and the options", async () => {
    const calls = fakeApi({
      [BY_REVIEW]: () =>
        json(
          request({
            publishPrefill: {
              suggested: [{ location: "PLANT1", department: "PRD" }],
              suggestionDropped: 0,
              viewOnly: true,
              allowDownload: false,
            },
          }),
        ),
      [OPTIONS]: () => json(LISTS),
      [APPROVE]: () => json({ data: {} }),
    });
    const { onDone } = change(false, true);

    expect(await screen.findByTestId("publish-choice")).toBeInTheDocument();
    await waitFor(() => expect(screen.getByRole("button", { name: "Approve" })).toBeEnabled());
    fireEvent.click(screen.getByRole("button", { name: "Approve" }));

    await waitFor(() => expect(onDone).toHaveBeenCalled());
    expect(JSON.parse(calls.find((call) => call.key === APPROVE)?.body ?? "{}").choices).toEqual([
      { key: "location", value: "PLANT1" },
      { key: "department", value: "PLANT1/PRD" },
      { key: "option", value: "viewOnly" },
    ]);
  });

  it("cannot be pressed at the last step until at least one department is chosen", async () => {
    fakeApi({ [BY_REVIEW]: () => json(request()), [OPTIONS]: () => json(LISTS) });
    change(false, true);

    await screen.findByTestId("publish-choice");

    expect(screen.getByRole("button", { name: "Approve" })).toBeDisabled();
  });

  it("shows the server's words when the request cannot be opened", async () => {
    fakeApi({
      [BY_REVIEW]: () => json({ error: { code: "NOT_FOUND", message: "This change request does not exist." } }, 404),
    });
    change(false, true);

    expect(await screen.findByRole("alert")).toHaveTextContent("This change request does not exist.");
    expect(screen.getByRole("button", { name: "Approve" })).toBeDisabled();
  });
});
