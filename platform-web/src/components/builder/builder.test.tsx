import { fireEvent, render, screen, within } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { startDrag } from "@/lib/builder/dragPayload";
import { makeTransfer } from "@/test/dragDrop";
import { BuilderShell } from "./BuilderShell";
import { DropZone } from "./DropZone";
import { MoveButtons } from "./MoveButtons";
import { Palette } from "./Palette";

describe("Palette", () => {
  const categories = [
    {
      id: "fields",
      label: "Fields",
      empty: "No fields.",
      items: [
        { key: "field:a", label: "Alpha" },
        { key: "field:b", label: "Beta", used: true },
        { key: "field:c", label: "Gamma" },
      ],
    },
    { id: "other", label: "Other", empty: "Nothing else.", items: [] },
  ];

  it("offers the chips of the first category, dims a used one and adds a chip with the keyboard", () => {
    const picked = vi.fn();
    render(<Palette label="Parts" categories={categories} onPick={picked} />);

    const beta = screen.getByRole("button", { name: "Beta" });
    expect(beta).toHaveAttribute("aria-disabled", "true");
    expect(beta).toHaveAttribute("draggable", "false");
    fireEvent.click(beta);
    fireEvent.keyDown(beta, { key: "Enter" });
    expect(picked).not.toHaveBeenCalled();

    fireEvent.keyDown(screen.getByRole("button", { name: "Alpha" }), { key: "Enter" });
    fireEvent.click(screen.getByRole("button", { name: "Gamma" }));
    fireEvent.keyDown(screen.getByRole("button", { name: "Gamma" }), { key: " " });
    expect(picked.mock.calls.map((call) => call[0].key)).toEqual(["field:a", "field:c", "field:c"]);
  });

  it("switches category, says what an empty one holds, and searches", () => {
    render(<Palette label="Parts" categories={categories} onPick={vi.fn()} searchable />);

    fireEvent.change(screen.getByLabelText("Search fields"), { target: { value: "gam" } });
    expect(screen.queryByRole("button", { name: "Alpha" })).toBeNull();
    expect(screen.getByRole("button", { name: "Gamma" })).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText("Search fields"), { target: { value: "zzz" } });
    expect(screen.getByText("Nothing matches.")).toBeInTheDocument();

    fireEvent.click(within(screen.getByRole("group", { name: "Parts: categories" })).getByRole("button", { name: "Other" }));
    expect(screen.getByText("Nothing else.")).toBeInTheDocument();
  });

  it("starts a drag that carries the key of the chip", () => {
    const transfer = makeTransfer();
    render(<Palette label="Parts" categories={categories} onPick={vi.fn()} />);

    fireEvent.dragStart(screen.getByRole("button", { name: "Alpha" }), { dataTransfer: transfer });

    expect(JSON.parse(transfer.getData("application/x-platform-builder"))).toEqual({ origin: "palette", key: "field:a" });
  });
});

describe("DropZone", () => {
  it("hands over what was dropped and shows the outline while a builder drag is over it", () => {
    const dropped = vi.fn();
    render(
      <>
        <div data-testid="source" draggable />
        <DropZone className="zone" data-testid="zone" onDropPayload={dropped}>
          here
        </DropZone>
      </>,
    );
    const zone = screen.getByTestId("zone");
    const transfer = makeTransfer();
    startDrag({ dataTransfer: transfer }, { origin: "canvas", group: "g", index: 2 });

    fireEvent.dragOver(zone, { dataTransfer: transfer });
    expect(zone).toHaveClass("drag-over");
    fireEvent.dragLeave(zone, { dataTransfer: transfer });
    expect(zone).not.toHaveClass("drag-over");
    fireEvent.drop(zone, { dataTransfer: transfer });

    expect(dropped).toHaveBeenCalledWith({ origin: "canvas", group: "g", index: 2 });
  });

  it("ignores a drag that did not start in a builder", () => {
    const dropped = vi.fn();
    render(
      <DropZone data-testid="zone" onDropPayload={dropped}>
        here
      </DropZone>,
    );
    const zone = screen.getByTestId("zone");
    const transfer = makeTransfer();
    transfer.setData("text/plain", "a file");

    fireEvent.dragOver(zone, { dataTransfer: transfer });
    fireEvent.drop(zone, { dataTransfer: transfer });

    expect(zone).not.toHaveClass("drag-over");
    expect(dropped).not.toHaveBeenCalled();
  });

  it("lets only the innermost zone take a drop", () => {
    const outer = vi.fn();
    const inner = vi.fn();
    render(
      <>
        <div data-testid="from" draggable />
        <DropZone data-testid="outer" onDropPayload={outer}>
          <DropZone data-testid="inner" onDropPayload={inner}>
            x
          </DropZone>
        </DropZone>
      </>,
    );
    const transfer = makeTransfer();
    startDrag({ dataTransfer: transfer }, { origin: "palette", key: "k" });

    fireEvent.drop(screen.getByTestId("inner"), { dataTransfer: transfer });

    expect(inner).toHaveBeenCalledTimes(1);
    expect(outer).not.toHaveBeenCalled();
  });
});

describe("MoveButtons", () => {
  it("names the item on every button and disables the move that is not possible", () => {
    const remove = vi.fn();
    render(<MoveButtons name="Account" onDown={vi.fn()} onUp={undefined} onRemove={remove} />);

    expect(screen.getByRole("button", { name: "Move Account up" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "Move Account down" })).toBeEnabled();
    fireEvent.click(screen.getByRole("button", { name: "Take out Account" }));
    expect(remove).toHaveBeenCalled();
  });
});

describe("BuilderShell", () => {
  function shell(dirty: boolean, onSave = vi.fn()) {
    return render(
      <BuilderShell
        testId="shell"
        crumbs={[{ label: "Objects", href: "/setup/objects" }, { label: "Deal" }]}
        title="Details"
        backHref="/setup/objects/Deal__c"
        dirty={dirty}
        busy={false}
        saveLabel="Save it"
        onSave={onSave}
      >
        <p>content</p>
      </BuilderShell>,
    );
  }

  it("shows where the person is and saves", () => {
    const save = vi.fn();
    shell(false, save);

    const crumbs = screen.getByRole("navigation", { name: "Where you are" });
    expect(within(crumbs).getByRole("link", { name: "Objects" })).toHaveAttribute("href", "/setup/objects");
    expect(within(crumbs).getByText("Deal")).toHaveAttribute("aria-current", "page");
    expect(screen.getByRole("link", { name: "Back" })).toHaveAttribute("href", "/setup/objects/Deal__c");
    expect(screen.queryByTestId("unsaved")).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "Save it" }));
    expect(save).toHaveBeenCalled();
  });

  it("warns about unsaved changes, asks before discarding and warns a closing tab", () => {
    shell(true);

    expect(screen.getByTestId("unsaved")).toBeInTheDocument();
    const event = new Event("beforeunload", { cancelable: true });
    window.dispatchEvent(event);
    expect(event.defaultPrevented).toBe(true);

    fireEvent.click(screen.getByRole("button", { name: "Cancel" }));
    expect(screen.getByRole("link", { name: "Yes, discard" })).toHaveAttribute("href", "/setup/objects/Deal__c");
  });

  it("does not warn a closing tab when nothing is unsaved", () => {
    shell(false);

    const event = new Event("beforeunload", { cancelable: true });
    window.dispatchEvent(event);

    expect(event.defaultPrevented).toBe(false);
  });
});
