import { act, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
import { ActionNotice } from "./ActionNotice";

afterEach(() => vi.useRealTimers());

it("expires a success after three seconds and keeps an error", () => {
  vi.useFakeTimers();
  render(<><ActionNotice text="Published." /><ActionNotice text="Save was refused." error /></>);
  act(() => vi.advanceTimersByTime(2999));
  expect(screen.getByText("Published.")).toBeInTheDocument();
  act(() => vi.advanceTimersByTime(1));
  expect(screen.queryByText("Published.")).not.toBeInTheDocument();
  expect(screen.getByRole("alert")).toHaveTextContent("Save was refused.");
});

it("keeps a notice while hovered and expires it after leaving", () => {
  vi.useFakeTimers();
  render(<ActionNotice text="Approved." />);
  fireEvent.mouseEnter(screen.getByRole("status"));
  act(() => vi.advanceTimersByTime(6000));
  expect(screen.getByText("Approved.")).toBeInTheDocument();
  fireEvent.mouseLeave(screen.getByRole("status"));
  act(() => vi.advanceTimersByTime(3000));
  expect(screen.queryByText("Approved.")).not.toBeInTheDocument();
});
