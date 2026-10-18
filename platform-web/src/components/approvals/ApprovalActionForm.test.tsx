import { fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { REQUEST_ID } from "@/test/approvals";
import { fakeApi, json, refusal } from "@/test/fakeApi";
import { ApprovalActionForm } from "./ApprovalActionForm";

beforeEach(() => {
  document.cookie = "XSRF-TOKEN=test-token; path=/";
});

afterEach(() => {
  vi.unstubAllGlobals();
  document.cookie = "XSRF-TOKEN=; path=/; max-age=0";
});

const APPROVE = `POST /api/v1/approvals/instances/${REQUEST_ID}/approve`;
const REJECT = `POST /api/v1/approvals/instances/${REQUEST_ID}/reject`;

function form(kind: "approve" | "reject" | "recall", onDone = vi.fn(), onClose = vi.fn()) {
  render(
    <ApprovalActionForm
      kind={kind}
      requestId={REQUEST_ID}
      version={3}
      explain="This is what will happen."
      onDone={onDone}
      onClose={onClose}
    />,
  );
  return { onDone, onClose };
}

describe("ApprovalActionForm", () => {
  it("says what will happen first and keeps the decision away until the person goes on", () => {
    form("approve");

    expect(screen.getByTestId("approval-explain")).toHaveTextContent("This is what will happen.");
    expect(screen.getByRole("button", { name: "Approve" })).toBeEnabled();
  });

  it("sends the version it read with the comment, trimmed, and reports success", async () => {
    const calls = fakeApi({ [APPROVE]: () => json({ data: {} }) });
    const { onDone } = form("approve");

    fireEvent.change(screen.getByLabelText(/Comment/), { target: { value: "  Looks fine  " } });
    fireEvent.click(screen.getByRole("button", { name: "Approve" }));

    await vi.waitFor(() => expect(onDone).toHaveBeenCalledWith("Approve: done."));
    expect(JSON.parse(calls.find((call) => call.key === APPROVE)?.body ?? "{}")).toEqual({
      version: 3,
      comment: "Looks fine",
    });
  });

  it("will not reject without a reason", async () => {
    const calls = fakeApi({ [REJECT]: () => json({ data: {} }) });
    const { onDone } = form("reject");

    expect(screen.getByRole("button", { name: "Reject" })).toBeDisabled();
    fireEvent.change(screen.getByLabelText(/Comment \(required\)/), { target: { value: "Too expensive" } });
    expect(screen.getByRole("button", { name: "Reject" })).toBeEnabled();
    fireEvent.click(screen.getByRole("button", { name: "Reject" }));

    await vi.waitFor(() => expect(onDone).toHaveBeenCalled());
    expect(JSON.parse(calls.find((call) => call.key === REJECT)?.body ?? "{}")).toEqual({
      version: 3,
      comment: "Too expensive",
    });
  });

  it("shows the API's words when somebody else decided first and does not close", async () => {
    fakeApi({ [APPROVE]: () => refusal("CONCURRENT_MODIFICATION", "This was changed by someone else.", 409) });
    const { onDone } = form("approve");

    fireEvent.click(screen.getByRole("button", { name: "Approve" }));

    expect(await screen.findByTestId("approval-action-failed")).toHaveTextContent("changed by someone else");
    expect(onDone).not.toHaveBeenCalled();
  });

  it("can be put away without doing anything", () => {
    const calls = fakeApi();
    const { onClose } = form("recall");

    fireEvent.click(screen.getByRole("button", { name: "Keep it as it is" }));

    expect(onClose).toHaveBeenCalled();
    expect(calls).toHaveLength(0);
  });
});
