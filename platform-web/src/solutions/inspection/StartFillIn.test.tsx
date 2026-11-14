import { configure, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { fakeApi, json } from "@/test/fakeApi";
import type { FillInOptions } from "./fillIns";
import { StartFillIn } from "./StartFillIn";

configure({ asyncUtilTimeout: 5000 });

const ID = "0198f5c0-0000-7000-8000-000000000055";
const PATH = `/api/v1/templates/${ID}/fill-ins`;

function options(extra: Partial<FillInOptions> = {}): FillInOptions {
  return {
    canStart: true,
    activeVersion: 2,
    monthly: true,
    plants: [{ plant: "PLANT1", label: "Plant 1" }],
    currentPeriod: "2026-05",
    ...extra,
  };
}

function draw(held = options()) {
  const onClose = vi.fn();
  const onStarted = vi.fn();
  render(<StartFillIn templateId={ID} options={held} onClose={onClose} onStarted={onStarted} />);
  return { onClose, onStarted };
}

describe("the popup that starts a monthly fill-in", () => {
  it("shows the one plant a person is plant head of and asks for the month, this month first", () => {
    draw();

    expect(screen.getByText("Plant 1")).toBeInTheDocument();
    expect(screen.queryByRole("combobox")).toBeNull();
    const month = screen.getByLabelText("Month");
    expect(month).toHaveValue("2026-05");
    expect(month).toHaveAttribute("max", "2026-05");
  });

  it("lets a person of two plants choose and does not start until they have", () => {
    draw(options({ plants: [{ plant: "PLANT1", label: "Plant 1" }, { plant: "PLANT2", label: "Plant 2" }] }));

    expect(screen.getByRole("button", { name: "Start" })).toBeDisabled();
    fireEvent.change(screen.getByRole("combobox"), { target: { value: "PLANT2" } });
    expect(screen.getByRole("button", { name: "Start" })).not.toBeDisabled();
  });

  it("sends the plant and the month chosen and hands the new fill-in on", async () => {
    const started = { fillId: "f-1", templateId: ID, versionNo: 2 };
    const calls = fakeApi({ [`POST ${PATH}`]: () => json({ data: started }, 201) });
    const { onStarted } = draw();
    fireEvent.change(screen.getByLabelText("Month"), { target: { value: "2026-03" } });

    fireEvent.click(screen.getByRole("button", { name: "Start" }));

    await waitFor(() => expect(onStarted).toHaveBeenCalledWith(started));
    expect(JSON.parse(calls.find((call) => call.key === `POST ${PATH}`)?.body ?? "{}")).toEqual({
      plant: "PLANT1",
      period: "2026-03",
    });
  });

  it("shows the server's words when the month is taken and stays open", async () => {
    fakeApi({
      [`POST ${PATH}`]: () =>
        json(
          {
            error: {
              code: "CONFLICT",
              message: "A fill-in for this document, this plant and this month exists already. Open it from the list.",
            },
          },
          409,
        ),
    });
    const { onStarted } = draw();

    fireEvent.click(screen.getByRole("button", { name: "Start" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("exists already");
    expect(onStarted).not.toHaveBeenCalled();
  });

  it("puts a refusal about the month next to the month", async () => {
    fakeApi({
      [`POST ${PATH}`]: () =>
        json(
          {
            error: {
              code: "VALIDATION_ERROR",
              message: "The request is not valid.",
              fields: { period: ["A month that has not begun cannot be started."] },
            },
          },
          400,
        ),
    });
    draw();

    fireEvent.click(screen.getByRole("button", { name: "Start" }));

    expect(await screen.findByText("A month that has not begun cannot be started.")).toBeInTheDocument();
  });
});
