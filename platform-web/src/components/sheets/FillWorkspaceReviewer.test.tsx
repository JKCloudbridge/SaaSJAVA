import { configure, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { fakeApi } from "@/test/fakeApi";
import { FILL_OWNER, newServer, reviewerFill, type ReviewerServer } from "@/test/reviewerFill";
import { sizeTheGrid } from "@/test/virtualDom";
import { FillWorkspace } from "./FillWorkspace";

vi.mock("next/navigation", async () => (await import("@/test/navigation")).navigationMock);

sizeTheGrid();
configure({ asyncUtilTimeout: 5000 });

/**
 * A reviewer fills the cells meant for the reviewer of a fill-in under review (SH5, SH5.1). Autosave is off for the reviewer
 * on purpose (every save is one counted, audited edit), so the answers of the formulas must be shown while the person types
 * and the server's answers must replace them after Save. The server stays the authority.
 */

afterEach(() => {
  vi.unstubAllGlobals();
});

const FIRST = '[data-r="1"][data-c="1"]';
const SECOND = '[data-r="2"][data-c="1"]';
const SCORE = '[data-r="3"][data-c="1"]';

async function open(server: ReviewerServer) {
  const calls = fakeApi(reviewerFill(server));
  const mounted = render(<FillWorkspace owner={FILL_OWNER} label="Fire checks" />);
  await screen.findByRole("grid");
  return { ...mounted, calls };
}

/** Types a mark into the cell of the grid the way a person does: open it, type, press Enter. */
async function typeMark(container: HTMLElement, cell: string, name: string, text: string) {
  fireEvent.doubleClick(container.querySelector(cell) as HTMLElement);
  const box = await screen.findByRole("textbox", { name });
  fireEvent.change(box, { target: { value: text } });
  fireEvent.keyDown(box, { key: "Enter" });
}

const score = (container: HTMLElement) => container.querySelector(SCORE)?.textContent ?? "";

describe("a reviewer types a mark", () => {
  it("shows the answer of the formula at once, as a preview, before anything is saved", async () => {
    const server = newServer();
    const { container } = await open(server);
    await waitFor(() => expect(score(container)).toContain("0"));

    await typeMark(container, FIRST, "Mark, First", "5");

    // A plain number typed into a text cell is a number to the formula, as it is on the server.
    await waitFor(() => expect(score(container)).toContain("5"));
    expect(screen.getByTestId("fill-status-tag")).toHaveAttribute("title", expect.stringContaining("Answers are a preview until you save"));
    expect(server.saves).toBe(0);
  });

  it("adds the marks of several cells and skips a mark that is not a number", async () => {
    const { container } = await open(newServer());

    await typeMark(container, FIRST, "Mark, First", "5");
    await typeMark(container, SECOND, "Mark, Second", "2.5");
    await waitFor(() => expect(score(container)).toContain("7.5"));

    await typeMark(container, SECOND, "Mark, Second", "n/a");
    await waitFor(() => expect(score(container)).toContain("5"));
    expect(score(container)).not.toContain("7.5");
  });

  it("replaces the preview by the server's answer after Save, and asks for the answers again", async () => {
    const server = newServer();
    const { container } = await open(server);
    await waitFor(() => expect(server.calculations).toBe(1));
    server.bonus = 10;

    await typeMark(container, FIRST, "Mark, First", "5");
    await waitFor(() => expect(score(container)).toContain("5"));
    fireEvent.click(screen.getByRole("button", { name: "Save" }));

    await screen.findByText("Saved");
    await waitFor(() => expect(score(container)).toContain("15"));
    expect(server.cells).toEqual({ "r1:c2": "5" });
    expect(server.calculations).toBe(2);
    expect(screen.getByTestId("fill-status-tag")).toHaveAttribute("title", expect.not.stringContaining("preview"));
  });

  it("asks for the answers again after a reviewer's Save and shows what the server worked out", async () => {
    const server = newServer();
    const { container } = await open(server);
    await waitFor(() => expect(server.calculations).toBe(1));
    server.bonus = 10;

    await typeMark(container, FIRST, "Mark, First", "5");
    fireEvent.click(screen.getByRole("button", { name: "Save" }));

    await screen.findByText("Saved");
    await waitFor(() => expect(score(container)).toContain("15"));
    expect(server.calculations).toBe(2);
  });

  it("keeps the typed value and the preview when the server refuses the save", async () => {
    const server = newServer();
    const { container } = await open(server);
    server.refuse = "The review window is closed.";

    await typeMark(container, FIRST, "Mark, First", "5");
    fireEvent.click(screen.getByRole("button", { name: "Save" }));

    expect(await screen.findByTestId("save-refused")).toHaveTextContent("The review window is closed.");
    expect(container.querySelector(FIRST)).toHaveTextContent("5");
    expect(score(container)).toContain("5");
    expect(screen.getByTestId("fill-status-tag")).toHaveAttribute("title", expect.stringContaining("preview"));
    expect(screen.getByText("Unsaved changes")).toBeInTheDocument();
  });

  it("keeps autosave off for the reviewer: nothing is saved by waiting", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    try {
      const server = newServer();
      const { container } = await open(server);

      await typeMark(container, FIRST, "Mark, First", "5");
      await vi.advanceTimersByTimeAsync(5000);

      expect(server.saves).toBe(0);
      expect(screen.getByText("Unsaved changes")).toBeInTheDocument();
    } finally {
      vi.useRealTimers();
    }
  });

  it("tells the reviewer that entries are kept at Save and that a decision saves them first", async () => {
    await open(newServer());

    expect(screen.getByTestId("fill-review-note")).toHaveTextContent("when you press Save");
    expect(screen.getByTestId("fill-review-note")).toHaveTextContent("a decision saves them first");
  });
});
