import { configure, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { fakeApi, json, refusal, type Handler } from "@/test/fakeApi";
import { setUrl } from "@/test/navigation";
import { FILL_ID, newServer, reviewerFill, TEMPLATE_ID } from "@/test/reviewerFill";
import { sizeTheGrid } from "@/test/virtualDom";
import { FillInDetail } from "./FillInDetail";

vi.mock("next/navigation", async () => (await import("@/test/navigation")).navigationMock);

sizeTheGrid();
configure({ asyncUtilTimeout: 5000 });

/**
 * The stamp of a fill-in on its page (SH5.2): the template's number, the Revision the fill-in is pinned to and, only when the
 * server says the application's footer carries it and the Revision is the one in use, the publish day. When the day is not
 * known the line is left out; it is never guessed.
 */

const RECORDS = "/api/v1/objects/InspectionFill/records";
const NO_APPROVAL = {
  data: {
    available: false,
    locked: false,
    processes: [],
    actions: {
      canApprove: false,
      canAssign: false,
      canCancel: false,
      canReapply: false,
      canReassign: false,
      canRecall: false,
      canReject: false,
      canSubmit: false,
    },
  },
};

beforeEach(() => {
  setUrl(`/apps/inspection/s-fill-ins?open=${FILL_ID}&tab=sheet`);
});

afterEach(() => {
  vi.unstubAllGlobals();
});

function lifecycle(more: Record<string, unknown> = {}, activeVersion = 3) {
  return {
    data: {
      footerDate: true,
      lastRevisedAt: "2026-11-13T10:15:00Z",
      lifecycle: { activeVersion, retiredVersion: 0, state: "ACTIVE" },
      ...more,
    },
  };
}

function page(answer: Handler) {
  fakeApi({
    ...reviewerFill(newServer(), { editAs: null, canEdit: false, fill: { submittedAt: "2026-11-17T10:00:00Z", submittedBy: "Plant head 1" } }),
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
          fields: { name: "Fire exit template", number: "F/MC/10" },
          version: 1,
          createdAt: "2026-10-23T08:00:00Z",
          updatedAt: "2026-10-23T08:00:00Z",
        },
      }),
    [`GET ${RECORDS}/${FILL_ID}/approval`]: () => json(NO_APPROVAL),
    [`GET ${RECORDS}/${FILL_ID}/files`]: () => json({ data: [] }),
    [`POST ${RECORDS}/approval-history`]: () => json({ data: [] }),
    [`GET /api/v1/templates/${TEMPLATE_ID}/lifecycle`]: answer,
  });
}

describe("the stamp on the page of a fill-in", () => {
  it("shows the number, the Revision, the publish day and who sent it, as the template's own page writes them", async () => {
    page(() => json(lifecycle()));
    render(<FillInDetail id={FILL_ID} onBack={() => undefined} />);

    expect(await screen.findByText("Publish date: 13-Nov-2026")).toBeInTheDocument();
    expect(screen.getByTestId("sheet-footer")).toHaveTextContent("F/MC/10 Rev No.'02'");
    expect(screen.getByTestId("sheet-footer-3")).toHaveTextContent("Submitted by Plant head 1 on 17-Nov-2026");
  });

  it("leaves the publish day out when the application's footer does not carry it", async () => {
    page(() => json(lifecycle({ footerDate: false })));
    render(<FillInDetail id={FILL_ID} onBack={() => undefined} />);

    expect(await screen.findByTestId("sheet-footer")).toHaveTextContent("F/MC/10 Rev No.'02'");
    expect(screen.queryByText(/Publish date/)).not.toBeInTheDocument();
    expect(screen.getByTestId("sheet-footer-2")).toHaveTextContent("Submitted by Plant head 1");
  });

  it("leaves the publish day out when the fill-in is on a Revision that is no longer the one in use", async () => {
    page(() => json(lifecycle({}, 4)));
    render(<FillInDetail id={FILL_ID} onBack={() => undefined} />);

    expect(await screen.findByTestId("sheet-footer")).toHaveTextContent("F/MC/10 Rev No.'02'");
    expect(screen.queryByText(/Publish date/)).not.toBeInTheDocument();
  });

  it("leaves the publish day out, and keeps the rest, when the person may not read the template's lifecycle", async () => {
    page(() => refusal("NOT_FOUND", "This template does not exist.", 404));
    render(<FillInDetail id={FILL_ID} onBack={() => undefined} />);

    expect(await screen.findByTestId("sheet-footer")).toHaveTextContent("F/MC/10 Rev No.'02'");
    expect(screen.queryByText(/Publish date/)).not.toBeInTheDocument();
    expect(screen.getByTestId("sheet-footer-2")).toHaveTextContent("Submitted by Plant head 1");
  });
});
