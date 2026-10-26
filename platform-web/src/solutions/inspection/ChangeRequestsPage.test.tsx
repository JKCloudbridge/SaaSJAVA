import { configure, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { fakeApi, json, refusal, type Handler } from "@/test/fakeApi";
import { currentUrl, setUrl } from "@/test/navigation";
import { ChangeRequestsList, ChangeRequestsPage } from "./ChangeRequestsPage";

// These screens draw a lot; a slow machine needs more than the default second to settle.
configure({ asyncUtilTimeout: 5000 });

vi.mock("next/navigation", async () => (await import("@/test/navigation")).navigationMock);

const ONE = "0198f5c0-0000-7000-8000-0000000000a1";
const TWO = "0198f5c0-0000-7000-8000-0000000000a2";
const TEMPLATE = "0198f5c0-0000-7000-8000-000000000042";

function row(id: string, extra: Record<string, unknown> = {}) {
  return {
    id,
    templateId: TEMPLATE,
    templateName: "Fire exit template",
    templateNumber: "WI/PRD/03",
    title: "Wording of the walkway",
    status: "OPEN",
    versionNo: 3,
    requesterName: "Reader A",
    requesterDepartment: "Plant 1 · Production",
    mine: false,
    createdAt: "2026-10-22T08:00:00Z",
    ...extra,
  };
}

function answers(rows = [row(ONE), row(TWO, { status: "DECLINED", versionNo: 2, requesterName: "Reader B" })]): Record<string, Handler> {
  return { "POST /api/v1/change-requests/query": () => json({ data: { rows } }) };
}

beforeEach(() => {
  setUrl("/apps/inspection/s-change-requests");
});

describe("the list of change requests (FA6E)", () => {
  it("shows the template with its number, who asked, the department, the date and the status, and no version column", async () => {
    fakeApi(answers());

    render(<ChangeRequestsPage />);

    const list = await screen.findByTestId("change-requests-list");
    expect(within(list).getAllByText("Fire exit template")).toHaveLength(2);
    expect(within(list).getAllByText(/WI\/PRD\/03/)).toHaveLength(2);
    for (const header of ["Template", "Change requested by", "Department", "Date", "Status"]) {
      expect(within(list).getByRole("columnheader", { name: new RegExp(header) })).toBeInTheDocument();
    }
    expect(within(list).queryByRole("columnheader", { name: /Revision|Version/ })).toBeNull();
    expect(within(list).getByText("Reader A")).toBeInTheDocument();
    expect(within(list).getAllByText("Plant 1 · Production")).toHaveLength(2);
    expect(within(list).getByText("In progress")).toBeInTheDocument();
    expect(within(list).getByText("Declined")).toBeInTheDocument();
  });

  it("asks newest first, and sends the search text and the chosen order to the server", async () => {
    const calls = fakeApi(answers());

    render(<ChangeRequestsPage />);
    await screen.findByTestId("change-requests-list");
    expect(JSON.parse(calls.find((one) => one.key === "POST /api/v1/change-requests/query")?.body ?? "{}")).toMatchObject({
      sort: "date",
      descending: true,
    });

    fireEvent.click(screen.getByRole("button", { name: /^Status, not ordered/ }));
    await waitFor(() => {
      const bodies = calls.filter((one) => one.key === "POST /api/v1/change-requests/query").map((one) => JSON.parse(one.body));
      expect(bodies.at(-1)).toMatchObject({ sort: "status", descending: true });
    });

    fireEvent.change(screen.getByRole("searchbox"), { target: { value: "walkway" } });
    await waitFor(() => {
      const bodies = calls.filter((one) => one.key === "POST /api/v1/change-requests/query").map((one) => JSON.parse(one.body));
      expect(bodies.at(-1)).toMatchObject({ search: "walkway" });
    });
  });

  it("opens a row on the same page and continues from the cursor of the last row seen", async () => {
    const cursors: (string | null)[] = [];
    fakeApi({
      "POST /api/v1/change-requests/query": (request) =>
        request.text().then((body) => {
          const cursor = (JSON.parse(body) as { cursor?: string }).cursor ?? null;
          cursors.push(cursor);
          return json(cursor ? { data: { rows: [row(TWO)] } } : { data: { rows: [row(ONE)], nextCursor: "after-one" } });
        }),
    });

    render(<ChangeRequestsPage />);
    await screen.findByText("Show more");
    fireEvent.click(screen.getByRole("button", { name: "Show more" }));
    await waitFor(() => expect(within(screen.getByTestId("change-requests-list")).getAllByRole("row")).toHaveLength(3));
    expect(cursors).toEqual([null, "after-one"]);

    fireEvent.click(within(screen.getByTestId("change-requests-list")).getAllByText("Fire exit template")[0] as HTMLElement);
    expect(currentUrl()).toContain(`open=${ONE}`);
  });

  it("says so in the API's words when it cannot be loaded, and has nothing to offer as New", async () => {
    fakeApi({ "POST /api/v1/change-requests/query": () => refusal("FORBIDDEN", "You may not see these.", 403) });

    render(<ChangeRequestsPage />);

    expect(await screen.findByText("You may not see these.")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /^New/ })).toBeNull();
  });

  it("explains an empty list", async () => {
    fakeApi(answers([]));

    render(<ChangeRequestsPage />);

    expect(await screen.findByText(/There are no change requests you can see yet/)).toBeInTheDocument();
  });
});

describe("the Change requests tab of one template (FA6E)", () => {
  it("asks only for that template, shows the request first and opens a row through the template address", async () => {
    const calls = fakeApi(answers());
    const opened: string[] = [];

    render(<ChangeRequestsList templateId={TEMPLATE} onOpen={(id) => opened.push(id)} onClose={() => undefined} />);

    const list = await screen.findByTestId("change-requests-list-embedded");
    const body = JSON.parse(calls.find((one) => one.key === "POST /api/v1/change-requests/query")?.body ?? "{}");
    expect(body).toMatchObject({ templateId: TEMPLATE });
    expect(within(list).queryByText("Fire exit template")).toBeNull();
    expect(within(list).getAllByText("Wording of the walkway")).toHaveLength(2);
    fireEvent.click(within(list).getAllByText("Wording of the walkway")[0] as HTMLElement);
    expect(opened).toEqual([ONE]);
  });
});
