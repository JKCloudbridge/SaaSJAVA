import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { useApplications } from "@/lib/applications/ApplicationsProvider";
import { json, refusal } from "@/test/fakeApi";
import { FIELD, SALES, shellApi, ShellProviders } from "@/test/shellApi";
import { AppLauncher, launcherColumns } from "./AppLauncher";

const THIRD = { ...SALES, apiName: "Third__c", label: "Third" };
const FOURTH = { ...SALES, apiName: "Fourth__c", label: "Fourth" };

vi.mock("next/navigation", () => ({ usePathname: () => "/apps/Field__c/c-Inspections__c" }));

afterEach(() => {
  vi.unstubAllGlobals();
});

/** Shows what the provider has done, so a test can wait for the list before it opens the launcher. */
function Status() {
  return <span data-testid="list-status">{useApplications().state.status}</span>;
}

function renderLauncher(props: { current?: string; canSetUp?: boolean } = {}) {
  return render(
    <ShellProviders>
      <AppLauncher current={props.current} canSetUp={props.canSetUp ?? false} />
      <Status />
      <p>outside</p>
    </ShellProviders>,
  );
}

async function listLoaded() {
  await waitFor(() => expect(screen.getByTestId("list-status")).toHaveTextContent(/ready|error/));
}

async function open() {
  const button = await screen.findByRole("button", { name: "Applications" });
  fireEvent.click(button);
  return { button };
}

/** A key pressed on whatever has the focus. */
function press(key: string) {
  fireEvent.keyDown(document.activeElement ?? document.body, { key });
}

describe("the application launcher", () => {
  it("is closed until it is asked to open, and says whether it is open", async () => {
    shellApi();
    renderLauncher();

    const button = await screen.findByRole("button", { name: "Applications" });

    expect(button).toHaveAttribute("aria-expanded", "false");
    expect(screen.queryByRole("dialog")).toBeNull();
  });

  it("lists the member's applications as the API gives them and links each to its own address", async () => {
    shellApi({ applications: [FIELD, SALES] });
    renderLauncher({ current: "Field__c" });
    await waitFor(() => expect(globalThis.fetch).toHaveBeenCalled());
    await open();

    const dialog = await screen.findByRole("dialog", { name: "Applications" });
    await waitFor(() => expect(within(dialog).getAllByRole("link")).toHaveLength(2));

    const field = within(dialog).getByRole("link", { name: /Field Service/ });
    expect(field).toHaveAttribute("href", "/apps/Field__c");
    expect(field).toHaveAttribute("aria-current", "page");
    expect(within(dialog).getByRole("link", { name: /Sales/ })).toHaveAttribute("href", "/apps/Sales__c");
    expect(within(dialog).getByRole("link", { name: /Sales/ })).not.toHaveAttribute("aria-current");
    expect(screen.getByRole("button", { name: "Applications" })).toHaveAttribute("aria-expanded", "true");
  });

  it("shows the first letter of an application whose icon it cannot draw", async () => {
    shellApi({ applications: [{ ...SALES, iconKey: "an-icon-nobody-drew" }] });
    renderLauncher();
    await open();

    const tile = await screen.findByRole("link", { name: /Sales/ });

    expect(tile).toHaveTextContent("S");
  });

  it("says so when there is nothing to open, and offers the way to set applications up only to who may", async () => {
    shellApi({ applications: [] });
    const { unmount } = renderLauncher({ canSetUp: false });
    await open();

    expect(await screen.findByText("No applications are set up for you yet.")).toBeInTheDocument();
    expect(screen.queryByRole("link", { name: /Manage applications/ })).toBeNull();
    unmount();

    shellApi({ applications: [] });
    renderLauncher({ canSetUp: true });
    await open();
    expect(await screen.findByRole("link", { name: /Manage applications/ })).toHaveAttribute(
      "href",
      "/setup/applications",
    );
  });

  it("says that it is loading, then offers to try again when the list could not be had", async () => {
    let healthy = false;
    shellApi({
      applicationsAnswer: () =>
        healthy ? json({ data: [SALES] }) : refusal("SERVICE_UNAVAILABLE", "Try again later.", 503),
    });
    renderLauncher();
    await open();

    expect(await screen.findByText("Your applications could not be loaded.")).toBeInTheDocument();
    healthy = true;
    fireEvent.click(screen.getByRole("button", { name: "Try again" }));

    expect(await screen.findByRole("link", { name: /Sales/ })).toBeInTheDocument();
  });

  it("closes with Escape and gives the focus back to its button", async () => {
    shellApi();
    renderLauncher();
    await listLoaded();
    const { button } = await open();
    await screen.findByRole("dialog");

    press("Escape");

    expect(screen.queryByRole("dialog")).toBeNull();
    expect(button).toHaveFocus();
    expect(button).toHaveAttribute("aria-expanded", "false");
  });

  it("closes when the person clicks elsewhere or chooses an application", async () => {
    shellApi();
    renderLauncher();
    await open();
    await screen.findByRole("dialog");

    fireEvent.pointerDown(screen.getByText("outside"));
    expect(screen.queryByRole("dialog")).toBeNull();

    fireEvent.click(screen.getByRole("button", { name: "Applications" }));
    const sales = await screen.findByRole("link", { name: /Sales/ });
    sales.addEventListener("click", (event) => event.preventDefault());
    fireEvent.click(sales);
    expect(screen.queryByRole("dialog")).toBeNull();
  });

  it("moves between applications with the arrow keys", async () => {
    shellApi({ applications: [FIELD, SALES, THIRD, FOURTH] });
    renderLauncher();
    await listLoaded();
    await open();
    const first = await screen.findByRole("link", { name: /Field Service/ });
    await waitFor(() => expect(first).toHaveFocus());

    press("ArrowRight");
    expect(screen.getByRole("link", { name: /Sales/ })).toHaveFocus();
    press("ArrowDown");
    expect(screen.getByRole("link", { name: /Fourth/ })).toHaveFocus();
    press("ArrowLeft");
    press("ArrowUp");
    expect(first).toHaveFocus();
    press("End");
    expect(screen.getByRole("link", { name: /Fourth/ })).toHaveFocus();
    press("Home");
    expect(first).toHaveFocus();
  });

  it("offers the tile to start a new application only to a member who may set things up", async () => {
    shellApi({ applications: [SALES] });
    const { unmount } = renderLauncher({ canSetUp: false });
    await open();
    await screen.findByRole("link", { name: /Sales/ });
    expect(screen.queryByRole("link", { name: /New application/ })).toBeNull();
    unmount();

    shellApi({ applications: [SALES] });
    renderLauncher({ canSetUp: true });
    await open();
    expect(await screen.findByRole("link", { name: /New application/ })).toHaveAttribute(
      "href",
      "/setup/applications/new",
    );
  });
});

describe("the size of the application grid", () => {
  it("grows with the number of tiles: two columns for a few, more as the list gets longer", () => {
    expect([1, 2, 4].map(launcherColumns)).toEqual([2, 2, 2]);
    expect([5, 7, 9].map(launcherColumns)).toEqual([3, 3, 3]);
    expect([10, 14, 40].map(launcherColumns)).toEqual([4, 4, 4]);
  });

  it("gives the panel the columns its list needs, counting the tile for a new application", async () => {
    shellApi({ applications: [FIELD, SALES, THIRD] });
    const { unmount } = renderLauncher({ canSetUp: false });
    await open();
    expect((await screen.findByRole("dialog")).style.getPropertyValue("--launcher-columns")).toBe("2");
    unmount();

    // Three applications and the tile for a new one is four tiles: still two columns. A fourth application makes five.
    shellApi({ applications: [FIELD, SALES, THIRD, FOURTH] });
    renderLauncher({ canSetUp: true });
    await open();
    await screen.findByRole("link", { name: /Fourth/ });
    expect(screen.getByRole("dialog").style.getPropertyValue("--launcher-columns")).toBe("3");
  });

  it("moves between tiles by the number of columns the grid has when the list is long", async () => {
    const many = Array.from({ length: 5 }, (_, index) => ({
      ...SALES,
      apiName: `App${index}__c`,
      label: `App ${index}`,
    }));
    shellApi({ applications: many });
    renderLauncher();
    await listLoaded();
    await open();
    const first = await screen.findByRole("link", { name: /App 0/ });
    await waitFor(() => expect(first).toHaveFocus());

    // Five tiles are three columns wide: one row down from the first is the fourth.
    press("ArrowDown");
    expect(screen.getByRole("link", { name: /App 3/ })).toHaveFocus();
  });
});
