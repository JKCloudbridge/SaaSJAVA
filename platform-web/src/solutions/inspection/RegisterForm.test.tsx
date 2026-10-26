import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { RegisterRaiseForm, RegisterViewForm } from "./RegisterForm";
import { completeRegister, EMPTY_REGISTER_INPUT, type RegisterView } from "./register";

vi.mock("./register", async () => ({
  ...(await vi.importActual<typeof import("./register")>("./register")),
  completeRegister: vi.fn(),
}));

function view(extra: Partial<RegisterView> = {}): RegisterView {
  return {
    version: 3,
    documentName: "Walkway checklist",
    requestNo: "DCN-12",
    requestedOn: "2026-10-25",
    documentNo: "WI/PRD/01",
    revNo: "00",
    changeRequested: "Make the walkway wider",
    requestedBy: "User A",
    canComplete: false,
    grid: {},
    ...extra,
  } as RegisterView;
}

beforeEach(() => {
  vi.mocked(completeRegister).mockReset();
});

describe("the register form of a person who asks for a change (FA8)", () => {
  it("shows what the platform writes, read only, beside the boxes the person types", () => {
    render(
      <RegisterRaiseForm facts={view()} value={EMPTY_REGISTER_INPUT} onChange={() => undefined} />,
    );

    expect(screen.getByText("Walkway checklist")).toBeInTheDocument();
    expect(screen.getByText("WI/PRD/01")).toBeInTheDocument();
    expect(screen.getByText("00")).toBeInTheDocument();
    expect(screen.getByText("User A")).toBeInTheDocument();
    expect(screen.getByLabelText("Request No:-")).toBeEnabled();
    expect(screen.getByLabelText("Change Requested -")).toBeEnabled();
    expect(screen.queryByLabelText(/Approved By/)).not.toBeInTheDocument();
    expect(screen.queryByLabelText(/New Rev/)).not.toBeInTheDocument();
  });

  it("types the request number by hand, up to the length the server also allows", () => {
    const onChange = vi.fn();
    render(<RegisterRaiseForm facts={undefined} value={EMPTY_REGISTER_INPUT} onChange={onChange} />);

    const box = screen.getByTestId("register-request-no");
    fireEvent.change(box, { target: { value: "D" } });

    expect(box).toHaveAttribute("maxlength", "30");
    expect(onChange).toHaveBeenLastCalledWith({ ...EMPTY_REGISTER_INPUT, requestNo: "D" });
  });

  it("sends each typed box on its own key and leaves the rest as they were", () => {
    const onChange = vi.fn();
    render(
      <RegisterRaiseForm
        facts={undefined}
        value={{ ...EMPTY_REGISTER_INPUT, requestNo: "DCN-1" }}
        onChange={onChange}
      />,
    );

    fireEvent.change(screen.getByTestId("register-basis"), { target: { value: "A" } });
    fireEvent.change(screen.getByTestId("register-horizontal"), { target: { value: "B" } });
    fireEvent.change(screen.getByTestId("register-obsoleted"), { target: { value: "C" } });

    expect(onChange).toHaveBeenNthCalledWith(1, { ...EMPTY_REGISTER_INPUT, requestNo: "DCN-1", basisForChange: "A" });
    expect(onChange).toHaveBeenNthCalledWith(2, {
      ...EMPTY_REGISTER_INPUT,
      requestNo: "DCN-1",
      horizontalDeployment: "B",
    });
    expect(onChange).toHaveBeenNthCalledWith(3, { ...EMPTY_REGISTER_INPUT, requestNo: "DCN-1", obsoletedFormat: "C" });
  });
});

describe("the register of a request that exists (FA8)", () => {
  it("is read only for a person who does not decide the request", () => {
    render(<RegisterViewForm templateId="t" requestId="q" view={view()} onSaved={() => undefined} />);

    expect(screen.getByText("Make the walkway wider")).toBeInTheDocument();
    expect(screen.queryByTestId("register-save")).not.toBeInTheDocument();
    expect(screen.queryByLabelText("Approved By:")).not.toBeInTheDocument();
    expect(screen.getByText("Written when the revision is published")).toBeInTheDocument();
    expect(screen.getByText("Stamped when the revision is published")).toBeInTheDocument();
  });

  it("shows the new revision and its day once the platform wrote them, and never as a box", () => {
    render(
      <RegisterViewForm
        templateId="t"
        requestId="q"
        view={view({ newRevNo: "01", newRevisionAt: "2026-10-26T08:00:00Z", canComplete: true })}
        onSaved={() => undefined}
      />,
    );

    expect(screen.getByTestId("register-new-rev")).toHaveTextContent("01");
    expect(screen.getByTestId("register-new-date")).toBeInTheDocument();
    expect(screen.queryByLabelText(/New Rev/)).not.toBeInTheDocument();
  });

  it("lets the people who decide complete the bottom and saves it against the version they read", async () => {
    const saved = view({ version: 4, canComplete: true, department: "Plant 1" });
    vi.mocked(completeRegister).mockResolvedValue({ ok: true, data: saved });
    const onSaved = vi.fn();
    render(<RegisterViewForm templateId="t" requestId="q" view={view({ canComplete: true })} onSaved={onSaved} />);

    fireEvent.change(screen.getByLabelText("Department:"), { target: { value: "Plant 1" } });
    fireEvent.change(screen.getByLabelText("Is master list updated with respect to latest revision? Yes/No"), {
      target: { value: "NO" },
    });
    fireEvent.click(screen.getByTestId("register-save"));

    await waitFor(() => expect(onSaved).toHaveBeenCalledWith(saved));
    expect(completeRegister).toHaveBeenCalledWith(
      "t",
      "q",
      expect.objectContaining({ version: 3, department: "Plant 1", masterListUpdated: "NO", serverUpdated: "" }),
    );
    expect(screen.getByRole("status")).toHaveTextContent("The register was saved.");
  });

  it("shows the server's words and the boxes it names when it refuses, and keeps what was typed", async () => {
    vi.mocked(completeRegister).mockResolvedValue({
      ok: false,
      failure: {
        code: "VALIDATION_ERROR",
        message: "Some boxes need attention.",
        fields: { effectiveOn: ["The effective date must be a day."] },
      },
    });
    render(<RegisterViewForm templateId="t" requestId="q" view={view({ canComplete: true })} onSaved={vi.fn()} />);

    fireEvent.change(screen.getByLabelText("Department:"), { target: { value: "Plant 1" } });
    fireEvent.click(screen.getByTestId("register-save"));

    const alerts = await screen.findAllByRole("alert");
    expect(alerts[0]).toHaveTextContent("Some boxes need attention.");
    expect(alerts[1]).toHaveTextContent("The effective date must be a day.");
    expect(screen.getByLabelText("Department:")).toHaveValue("Plant 1");
  });
});
