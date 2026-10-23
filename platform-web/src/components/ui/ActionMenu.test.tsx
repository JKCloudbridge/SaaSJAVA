import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { ActionMenu } from "./ActionMenu";

describe("ActionMenu", () => {
  it("opens a menu of what is offered, runs the choice and closes", () => {
    const retire = vi.fn();
    render(<ActionMenu label="More actions" items={[{ label: "Unpublish", onSelect: vi.fn() }, { label: "Retire", onSelect: retire, danger: true }]} />);
    const button = screen.getByRole("button", { name: "More actions" });
    expect(button).toHaveAttribute("aria-expanded", "false");

    fireEvent.click(button);
    expect(button).toHaveAttribute("aria-expanded", "true");
    expect(screen.getByRole("menuitem", { name: "Retire" })).toHaveClass("menu-danger");
    fireEvent.click(screen.getByRole("menuitem", { name: "Retire" }));

    expect(retire).toHaveBeenCalledTimes(1);
    expect(screen.queryByRole("menu")).toBeNull();
  });

  it("closes on Escape and on a click away", () => {
    render(
      <>
        <ActionMenu label="More actions" items={[{ label: "Retire", onSelect: vi.fn() }]} />
        <p>elsewhere</p>
      </>,
    );
    fireEvent.click(screen.getByRole("button", { name: "More actions" }));
    fireEvent.keyDown(document, { key: "Escape" });
    expect(screen.queryByRole("menu")).toBeNull();

    fireEvent.click(screen.getByRole("button", { name: "More actions" }));
    fireEvent.mouseDown(screen.getByText("elsewhere"));
    expect(screen.queryByRole("menu")).toBeNull();
  });

  it("draws nothing when nothing is offered", () => {
    const { container } = render(<ActionMenu label="More actions" items={[]} />);
    expect(container).toBeEmptyDOMElement();
  });
});
