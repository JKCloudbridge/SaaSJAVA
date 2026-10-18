import { act, fireEvent, render, screen } from "@testing-library/react";
import { useState } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { fakeApi, json, refusal } from "@/test/fakeApi";
import { PersonPicker } from "./PersonPicker";

beforeEach(() => {
  vi.useFakeTimers();
});

afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
});

function Harness() {
  const [value, setValue] = useState("");
  const [name, setName] = useState("");
  return (
    <>
      <PersonPicker
        id="who"
        label="Who"
        value={value}
        name={name}
        onChange={(identifier, chosen) => {
          setValue(identifier);
          setName(chosen);
        }}
      />
      <output data-testid="held">{value}</output>
    </>
  );
}

async function type(text: string) {
  fireEvent.change(screen.getByLabelText("Who"), { target: { value: text } });
  await act(async () => {
    await vi.advanceTimersByTimeAsync(300);
  });
}

describe("PersonPicker", () => {
  it("lists nobody until something is typed, then offers what the API finds and keeps the choice", async () => {
    const calls = fakeApi({
      "GET /api/v1/approvals/people": () =>
        json({ data: [{ userId: "u-1", name: "User A" }, { userId: "u-2", name: "User B" }] }),
    });
    render(<Harness />);
    expect(calls).toHaveLength(0);

    await type("us");
    fireEvent.click(screen.getByRole("button", { name: "User B" }));

    expect(screen.getByTestId("held")).toHaveTextContent("u-2");
    expect(screen.getByTestId("who-chosen")).toHaveTextContent("User B");
    expect(calls.map((call) => call.key)).toEqual(["GET /api/v1/approvals/people"]);
  });

  it("waits for the person to stop typing and asks once", async () => {
    const calls = fakeApi({ "GET /api/v1/approvals/people": () => json({ data: [] }) });
    render(<Harness />);

    fireEvent.change(screen.getByLabelText("Who"), { target: { value: "u" } });
    fireEvent.change(screen.getByLabelText("Who"), { target: { value: "us" } });
    fireEvent.change(screen.getByLabelText("Who"), { target: { value: "use" } });
    await act(async () => {
      await vi.advanceTimersByTimeAsync(300);
    });

    expect(calls).toHaveLength(1);
    expect(screen.getByText("Nobody who can be asked matches.")).toBeInTheDocument();
  });

  it("lets the person choose someone else", async () => {
    fakeApi({ "GET /api/v1/approvals/people": () => json({ data: [{ userId: "u-1", name: "User A" }] }) });
    render(<Harness />);
    await type("us");
    fireEvent.click(screen.getByRole("button", { name: "User A" }));

    fireEvent.click(screen.getByRole("button", { name: "Choose someone else" }));

    expect(screen.getByTestId("held")).toHaveTextContent("");
    expect(screen.getByLabelText("Who")).toBeInTheDocument();
  });

  it("shows the API's words when the search is refused", async () => {
    fakeApi({ "GET /api/v1/approvals/people": () => refusal("FORBIDDEN", "You may not search for people.", 403) });
    render(<Harness />);

    await type("us");

    expect(screen.getByRole("alert")).toHaveTextContent("You may not search for people.");
  });
});
