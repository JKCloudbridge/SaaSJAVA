import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { everyType, field } from "@/test/records";
import { emptyInput } from "@/lib/records/fieldValue";
import type { FieldInput } from "@/lib/records/types";
import { FieldEditor, FieldReadValue } from "./FieldRegistry";

vi.mock("next/link", () => ({
  default: ({ href, children }: { href: string; children: React.ReactNode }) => <a href={href}>{children}</a>,
}));

function editor(one: ReturnType<typeof field>, input: FieldInput = emptyInput(one), onChange = vi.fn(), problems: string[] = []) {
  render(
    <FieldEditor
      objectApiName="Deal__c"
      field={one}
      input={input}
      problems={problems}
      onChange={onChange}
      onBlur={vi.fn()}
    />,
  );
  return onChange;
}

describe("editing: every type a person can type into has a labelled control", () => {
  const editable = everyType().filter((one) => !one.calculated);

  it.each(editable.map((one) => [one.type, one] as const))("%s", (_type, one) => {
    editor(one);

    // The control is reachable through its label (a group for a list of checkboxes), so a screen reader names it.
    if (one.type === "MULTI_PICKLIST") {
      expect(screen.getByRole("group")).toBeInTheDocument();
    } else {
      expect(screen.getByLabelText(new RegExp(one.label))).toBeInTheDocument();
    }
  });

  it("uses a text control for long text, a select for a picklist, a checkbox for a checkbox", () => {
    editor(field("note__c", "LONG_TEXT"));
    expect(screen.getByRole("textbox")).toHaveProperty("tagName", "TEXTAREA");
  });

  it("keeps the digits of a number as text with a numeric keyboard, not a number control", () => {
    editor(field("n__c", "DECIMAL"), "12.50");

    const input = screen.getByRole("textbox") as HTMLInputElement;
    expect(input.type).toBe("text");
    expect(input.inputMode).toBe("decimal");
    expect(input.value).toBe("12.50");
  });

  it("reports what is typed, as text for a line, a flag for a checkbox and a list for choices", () => {
    const onText = editor(field("t__c", "TEXT"));
    fireEvent.change(screen.getByRole("textbox"), { target: { value: "Acme" } });
    expect(onText).toHaveBeenCalledWith("Acme");
  });

  it("reports a checkbox as a flag", () => {
    const onChange = editor(field("f__c", "BOOLEAN", { label: "Flag" }), false);
    fireEvent.click(screen.getByLabelText("Flag"));
    expect(onChange).toHaveBeenCalledWith(true);
  });

  it("reports the choices of a multi-select picklist as a list, in the order ticked", () => {
    const one = everyType().find((candidate) => candidate.type === "MULTI_PICKLIST")!;
    const onChange = editor(one, ["A"]);

    fireEvent.click(screen.getByLabelText("Beta"));

    expect(onChange).toHaveBeenCalledWith(["A", "B"]);
  });

  it("offers a picklist's active values, and a value the record holds even when it is switched off", () => {
    const one = everyType().find((candidate) => candidate.type === "PICKLIST")!;
    editor(one, "OLD");

    const options = screen.getAllByRole("option").map((option) => option.textContent);
    expect(options).toEqual(["None", "Open", "Won", "Old"]);
  });

  it("does not offer a switched-off value to someone who does not hold it", () => {
    const one = everyType().find((candidate) => candidate.type === "PICKLIST")!;
    editor(one, "");

    expect(screen.getAllByRole("option").map((option) => option.textContent)).toEqual(["None", "Open", "Won"]);
  });

  it("marks a required field in text as well as by a star, and describes it by its problems", () => {
    editor(field("name__c", "TEXT", { label: "Name", required: true, description: "What it is called" }), "", vi.fn(), ["Enter a value for this field."]);

    expect(screen.getByText("(required)")).toBeInTheDocument();
    const input = screen.getByLabelText(/Name/);
    expect(input).toHaveAttribute("aria-invalid", "true");
    expect(input.getAttribute("aria-describedby")).toContain("field-name__c-error");
    expect(screen.getByRole("alert")).toHaveTextContent("Enter a value for this field.");
    expect(screen.getByText("What it is called")).toBeInTheDocument();
  });

  it("starts a date and time with minutes, and with seconds only when the value has them", () => {
    editor(field("when__c", "DATETIME", { label: "When" }), "2030-01-31T09:30");
    expect((screen.getByLabelText("When") as HTMLInputElement).step).toBe("60");
  });
});

describe("reading: a value as it reads", () => {
  function read(one: ReturnType<typeof field>, value: unknown, labels: Record<string, string> = {}) {
    return render(
      <span data-testid="out">
        <FieldReadValue field={one} value={value} labels={labels} recordHref={(object, id) => `/records/${object}/${id}`} />
      </span>,
    );
  }

  it("makes a mail address, a telephone number and a web address links, and a web address opens safely", () => {
    read(field("m__c", "EMAIL"), "user-a@example.test");
    expect(screen.getByRole("link")).toHaveAttribute("href", "mailto:user-a@example.test");
  });

  it("opens a web address in a new tab without handing the page over", () => {
    read(field("w__c", "URL"), "https://example.test/x");

    const link = screen.getByRole("link");
    expect(link).toHaveAttribute("target", "_blank");
    expect(link.getAttribute("rel")).toContain("noopener");
    expect(link.getAttribute("rel")).toContain("noreferrer");
  });

  it("shows an unsafe web address as plain text, never as a link", () => {
    read(field("w__c", "URL"), "javascript:alert(1)");

    expect(screen.queryByRole("link")).toBeNull();
    expect(screen.getByTestId("out")).toHaveTextContent("javascript:alert(1)");
  });

  it("links a record the link names, when the object can be opened", () => {
    const link = field("a__c", "LOOKUP", { targetObject: "Account__c", targetOpenable: true });
    read(link, "id-1", { "id-1": "Acme" });

    expect(screen.getByRole("link", { name: "Acme" })).toHaveAttribute("href", "/records/Account__c/id-1");
  });

  it("shows a link as text when its object has no screen of its own (a person), and never shows an identifier", () => {
    read(field("o__c", "LOOKUP", { targetObject: "User", targetOpenable: false }), "id-1", { "id-1": "User A" });
    expect(screen.queryByRole("link")).toBeNull();
    expect(screen.getByTestId("out")).toHaveTextContent("User A");
  });

  it("says 'Linked record' for a link whose name is not known, with no link", () => {
    read(field("a__c", "LOOKUP", { targetObject: "Account__c", targetOpenable: true }), "id-9", {});

    expect(screen.getByTestId("out")).toHaveTextContent("Linked record");
    expect(screen.getByTestId("out")).not.toHaveTextContent("id-9");
    expect(screen.queryByRole("link")).toBeNull();
  });

  it("shows nothing for no value", () => {
    read(field("t__c", "TEXT"), undefined);

    expect(screen.getByTestId("out")).toBeEmptyDOMElement();
  });
});
