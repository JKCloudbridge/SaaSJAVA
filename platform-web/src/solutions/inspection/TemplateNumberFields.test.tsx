import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import type { FormSlotAccess } from "@/components/records/RecordForm";
import type { NumberingInfo } from "./api";
import { NumberField, PartField } from "./TemplateNumberFields";

const info = (extra: Partial<NumberingInfo> = {}): NumberingInfo => ({
  known: true,
  partA: true,
  partB: false,
  serial: true,
  width: 2,
  prefix: "WI/PRD/",
  complete: true,
  started: false,
  canStart: true,
  nextSerial: undefined,
  number: undefined,
  ...extra,
});

const access = (values: Record<string, string> = {}, editable: readonly string[] = ["serial", "partA"]) => {
  const set = vi.fn();
  const given: FormSlotAccess = {
    value: (name) => values[name] ?? "",
    set,
    editable: (name) => editable.includes(name),
    problems: () => [],
  };
  return { given, set };
};

describe("the Document number as it builds itself (FA11B)", () => {
  it("shows the number the template already has while the identity is unchanged", () => {
    render(<NumberField access={access().given} info={info()} savedNumber="WI/PRD/04" />);

    expect(screen.getByTestId("live-number")).toHaveTextContent("WI/PRD/04");
    expect(screen.queryByTestId("serial-input")).toBeNull();
  });

  it("shows nothing for an identification that has no number format", () => {
    render(<NumberField access={access().given} info={info({ known: false })} savedNumber={undefined} />);

    expect(screen.getByTestId("live-number")).toHaveTextContent("—");
  });

  it("shows the whole number of a format without a serial, and the start of it while a part is missing", () => {
    const { rerender } = render(
      <NumberField access={access().given} info={info({ serial: false, number: "BP/2026" })} savedNumber={undefined} />,
    );
    expect(screen.getByTestId("live-number")).toHaveTextContent("BP/2026");

    rerender(<NumberField access={access().given} info={info({ serial: false, number: undefined, prefix: "BP/", complete: false })} savedNumber={undefined} />);
    expect(screen.getByTestId("live-number")).toHaveTextContent("BP/…");
  });

  it("lets the first serial of a new combination be typed, digits only, and tells where it starts", () => {
    const { given, set } = access();
    render(<NumberField access={given} info={info()} savedNumber={undefined} />);

    const box = screen.getByTestId("serial-input");
    expect(box).toBeEnabled();
    expect(screen.getByTestId("live-number")).toHaveTextContent("WI/PRD/");
    fireEvent.change(box, { target: { value: "5a" } });
    expect(set).toHaveBeenCalledWith("serial", "5");
    expect(screen.getByText(/type the serial it starts at/)).toBeInTheDocument();
  });

  it("shows the next serial, disabled, for a combination that already has numbers", () => {
    render(<NumberField access={access().given} info={info({ started: true, nextSerial: 7 })} savedNumber={undefined} />);

    const box = screen.getByTestId("serial-input");
    expect(box).toBeDisabled();
    expect(box).toHaveValue("07");
    expect(screen.getByText(/already has numbers/)).toBeInTheDocument();
  });

  it("offers no box to a person who may not type the serial", () => {
    render(<NumberField access={access({}, []).given} info={info({ canStart: false })} savedNumber={undefined} />);

    expect(screen.queryByTestId("serial-input")).toBeNull();
    expect(screen.getByTestId("live-number")).toHaveTextContent("WI/PRD/…");
  });

  it("shows the server's refusal about the serial beside the number", () => {
    const given: FormSlotAccess = { ...access().given, problems: (name) => (name === "serial" ? ["This combination has just started."] : []) };
    render(<NumberField access={given} info={info()} savedNumber={undefined} />);

    expect(screen.getByRole("alert")).toHaveTextContent("just started");
  });
});

describe("Part A and Part B typed freehand (FA11B)", () => {
  it("writes capitals, digits and a hyphen only", () => {
    const { given, set } = access({ partA: "" });
    render(<PartField name="partA" label="Part A" access={given} hint="Typed freehand." />);

    fireEvent.change(screen.getByLabelText("Part A"), { target: { value: "ab c-1/" } });

    expect(set).toHaveBeenCalledWith("partA", "ABC-1");
  });

  it("is read only for a person who may not edit it, and shows the server's words beside it", () => {
    const given: FormSlotAccess = { ...access({ partA: "PRD" }, []).given, problems: () => ["Part A uses capital letters."] };
    render(<PartField name="partA" label="Part A" access={given} hint="Typed freehand." />);

    expect(screen.getByLabelText("Part A")).toBeDisabled();
    expect(screen.getByRole("alert")).toHaveTextContent("capital letters");
  });
});
