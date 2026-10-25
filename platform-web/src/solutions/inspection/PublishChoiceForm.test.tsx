import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { getSuggestionOptions } from "./api";
import type { PublishPrefill } from "./changeRequests";
import { PublishChoiceForm } from "./PublishChoiceForm";

vi.mock("./api", async () => ({
  ...(await vi.importActual<typeof import("./api")>("./api")),
  getSuggestionOptions: vi.fn(),
}));

const OPTIONS = {
  locations: [
    {
      value: "PLANT1",
      label: "Plant 1",
      departments: [
        { value: "PRD", label: "Production", configured: true },
        { value: "QLT", label: "Quality", configured: true },
      ],
    },
    { value: "HEAD", label: "Head office", departments: [{ value: "HR", label: "Human resources", configured: true }] },
  ],
};

function prefill(extra: Partial<PublishPrefill> = {}): PublishPrefill {
  return { suggested: [], suggestionDropped: 0, viewOnly: false, allowDownload: false, ...extra };
}

beforeEach(() => {
  vi.mocked(getSuggestionOptions).mockReset().mockResolvedValue({ ok: true, data: OPTIONS } as never);
});

const departmentBoxes = () => within(screen.getByRole("group", { name: "Departments" })).queryAllByRole("checkbox");

describe("the window of the last approval of a change request (FA8)", () => {
  it("offers nothing to confirm until at least one department is chosen", async () => {
    const onChange = vi.fn();
    render(<PublishChoiceForm templateId="t" prefill={prefill()} onChange={onChange} />);

    await screen.findByText("Choose a location");
    expect(onChange).toHaveBeenLastCalledWith(null);
    expect(screen.getByText("Nothing is chosen yet. Add at least one department.")).toBeInTheDocument();
  });

  it("starts from the audience the step that asked for a choice suggested, and says so", async () => {
    const onChange = vi.fn();
    render(
      <PublishChoiceForm
        templateId="t"
        prefill={prefill({ suggested: [{ location: "PLANT1", department: "PRD" }] })}
        onChange={onChange}
      />,
    );

    expect(await screen.findByTestId("publish-choice-suggested")).toBeInTheDocument();
    expect(screen.getByRole("list", { name: "Published to" })).toHaveTextContent("Plant 1 · Production");
    await waitFor(() =>
      expect(onChange).toHaveBeenLastCalledWith([
        { key: "location", value: "PLANT1" },
        { key: "department", value: "PLANT1/PRD" },
      ]),
    );
  });

  it("starts from the options the template has now and sends them as choices", async () => {
    const onChange = vi.fn();
    render(
      <PublishChoiceForm
        templateId="t"
        prefill={prefill({ suggested: [{ location: "PLANT1", department: "PRD" }], viewOnly: true })}
        onChange={onChange}
      />,
    );
    await screen.findByText("Choose a location");

    expect(screen.getByRole("checkbox", { name: "View only" })).toBeChecked();
    expect(screen.getByRole("checkbox", { name: "Allow download" })).not.toBeChecked();
    fireEvent.click(screen.getByRole("checkbox", { name: "Allow download" }));

    await waitFor(() =>
      expect(onChange).toHaveBeenLastCalledWith([
        { key: "location", value: "PLANT1" },
        { key: "department", value: "PLANT1/PRD" },
        { key: "option", value: "viewOnly" },
        { key: "option", value: "allowDownload" },
      ]),
    );
  });

  it("adds the ticked departments of a location, then another location, as for a new template", async () => {
    const onChange = vi.fn();
    render(<PublishChoiceForm templateId="t" prefill={prefill()} onChange={onChange} />);
    await screen.findByText("Choose a location");

    fireEvent.change(screen.getByRole("combobox", { name: "Location" }), { target: { value: "PLANT1" } });
    expect(departmentBoxes()).toHaveLength(2);
    fireEvent.click(departmentBoxes()[0]);
    fireEvent.click(departmentBoxes()[1]);
    fireEvent.click(screen.getByRole("button", { name: "Add the ticked departments" }));
    fireEvent.change(screen.getByRole("combobox", { name: "Location" }), { target: { value: "HEAD" } });
    fireEvent.click(departmentBoxes()[0]);
    fireEvent.click(screen.getByRole("button", { name: "Add the ticked departments" }));

    await waitFor(() =>
      expect(onChange).toHaveBeenLastCalledWith([
        { key: "location", value: "PLANT1" },
        { key: "location", value: "HEAD" },
        { key: "department", value: "PLANT1/PRD" },
        { key: "department", value: "PLANT1/QLT" },
        { key: "department", value: "HEAD/HR" },
      ]),
    );
  });

  it("takes a department out again, and chooses nothing when the list is empty", async () => {
    const onChange = vi.fn();
    render(
      <PublishChoiceForm
        templateId="t"
        prefill={prefill({ suggested: [{ location: "PLANT1", department: "PRD" }] })}
        onChange={onChange}
      />,
    );
    await screen.findByText("Choose a location");

    fireEvent.click(screen.getByRole("button", { name: "Remove Plant 1 · Production" }));

    await waitFor(() => expect(onChange).toHaveBeenLastCalledWith(null));
  });

  it("says how many suggested places the lists no longer offer", async () => {
    render(
      <PublishChoiceForm templateId="t" prefill={prefill({ suggestionDropped: 2 })} onChange={() => undefined} />,
    );

    expect(await screen.findByRole("note")).toHaveTextContent("2 suggested places are no longer offered");
  });

  it("shows the server's words when the locations cannot be read", async () => {
    vi.mocked(getSuggestionOptions).mockResolvedValue({
      ok: false,
      failure: { code: "NOT_FOUND", message: "This template does not exist." },
    });
    render(<PublishChoiceForm templateId="t" prefill={undefined} onChange={() => undefined} />);

    expect(await screen.findByRole("alert")).toHaveTextContent("This template does not exist.");
  });
});
