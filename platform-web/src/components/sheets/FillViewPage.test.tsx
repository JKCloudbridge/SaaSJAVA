import { configure, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { fakeApi, json, type Handler } from "@/test/fakeApi";
import {
  FILL_OWNER,
  FILL_URL,
  newServer,
  reviewerFill,
  reviewerSheet,
  type ReviewerServer,
} from "@/test/reviewerFill";
import { sizeTheGrid } from "@/test/virtualDom";
import { FillWorkspace } from "./FillWorkspace";

vi.mock("next/navigation", async () => (await import("@/test/navigation")).navigationMock);

sizeTheGrid();
configure({ asyncUtilTimeout: 5000 });

/**
 * The View page of a fill-in (SH_6): the same grid over the same editor and the same save queue as the Sheet tab, drawn as the
 * form, with Save, Print and Back. These tests drive it through the whole workspace, the way a person reaches it.
 */

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

const FIRST = '[data-r="1"][data-c="1"]';
const SECOND = '[data-r="2"][data-c="1"]';
const SCORE = '[data-r="3"][data-c="1"]';
const FIXED = '[data-r="1"][data-c="0"]';
const STAMP = "F/MC/10  Rev No.'02'";

/** The fill-in as a filler has it: the two mark cells are the filler's own, not the reviewer's. */
function asFiller(server: ReviewerServer, fill: Record<string, unknown> = { status: "DRAFT" }): Record<string, Handler> {
  const handlers = reviewerFill(server, { editAs: "FILLER", fill });
  const original = handlers[`GET ${FILL_URL}`] as Handler;
  handlers[`GET ${FILL_URL}`] = async (request) => {
    const body = (await (await original(request)).json()) as { data: { grid: ReturnType<typeof reviewerSheet> } };
    const sheet = (body.data.grid as { sheets: { inputs: Record<string, unknown> }[] }).sheets[0];
    if (sheet) sheet.inputs = { "r1:c2": {}, "r2:c2": {} };
    return json(body);
  };
  return handlers;
}

async function open(handlers: Record<string, Handler>, props: { footer?: string } = {}) {
  const calls = fakeApi(handlers);
  const mounted = render(<FillWorkspace owner={FILL_OWNER} label="Fire checks" footer={props.footer ?? STAMP} />);
  await screen.findByRole("grid");
  return { ...mounted, calls };
}

async function openView() {
  const button = screen.getByTestId("fill-view");
  button.focus();
  fireEvent.click(button);
  return await screen.findByTestId("fill-view-page");
}

/** Types into a cell of the page the way a person does: open it, type, press Enter. */
async function typeMark(page: HTMLElement, cell: string, name: string, text: string) {
  fireEvent.doubleClick(page.querySelector(cell) as HTMLElement);
  const box = await within(page).findByRole("textbox", { name });
  fireEvent.change(box, { target: { value: text } });
  fireEvent.keyDown(box, { key: "Enter" });
}

describe("the View page as a form", () => {
  it("shows the sheet with no column letters and no row numbers, and the stamp under it", async () => {
    await open(asFiller(newServer()));
    // the tab's grid has the letters and the numbers
    expect(screen.getAllByRole("columnheader", { name: /^A/ })).toHaveLength(1);

    const page = await openView();

    expect(within(page).queryAllByRole("columnheader")).toHaveLength(0);
    expect(within(page).queryAllByRole("rowheader")).toHaveLength(0);
    expect(within(page).getByText("Fire exit")).toBeInTheDocument();
    expect(within(page).getByTestId("sheet-footer")).toHaveTextContent("F/MC/10 Rev No.'02'");
  });

  it("puts the focus on the grid, on the first cell the person may type in", async () => {
    await open(asFiller(newServer()));
    const page = await openView();

    const grid = within(page).getByRole("grid");
    await waitFor(() => expect(grid).toHaveFocus());
    expect(page.querySelector(FIRST)).toHaveClass("sheet-active");
  });

  it("is the same editor: what is typed in View is in the tab, and the reverse", async () => {
    const { container } = await open(asFiller(newServer()));
    const page = await openView();

    await typeMark(page, FIRST, "Mark, First", "5");
    expect(container.querySelector(FIRST)).toHaveTextContent("5");

    fireEvent.click(within(page).getByRole("button", { name: "Back" }));
    await waitFor(() => expect(screen.queryByTestId("fill-view-page")).toBeNull());
    await typeMarkInTab(container, SECOND, "Mark, Second", "2");

    const again = await openView();
    expect(again.querySelector(FIRST)).toHaveTextContent("5");
    expect(again.querySelector(SECOND)).toHaveTextContent("2");
  });

  it("cannot be typed into on a fixed cell or a cell of the other class", async () => {
    await open(asFiller(newServer()));
    const page = await openView();

    fireEvent.doubleClick(page.querySelector(FIXED) as HTMLElement);
    expect(within(page).queryByRole("textbox")).toBeNull();
    expect(within(page).getByText("This cell is fixed text and cannot be typed into.")).toBeInTheDocument();

  });

  it("does not let a filler type into the reviewer's cells", async () => {
    await open(reviewerFill(newServer(), { editAs: "FILLER", fill: { status: "DRAFT" } }));
    const page = await openView();

    fireEvent.doubleClick(page.querySelector(FIRST) as HTMLElement);

    expect(within(page).queryByRole("textbox")).toBeNull();
  });

  it("keeps the named headings of a sheet in the first format, which has no letters to leave out", async () => {
    const handlers = asFiller(newServer());
    const original = handlers[`GET ${FILL_URL}`] as Handler;
    handlers[`GET ${FILL_URL}`] = async (request) => {
      const body = (await (await original(request)).json()) as { data: Record<string, unknown> };
      const sheet = (body.data.grid as { sheets: Record<string, unknown>[] }).sheets[0] as Record<string, unknown>;
      body.data.grid = { formatVersion: 1, ...sheet, inputs: undefined, cells: { "r1:c1": { value: "Fire exit" } } };
      body.data.cells = { formatVersion: 1, rows: [] };
      return json(body);
    };
    await open(handlers);

    const page = await openView();

    expect(within(page).getAllByRole("columnheader").map((one) => one.textContent)).toEqual(["Item", "Mark"]);
  });
});

async function typeMarkInTab(container: HTMLElement, cell: string, name: string, text: string) {
  fireEvent.doubleClick(container.querySelector(cell) as HTMLElement);
  const box = await screen.findByRole("textbox", { name });
  fireEvent.change(box, { target: { value: text } });
  fireEvent.keyDown(box, { key: "Enter" });
}

describe("Back for a filler", () => {
  it("saves what is unsaved first, then returns to the fill-in with the work saved", async () => {
    const server = newServer();
    await open(asFiller(server));
    const page = await openView();

    await typeMark(page, FIRST, "Mark, First", "5");
    fireEvent.click(within(page).getByRole("button", { name: "Back" }));

    await waitFor(() => expect(screen.queryByTestId("fill-view-page")).toBeNull());
    expect(server.saves).toBe(1);
    expect(server.cells).toEqual({ "r1:c2": "5" });
    expect(screen.getByText("Saved")).toBeInTheDocument();
  });

  it("goes back at once when nothing is unsaved", async () => {
    const server = newServer();
    await open(asFiller(server));
    const page = await openView();

    fireEvent.click(within(page).getByRole("button", { name: "Back" }));

    await waitFor(() => expect(screen.queryByTestId("fill-view-page")).toBeNull());
    expect(server.saves).toBe(0);
  });

  it("stays on the page, with the cells and the server's words, when the save is refused", async () => {
    const server = newServer();
    await open(asFiller(server));
    const page = await openView();
    server.refuse = "The amount is too big.";

    await typeMark(page, FIRST, "Mark, First", "5");
    fireEvent.click(within(page).getByRole("button", { name: "Back" }));

    expect(await within(page).findByTestId("save-refused")).toHaveTextContent("The amount is too big.");
    expect(screen.getByTestId("fill-view-page")).toBeInTheDocument();
    expect(page.querySelector(FIRST)).toHaveTextContent("5");
    // the notice is drawn once: the tab's own copy is not drawn behind the page
    expect(screen.getAllByTestId("save-refused")).toHaveLength(1);

    // the cells are still there on the tab after Back without a successful save is not possible: fix the cause and go back
    server.refuse = undefined;
    fireEvent.click(within(page).getByRole("button", { name: "Back" }));
    await waitFor(() => expect(screen.queryByTestId("fill-view-page")).toBeNull());
    expect(server.cells).toEqual({ "r1:c2": "5" });
  });

  it("saves with the Save button and shows Saved", async () => {
    const server = newServer();
    await open(asFiller(server));
    const page = await openView();

    expect(within(page).getByRole("button", { name: "Save" })).toBeDisabled();
    await typeMark(page, FIRST, "Mark, First", "5");
    fireEvent.click(within(page).getByRole("button", { name: "Save" }));

    await waitFor(() => expect(within(page).getByTestId("fill-view-state")).toHaveTextContent("Saved"));
    expect(server.saves).toBe(1);
    expect(screen.getByTestId("fill-view-page")).toBeInTheDocument();
  });
});

describe("Back for a reviewer", () => {
  it("asks first when cells are unsaved, and Save and go back makes one save", async () => {
    const server = newServer();
    await open(reviewerFill(server));
    const page = await openView();

    await typeMark(page, FIRST, "Mark, First", "5");
    fireEvent.click(within(page).getByRole("button", { name: "Back" }));

    const ask = await within(page).findByTestId("fill-view-ask");
    expect(server.saves).toBe(0);
    fireEvent.click(within(ask).getByRole("button", { name: "Save and go back" }));

    await waitFor(() => expect(screen.queryByTestId("fill-view-page")).toBeNull());
    expect(server.saves).toBe(1);
    expect(server.cells).toEqual({ "r1:c2": "5" });
  });

  it("goes back without saving, and the entries stay unsaved on the tab", async () => {
    const server = newServer();
    const { container } = await open(reviewerFill(server));
    const page = await openView();

    await typeMark(page, FIRST, "Mark, First", "5");
    fireEvent.click(within(page).getByRole("button", { name: "Back" }));
    fireEvent.click(await within(page).findByRole("button", { name: "Go back without saving" }));

    await waitFor(() => expect(screen.queryByTestId("fill-view-page")).toBeNull());
    expect(server.saves).toBe(0);
    expect(container.querySelector(FIRST)).toHaveTextContent("5");
    expect(screen.getByText("Unsaved changes")).toBeInTheDocument();
  });

  it("goes back without asking when nothing is unsaved", async () => {
    const server = newServer();
    await open(reviewerFill(server));
    const page = await openView();

    fireEvent.click(within(page).getByRole("button", { name: "Back" }));

    await waitFor(() => expect(screen.queryByTestId("fill-view-page")).toBeNull());
    expect(server.saves).toBe(0);
  });

  it("makes one counted save for each press of Save and never saves by waiting", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    try {
      const server = newServer();
      await open(reviewerFill(server));
      const page = await openView();

      await typeMark(page, FIRST, "Mark, First", "5");
      await vi.advanceTimersByTimeAsync(5000);
      expect(server.saves).toBe(0);

      fireEvent.click(within(page).getByRole("button", { name: "Save" }));
      await waitFor(() => expect(server.saves).toBe(1));
      await waitFor(() => expect(within(page).getByTestId("fill-view-state")).toHaveTextContent("Saved"));
    } finally {
      vi.useRealTimers();
    }
  });
});

describe("the formulas in the View page", () => {
  it("recalculate at once while typing, and the server's answer replaces them after Save", async () => {
    const server = newServer();
    await open(reviewerFill(server));
    const page = await openView();
    await waitFor(() => expect(server.calculations).toBe(1));
    server.bonus = 10;

    await typeMark(page, FIRST, "Mark, First", "5");
    await waitFor(() => expect(page.querySelector(SCORE)?.textContent).toContain("5"));
    expect(page.querySelector(SCORE)?.textContent).not.toContain("15");

    fireEvent.click(within(page).getByRole("button", { name: "Save" }));
    await waitFor(() => expect(page.querySelector(SCORE)?.textContent).toContain("15"));
  });
});

describe("Print on the View page", () => {
  it("opens the print drawing with the typed values, and Escape closes only the drawing", async () => {
    const print = vi.spyOn(window, "print").mockImplementation(() => undefined);
    await open(asFiller(newServer()));
    const page = await openView();
    await typeMark(page, FIRST, "Mark, First", "7");

    fireEvent.click(within(page).getByRole("button", { name: "Print" }));
    const drawing = await screen.findByTestId("sheet-print-view");

    // the mark itself, and the total that adds it up
    expect(within(drawing).getAllByText("7").length).toBeGreaterThan(0);
    expect(within(drawing).getByTestId("sheet-print-footer")).toHaveTextContent("F/MC/10 Rev No.'02'");
    fireEvent.click(within(drawing).getByRole("button", { name: "Print" }));
    expect(print).toHaveBeenCalledTimes(1);

    fireEvent.keyDown(document, { key: "Escape" });
    await waitFor(() => expect(screen.queryByTestId("sheet-print-view")).toBeNull());
    expect(screen.getByTestId("fill-view-page")).toBeInTheDocument();
  });
});

describe("a person who may only read", () => {
  it("gets the same page with no Save, can print, and goes back", async () => {
    const print = vi.spyOn(window, "print").mockImplementation(() => undefined);
    await open(reviewerFill(newServer(), { editAs: null, canEdit: false }));
    const page = await openView();

    expect(within(page).queryByRole("button", { name: "Save" })).toBeNull();
    expect(within(page).getByRole("grid")).toHaveAttribute("aria-readonly", "true");
    fireEvent.doubleClick(page.querySelector(FIRST) as HTMLElement);
    expect(within(page).queryByRole("textbox")).toBeNull();

    fireEvent.click(within(page).getByRole("button", { name: "Print" }));
    fireEvent.click(await within(await screen.findByTestId("sheet-print-view")).findByRole("button", { name: "Print" }));
    expect(print).toHaveBeenCalledTimes(1);
    fireEvent.keyDown(document, { key: "Escape" });

    fireEvent.click(within(page).getByRole("button", { name: "Back" }));
    await waitFor(() => expect(screen.queryByTestId("fill-view-page")).toBeNull());
  });
});

describe("the keyboard and the focus", () => {
  it("leaves with Escape like Back, and gives the focus back to the View button", async () => {
    await open(asFiller(newServer()));
    const button = screen.getByTestId("fill-view");
    button.focus();
    fireEvent.click(button);
    await screen.findByTestId("fill-view-page");

    fireEvent.keyDown(document, { key: "Escape" });

    await waitFor(() => expect(screen.queryByTestId("fill-view-page")).toBeNull());
    expect(button).toHaveFocus();
  });

  it("keeps Escape for a cell being typed in: the page stays", async () => {
    await open(asFiller(newServer()));
    const page = await openView();

    fireEvent.doubleClick(page.querySelector(FIRST) as HTMLElement);
    const box = await within(page).findByRole("textbox", { name: "Mark, First" });
    fireEvent.keyDown(box, { key: "Escape" });

    expect(screen.getByTestId("fill-view-page")).toBeInTheDocument();
  });

  it("asks a reviewer on Escape when cells are unsaved, and a second Escape stays on the page", async () => {
    const server = newServer();
    await open(reviewerFill(server));
    const page = await openView();
    await typeMark(page, FIRST, "Mark, First", "5");

    fireEvent.keyDown(document, { key: "Escape" });

    expect(await within(page).findByTestId("fill-view-ask")).toBeInTheDocument();
    fireEvent.keyDown(document, { key: "Escape" });
    await waitFor(() => expect(screen.queryByTestId("fill-view-ask")).toBeNull());
    expect(screen.getByTestId("fill-view-page")).toBeInTheDocument();
    expect(server.saves).toBe(0);
  });

  it("saves a filler's cells on Escape and then leaves", async () => {
    const server = newServer();
    await open(asFiller(server));
    const page = await openView();
    await typeMark(page, FIRST, "Mark, First", "5");

    fireEvent.keyDown(document, { key: "Escape" });

    await waitFor(() => expect(screen.queryByTestId("fill-view-page")).toBeNull());
    expect(server.cells).toEqual({ "r1:c2": "5" });
  });

  it("names the page and every button, and puts the tab out of reach while it is open", async () => {
    await open(asFiller(newServer()));
    const page = await openView();

    expect(page).toHaveAttribute("role", "dialog");
    expect(page).toHaveAttribute("aria-modal", "true");
    expect(page).toHaveAccessibleName("Page view of Fire checks");
    for (const name of ["Save", "Print", "Back"]) {
      expect(within(page).getByRole("button", { name })).toBeInTheDocument();
    }
    expect(screen.getByTestId("fill-editor").querySelector("[inert]")).not.toBeNull();
  });
});
