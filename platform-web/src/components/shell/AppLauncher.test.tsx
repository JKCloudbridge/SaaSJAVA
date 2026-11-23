import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { AppLauncher } from "./AppLauncher";

vi.mock("next/navigation", () => ({ usePathname: () => "/apps/quality/home" }));
vi.mock("@/components/apps/ApplicationGrid", () => ({
  ApplicationGrid: ({ current }: { current?: string }) => <div data-testid="the-grid">{current ?? "none"}</div>,
}));
vi.mock("@/lib/applications/ApplicationsProvider", () => ({
  useApplications: () => ({ state: { status: "ready", applications: [] }, refresh: async () => {} }),
}));
vi.mock("@/lib/session/SessionProvider", () => ({
  useSession: () => ({ state: { status: "signedIn", user: { abilities: [] } } }),
}));

describe("the application launcher button", () => {
  it("opens the grid in a window and closes it with a click outside it, with no cross", () => {
    render(<AppLauncher current="quality" />);
    expect(screen.queryByRole("dialog")).toBeNull();

    fireEvent.click(screen.getByRole("button", { name: "Applications" }));

    expect(screen.getByRole("dialog")).toBeInTheDocument();
    expect(screen.getByTestId("the-grid")).toHaveTextContent("quality");

    expect(screen.queryByRole("button", { name: "Close" })).toBeNull();
    // a click inside the window keeps it open; a click on the dim area beside it closes it
    fireEvent.mouseDown(screen.getByTestId("the-grid"));
    expect(screen.getByRole("dialog")).toBeInTheDocument();
    fireEvent.mouseDown(screen.getByTestId("launcher-window"));

    expect(screen.queryByRole("dialog")).toBeNull();
  });

  it("closes with Escape", () => {
    render(<AppLauncher />);
    fireEvent.click(screen.getByRole("button", { name: "Applications" }));

    fireEvent.keyDown(document, { key: "Escape" });

    expect(screen.queryByRole("dialog")).toBeNull();
  });
});
