import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { REQUEST_ID } from "@/test/approvals";
import { fakeApi, json } from "@/test/fakeApi";
import { ApprovalActionForm, type ApprovalActionKind, type BeforeDecision } from "./ApprovalActionForm";

/**
 * What a page may do before a decision is sent (SH5.1): the fill-in page saves the cells typed and not yet saved. The form
 * asks the page first for an approval, a rejection and a withdrawal, sends nothing when the page says no, and shows the
 * page's words; it asks nothing of the page for the actions that do not decide anything about the cells.
 */

const PATH = `/api/v1/approvals/instances/${REQUEST_ID}`;

beforeEach(() => {
  document.cookie = "XSRF-TOKEN=test-token; path=/";
});

afterEach(() => {
  vi.unstubAllGlobals();
  document.cookie = "XSRF-TOKEN=; path=/; max-age=0";
});

function form(kind: ApprovalActionKind, beforeDecision?: BeforeDecision) {
  const onDone = vi.fn();
  render(
    <ApprovalActionForm
      kind={kind}
      requestId={REQUEST_ID}
      version={3}
      explain="This is what will happen."
      beforeDecision={beforeDecision}
      onDone={onDone}
      onClose={vi.fn()}
    />,
  );
  return { onDone };
}

function api() {
  return fakeApi({
    [`POST ${PATH}/approve`]: () => json({ data: {} }),
    [`POST ${PATH}/reject`]: () => json({ data: {} }),
    [`POST ${PATH}/recall`]: () => json({ data: {} }),
    [`POST ${PATH}/cancel`]: () => json({ data: {} }),
  });
}

describe("the page's step before a decision", () => {
  it.each([
    ["approve", "Approve"],
    ["recall", "Withdraw the request"],
  ] as const)("is taken first for %s, and the decision is sent after it", async (kind, button) => {
    const order: string[] = [];
    const calls = api();
    const first: BeforeDecision = async () => {
      order.push(`page:${calls.length}`);
      return { ok: true };
    };
    const { onDone } = form(kind, first);

    fireEvent.click(screen.getByRole("button", { name: button }));

    await waitFor(() => expect(onDone).toHaveBeenCalled());
    expect(order).toEqual(["page:0"]);
    expect(calls.map((call) => call.key)).toEqual([`POST ${PATH}/${kind}`]);
  });

  it("is taken first for a rejection as well, after the reason was written", async () => {
    const first = vi.fn<BeforeDecision>(async () => ({ ok: true }));
    const calls = api();
    const { onDone } = form("reject", first);

    fireEvent.change(screen.getByRole("textbox", { name: /Comment/ }), { target: { value: "Not enough" } });
    fireEvent.click(screen.getByRole("button", { name: "Reject" }));

    await waitFor(() => expect(onDone).toHaveBeenCalled());
    expect(first).toHaveBeenCalledTimes(1);
    expect(calls.map((call) => call.key)).toEqual([`POST ${PATH}/reject`]);
  });

  it("sends nothing when the page says no, shows the page's words and lets the person try again", async () => {
    const first = vi.fn<BeforeDecision>(async () => ({ ok: false, message: "Your entries could not be saved." }));
    const calls = api();
    const { onDone } = form("approve", first);

    fireEvent.click(screen.getByRole("button", { name: "Approve" }));

    expect(await screen.findByTestId("approval-action-failed")).toHaveTextContent("Your entries could not be saved.");
    expect(calls).toHaveLength(0);
    expect(onDone).not.toHaveBeenCalled();
    expect(screen.getByRole("button", { name: "Approve" })).toBeEnabled();

    first.mockResolvedValueOnce({ ok: true });
    fireEvent.click(screen.getByRole("button", { name: "Approve" }));
    await waitFor(() => expect(onDone).toHaveBeenCalled());
    expect(first).toHaveBeenCalledTimes(2);
    expect(calls.map((call) => call.key)).toEqual([`POST ${PATH}/approve`]);
  });

  it("says something plain when the page says no without words", async () => {
    api();
    form("approve", async () => ({ ok: false }));

    fireEvent.click(screen.getByRole("button", { name: "Approve" }));

    expect(await screen.findByTestId("approval-action-failed")).toHaveTextContent("so nothing was decided");
  });

  it("is not asked for ending a request as an administrator, which says nothing about the cells", async () => {
    const first = vi.fn<BeforeDecision>(async () => ({ ok: true }));
    const calls = api();
    const { onDone } = form("cancel", first);

    fireEvent.click(screen.getByRole("button", { name: "End the request" }));

    await waitFor(() => expect(onDone).toHaveBeenCalled());
    expect(first).not.toHaveBeenCalled();
    expect(calls.map((call) => call.key)).toEqual([`POST ${PATH}/cancel`]);
  });

  it("changes nothing for a page that has no such step", async () => {
    const calls = api();
    const { onDone } = form("approve");

    fireEvent.click(screen.getByRole("button", { name: "Approve" }));

    await waitFor(() => expect(onDone).toHaveBeenCalled());
    expect(calls.map((call) => call.key)).toEqual([`POST ${PATH}/approve`]);
  });
});
