import { fireEvent, render, screen, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { LAST_PAGE, NO_ACTIONS, RECORD_ID, REQUEST_ID, task } from "@/test/approvals";
import { fakeApi, json, refusal } from "@/test/fakeApi";
import { Inbox } from "./Inbox";

vi.mock("next/navigation", () => ({ useSearchParams: () => new URLSearchParams() }));
vi.mock("next/link", () => ({
  default: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => (
    <a href={href} {...rest}>
      {children}
    </a>
  ),
}));

beforeEach(() => {
  document.cookie = "XSRF-TOKEN=test-token; path=/";
});

afterEach(() => {
  vi.unstubAllGlobals();
  document.cookie = "XSRF-TOKEN=; path=/; max-age=0";
});

const INBOX = "GET /api/v1/approvals/inbox";
const APPROVE = `POST /api/v1/approvals/instances/${REQUEST_ID}/approve`;
const REJECT = `POST /api/v1/approvals/instances/${REQUEST_ID}/reject`;

describe("Inbox", () => {
  it("lists the tasks with the record named, the step and the process, and links to a readable record", async () => {
    fakeApi({ [INBOX]: () => json({ data: [task()], pagination: LAST_PAGE }) });

    render(<Inbox />);

    const row = await screen.findByTestId("inbox-task");
    expect(within(row).getByRole("link", { name: "Trip to the fair" })).toHaveAttribute(
      "href",
      `/records/Expense__c/${RECORD_ID}`,
    );
    expect(row).toHaveTextContent("Large expense");
    expect(row).toHaveTextContent("Manager (step 1 of 2)");
    expect(within(row).getByRole("button", { name: "Approve" })).toBeInTheDocument();
    expect(within(row).getByRole("button", { name: "Reject" })).toBeInTheDocument();
    expect(within(row).getByRole("button", { name: "Hand it on" })).toBeInTheDocument();
  });

  it("says plainly that a record cannot be opened and names no title for it", async () => {
    fakeApi({
      [INBOX]: () => json({ data: [task({ recordReadable: false, recordTitle: undefined })], pagination: LAST_PAGE }),
    });

    render(<Inbox />);

    expect(await screen.findByTestId("inbox-unreadable")).toHaveTextContent("does not give you access");
    expect(screen.queryByRole("link", { name: "Trip to the fair" })).not.toBeInTheDocument();
  });

  it("offers only the buttons the API says are open", async () => {
    fakeApi({
      [INBOX]: () =>
        json({ data: [task({ actions: { ...NO_ACTIONS, canApprove: true } })], pagination: LAST_PAGE }),
    });

    render(<Inbox />);
    const row = await screen.findByTestId("inbox-task");

    expect(within(row).getByRole("button", { name: "Approve" })).toBeInTheDocument();
    expect(within(row).queryByRole("button", { name: "Reject" })).not.toBeInTheDocument();
    expect(within(row).queryByRole("button", { name: "Hand it on" })).not.toBeInTheDocument();
  });

  it("asks for the tab chosen and says so when nothing is there", async () => {
    const calls = fakeApi({ [INBOX]: () => json({ data: [], pagination: LAST_PAGE }) });
    render(<Inbox />);
    expect(await screen.findByTestId("inbox-empty")).toBeInTheDocument();

    fireEvent.click(screen.getByRole("tab", { name: "Delegated to me" }));

    await vi.waitFor(() => expect(calls.filter((call) => call.key === INBOX).length).toBeGreaterThan(1));
    expect(screen.getByRole("tab", { name: "Delegated to me" })).toHaveAttribute("aria-selected", "true");
  });

  it("approves with a confirmation that says what happens, the version it read, and then reads the list again", async () => {
    let approved = false;
    const calls = fakeApi({
      [INBOX]: () => json({ data: approved ? [] : [task()], pagination: LAST_PAGE }),
      [APPROVE]: () => {
        approved = true;
        return json({ data: {} });
      },
    });
    render(<Inbox />);
    fireEvent.click(within(await screen.findByTestId("inbox-task")).getByRole("button", { name: "Approve" }));

    expect(screen.getByTestId("approval-explain")).toHaveTextContent("step Manager");
    fireEvent.click(within(screen.getByTestId("approval-action-approve")).getByRole("button", { name: "Approve" }));

    expect(await screen.findByTestId("inbox-notice")).toHaveTextContent("done");
    expect(JSON.parse(calls.find((call) => call.key === APPROVE)?.body ?? "{}")).toMatchObject({ version: 3 });
    expect(await screen.findByTestId("inbox-empty")).toBeInTheDocument();
  });

  it("explains an approval at the last step as the end of the request", async () => {
    fakeApi({
      [INBOX]: () =>
        json({
          data: [task({ instance: { ...task().instance, currentStep: 2, stepCount: 2 }, stepLabel: "Finance" })],
          pagination: LAST_PAGE,
        }),
    });
    render(<Inbox />);

    fireEvent.click(within(await screen.findByTestId("inbox-task")).getByRole("button", { name: "Approve" }));

    expect(screen.getByTestId("approval-explain")).toHaveTextContent("last step");
    expect(screen.getByTestId("approval-explain")).toHaveTextContent("unlocked");
  });

  it("will not reject without a reason and shows the API's words when somebody decided first", async () => {
    fakeApi({
      [INBOX]: () => json({ data: [task()], pagination: LAST_PAGE }),
      [REJECT]: () => refusal("CONCURRENT_MODIFICATION", "This was changed by someone else.", 409),
    });
    render(<Inbox />);
    fireEvent.click(within(await screen.findByTestId("inbox-task")).getByRole("button", { name: "Reject" }));

    const confirm = within(screen.getByTestId("approval-action-reject")).getByRole("button", { name: "Reject" });
    expect(confirm).toBeDisabled();
    fireEvent.change(screen.getByLabelText(/Comment/), { target: { value: "No" } });
    fireEvent.click(confirm as HTMLElement);

    expect(await screen.findByTestId("approval-action-failed")).toHaveTextContent("changed by someone else");
  });

  it("offers more when the API has more and shows its words when the list cannot be read", async () => {
    fakeApi({ [INBOX]: () => json({ data: [task()], pagination: { limit: 50, hasMore: true, nextCursor: "n1" } }) });
    const { unmount } = render(<Inbox />);
    expect(await screen.findByTestId("inbox-more")).toBeInTheDocument();
    unmount();

    fakeApi({ [INBOX]: () => refusal("FORBIDDEN", "Approvals are not part of your plan.", 403) });
    render(<Inbox />);
    expect(await screen.findByTestId("inbox-failed")).toHaveTextContent("Approvals are not part of your plan.");
  });
});
