import { configure, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NO_ACTIONS, REQUEST_ID, summary as requestSummary } from "@/test/approvals";
import { fakeApi, json, refusal, type Call, type Handler } from "@/test/fakeApi";
import { setUrl } from "@/test/navigation";
import {
  FILL_ID,
  FILL_URL,
  newServer,
  reviewerFill,
  TEMPLATE_ID,
  type ReviewerServer,
} from "@/test/reviewerFill";
import { sizeTheGrid } from "@/test/virtualDom";
import { FillInDetail } from "./FillInDetail";

vi.mock("next/navigation", async () => (await import("@/test/navigation")).navigationMock);

sizeTheGrid();
configure({ asyncUtilTimeout: 5000 });

/**
 * A reviewer decides a fill-in on its page (SH5.1). The score is worked out at approval from the cells that are SAVED, so a
 * decision must first save what the reviewer typed, through the same queue as the Save button, and send nothing when that
 * save is refused. One decision makes at most one counted review edit.
 */

const RECORDS = "/api/v1/objects/InspectionFill/records";
const APPROVE = `POST /api/v1/approvals/instances/${REQUEST_ID}/approve`;
const REJECT = `POST /api/v1/approvals/instances/${REQUEST_ID}/reject`;
const SAVE = `PUT ${FILL_URL}/cells`;

beforeEach(() => {
  setUrl(`/apps/inspection/s-fill-ins?open=${FILL_ID}&tab=sheet`);
});

afterEach(() => {
  vi.unstubAllGlobals();
});

function pending(): { data: unknown } {
  const actions = { ...NO_ACTIONS, canApprove: true, canReject: true };
  return {
    data: {
      available: true,
      locked: true,
      processes: [],
      actions,
      current: {
        summary: requestSummary({ objectApiName: "InspectionFill", recordId: FILL_ID }),
        steps: [],
        actions,
        historyVisible: false,
        history: [],
      },
    },
  };
}

function page(server: ReviewerServer, more: Record<string, Handler> = {}): Call[] {
  return fakeApi({
    ...reviewerFill(server),
    [`GET ${RECORDS}/${FILL_ID}`]: () =>
      json({
        data: {
          id: FILL_ID,
          objectApiName: "InspectionFill",
          ownerId: "0198f5c0-0000-7000-8000-000000000001",
          fields: { name: "Fire exit check", templateId: TEMPLATE_ID, status: "SUBMITTED" },
          version: 2,
          createdAt: "2026-10-23T08:00:00Z",
          updatedAt: "2026-10-23T08:00:00Z",
        },
      }),
    [`GET /api/v1/objects/InspectionTemplate/records/${TEMPLATE_ID}`]: () =>
      json({
        data: {
          id: TEMPLATE_ID,
          objectApiName: "InspectionTemplate",
          ownerId: "0198f5c0-0000-7000-8000-000000000001",
          fields: { name: "Fire exit template", number: "WI/PRD/03" },
          version: 1,
          createdAt: "2026-10-23T08:00:00Z",
          updatedAt: "2026-10-23T08:00:00Z",
        },
      }),
    [`GET ${RECORDS}/${FILL_ID}/approval`]: () => json(pending()),
    [`GET ${RECORDS}/${FILL_ID}/files`]: () => json({ data: [] }),
    [`POST ${RECORDS}/approval-history`]: () => json({ data: [] }),
    [APPROVE]: () => json({ data: {} }),
    [REJECT]: () => json({ data: {} }),
    ...more,
  });
}

/** Types a mark into the first reviewer cell of the grid and leaves the cell, without saving. */
async function typeFirstMark(text: string) {
  const grid = await screen.findByRole("grid");
  const cell = grid.ownerDocument.querySelector('[data-r="1"][data-c="1"]') as HTMLElement;
  fireEvent.doubleClick(cell);
  const box = await screen.findByRole("textbox", { name: "Mark, First" });
  fireEvent.change(box, { target: { value: text } });
  fireEvent.keyDown(box, { key: "Enter" });
  await screen.findByText("Unsaved changes");
}

async function openApproval(button: string) {
  fireEvent.click(await screen.findByRole("button", { name: button }));
  return screen.findByRole("form", { name: button });
}

const order = (calls: Call[]) => calls.map((call) => call.key);

describe("a decision on a fill-in with cells that were typed and not saved", () => {
  it("saves the cells first, through the queue of the Save button, and then sends the approval", async () => {
    const server = newServer();
    const calls = page(server);
    render(<FillInDetail id={FILL_ID} onBack={() => undefined} />);
    await typeFirstMark("5");

    const form = await openApproval("Approve");
    fireEvent.click(within(form).getByRole("button", { name: "Approve" }));

    await waitFor(() => expect(order(calls)).toContain(APPROVE));
    expect(order(calls).filter((key) => key === SAVE)).toHaveLength(1);
    expect(order(calls).indexOf(SAVE)).toBeLessThan(order(calls).indexOf(APPROVE));
    expect(server.cells).toEqual({ "r1:c2": "5" });
    // The save is the reviewer's: one decision makes one counted edit and nothing more.
    expect(server.saves).toBe(1);
  });

  it("saves the cells first when the reviewer rejects, too", async () => {
    const server = newServer();
    const calls = page(server);
    render(<FillInDetail id={FILL_ID} onBack={() => undefined} />);
    await typeFirstMark("3");

    const form = await openApproval("Reject");
    fireEvent.change(within(form).getByRole("textbox", { name: /Comment/ }), { target: { value: "Not enough" } });
    fireEvent.click(within(form).getByRole("button", { name: "Reject" }));

    await waitFor(() => expect(order(calls)).toContain(REJECT));
    expect(order(calls).indexOf(SAVE)).toBeLessThan(order(calls).indexOf(REJECT));
    expect(server.cells).toEqual({ "r1:c2": "3" });
  });

  it("sends no decision when the save is refused, shows why, and keeps what was typed", async () => {
    const server = newServer();
    server.refuse = "The review window is closed.";
    const calls = page(server);
    render(<FillInDetail id={FILL_ID} onBack={() => undefined} />);
    await typeFirstMark("5");

    const form = await openApproval("Approve");
    fireEvent.click(within(form).getByRole("button", { name: "Approve" }));

    expect(await within(form).findByTestId("approval-action-failed")).toHaveTextContent("The review window is closed.");
    expect(await screen.findByTestId("save-refused")).toHaveTextContent("The review window is closed.");
    expect(order(calls)).not.toContain(APPROVE);
    expect(screen.getByText("Unsaved changes")).toBeInTheDocument();
    expect(form).toBeInTheDocument();
  });

  it("saves nothing when nothing was typed, and sends the decision at once", async () => {
    const server = newServer();
    const calls = page(server);
    render(<FillInDetail id={FILL_ID} onBack={() => undefined} />);
    await screen.findByRole("grid");

    const form = await openApproval("Approve");
    fireEvent.click(within(form).getByRole("button", { name: "Approve" }));

    await waitFor(() => expect(order(calls)).toContain(APPROVE));
    expect(order(calls)).not.toContain(SAVE);
    expect(server.saves).toBe(0);
  });

  it("sends the decision at once when the sheet was never opened, because nothing there can be unsaved", async () => {
    setUrl(`/apps/inspection/s-fill-ins?open=${FILL_ID}`);
    const calls = page(newServer());
    render(<FillInDetail id={FILL_ID} onBack={() => undefined} />);

    const form = await openApproval("Approve");
    fireEvent.click(within(form).getByRole("button", { name: "Approve" }));

    await waitFor(() => expect(order(calls)).toContain(APPROVE));
    expect(order(calls)).not.toContain(SAVE);
  });

  it("shows the API's words when the decision itself is refused after a good save", async () => {
    const server = newServer();
    page(server, { [APPROVE]: () => refusal("CONFLICT", "Someone decided first.", 409) });
    render(<FillInDetail id={FILL_ID} onBack={() => undefined} />);
    await typeFirstMark("5");

    const form = await openApproval("Approve");
    fireEvent.click(within(form).getByRole("button", { name: "Approve" }));

    expect(await within(form).findByTestId("approval-action-failed")).toHaveTextContent("Someone decided first.");
    expect(server.cells).toEqual({ "r1:c2": "5" });
  });
});

describe("the reviewer's buttons on a fill-in made for a plant (one step, 2026-11-17)", () => {
  const forPlant = {
    [`GET ${RECORDS}/${FILL_ID}`]: () =>
      json({
        data: {
          id: FILL_ID,
          objectApiName: "InspectionFill",
          ownerId: "0198f5c0-0000-7000-8000-000000000001",
          fields: { name: "Plant activity", templateId: TEMPLATE_ID, status: "SUBMITTED", plant: "PLANT1", period: "2026-11" },
          version: 2,
          createdAt: "2026-10-23T08:00:00Z",
          updatedAt: "2026-10-23T08:00:00Z",
        },
      }),
  };

  it("say Submit and Return, and Submit sends the approval that finishes the fill-in", async () => {
    setUrl(`/apps/management-core/s-fill-ins?open=${FILL_ID}`);
    const calls = page(newServer(), forPlant);
    render(<FillInDetail id={FILL_ID} onBack={() => undefined} />);

    expect(await screen.findByRole("button", { name: "Return" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Approve" })).toBeNull();
    const form = await openApproval("Submit");
    fireEvent.click(within(form).getByRole("button", { name: "Submit" }));

    await waitFor(() => expect(order(calls)).toContain(APPROVE));
  });

  it("keep Approve and Reject on a fill-in that is not made for a plant", async () => {
    setUrl(`/apps/inspection/s-fill-ins?open=${FILL_ID}`);
    page(newServer());
    render(<FillInDetail id={FILL_ID} onBack={() => undefined} />);

    expect(await screen.findByRole("button", { name: "Approve" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Reject" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Submit" })).toBeNull();
  });
});
