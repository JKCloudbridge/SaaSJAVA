import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { RegisterRaisePanel, RegisterRequestPanel } from "./RegisterPanel";
import {
  downloadRegister,
  downloadRegisterDraft,
  drawRegister,
  EMPTY_REGISTER_INPUT,
  readRegisterFile,
  saveBlob,
  type RegisterView,
} from "./register";

vi.mock("./register", async () => ({
  ...(await vi.importActual<typeof import("./register")>("./register")),
  drawRegister: vi.fn(),
  downloadRegister: vi.fn(),
  downloadRegisterDraft: vi.fn(),
  readRegisterFile: vi.fn(),
  saveBlob: vi.fn(),
}));
vi.mock("./RegisterExcelView", () => ({
  RegisterExcelView: ({ label }: { label: string }) => <div data-testid="register-excel">{label}</div>,
}));

function view(extra: Partial<RegisterView> = {}): RegisterView {
  return {
    version: 1,
    documentName: "Walkway checklist",
    requestNo: "DCN-12",
    requestedOn: "2026-10-25",
    documentNo: "WI/PRD/01",
    revNo: "00",
    changeRequested: "Make the walkway wider",
    requestedBy: "User A",
    canComplete: false,
    grid: { columns: [] },
    ...extra,
  } as RegisterView;
}

beforeEach(() => {
  vi.mocked(drawRegister).mockReset().mockResolvedValue({ ok: true, data: view() });
  vi.mocked(downloadRegister).mockReset();
  vi.mocked(downloadRegisterDraft).mockReset();
  vi.mocked(readRegisterFile).mockReset();
  vi.mocked(saveBlob).mockReset();
});

describe("the register a person fills in to ask for a change (FA8)", () => {
  it("shows the form first, with the parts the platform writes drawn by the server", async () => {
    render(<RegisterRaisePanel templateId="t" value={EMPTY_REGISTER_INPUT} onChange={() => undefined} />);

    expect(screen.getByTestId("register-form")).toBeInTheDocument();
    expect(await screen.findByText("Walkway checklist")).toBeInTheDocument();
    expect(drawRegister).toHaveBeenCalledWith("t", EMPTY_REGISTER_INPUT);
  });

  it("switches to the Excel page of the same values and back, without losing what was typed", async () => {
    const value = { ...EMPTY_REGISTER_INPUT, requestNo: "DCN-1" };
    render(<RegisterRaisePanel templateId="t" value={value} onChange={() => undefined} />);
    await screen.findByText("Walkway checklist");

    fireEvent.click(screen.getByTestId("register-tab-excel"));
    expect(screen.getByTestId("register-excel")).toBeInTheDocument();
    expect(screen.queryByTestId("register-form")).not.toBeInTheDocument();
    expect(screen.getByTestId("register-tab-excel")).toHaveAttribute("aria-selected", "true");

    fireEvent.click(screen.getByTestId("register-tab-form"));
    expect(screen.getByTestId("register-request-no")).toHaveValue("DCN-1");
  });

  it("saves the register as an Excel file under the request number", async () => {
    const blob = new Blob(["x"]);
    vi.mocked(downloadRegisterDraft).mockResolvedValue({ ok: true, data: blob });
    render(
      <RegisterRaisePanel templateId="t" value={{ ...EMPTY_REGISTER_INPUT, requestNo: "DCN-1" }} onChange={() => undefined} />,
    );

    fireEvent.click(screen.getByTestId("register-download"));

    await waitFor(() => expect(saveBlob).toHaveBeenCalledWith(blob, "DCN-1.xlsx"));
    expect(downloadRegisterDraft).toHaveBeenCalledWith("t", expect.objectContaining({ requestNo: "DCN-1" }));
  });

  it("shows the server's words when the file cannot be made", async () => {
    vi.mocked(downloadRegisterDraft).mockResolvedValue({
      ok: false,
      failure: { code: "CONFLICT", message: "No revision is in use." },
    });
    render(<RegisterRaisePanel templateId="t" value={EMPTY_REGISTER_INPUT} onChange={() => undefined} />);

    fireEvent.click(screen.getByTestId("register-download"));

    expect(await screen.findByRole("alert")).toHaveTextContent("No revision is in use.");
    expect(saveBlob).not.toHaveBeenCalled();
  });

  it("fills the form from a register that was filled in Excel and says what the file could not give", async () => {
    vi.mocked(readRegisterFile).mockResolvedValue({
      ok: true,
      data: { values: { requestNo: "DCN-7", changeRequested: "Wider" }, notes: ["The signature was left out."] },
    } as never);
    const onChange = vi.fn();
    render(<RegisterRaisePanel templateId="t" value={EMPTY_REGISTER_INPUT} onChange={onChange} />);

    fireEvent.change(screen.getByLabelText("Choose the filled register"), {
      target: { files: [new File(["x"], "register.xlsx", { type: "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" })] },
    });

    await waitFor(() =>
      expect(onChange).toHaveBeenCalledWith({ ...EMPTY_REGISTER_INPUT, requestNo: "DCN-7", changeRequested: "Wider" }),
    );
    expect(screen.getByRole("status")).toHaveTextContent("The signature was left out.");
  });

  it("refuses a file that is not a workbook before it is sent anywhere", async () => {
    render(<RegisterRaisePanel templateId="t" value={EMPTY_REGISTER_INPUT} onChange={() => undefined} />);

    fireEvent.change(screen.getByLabelText("Choose the filled register"), {
      target: { files: [new File(["x"], "register.csv", { type: "text/csv" })] },
    });

    expect(await screen.findByRole("alert")).toHaveTextContent("Excel workbook");
    expect(readRegisterFile).not.toHaveBeenCalled();
  });

  it("shows the server's own words when it refuses the file", async () => {
    vi.mocked(readRegisterFile).mockResolvedValue({
      ok: false,
      failure: { code: "VALIDATION_ERROR", message: "Refused.", fields: { file: ["Text in B9 is not part of the register."] } },
    });
    render(<RegisterRaisePanel templateId="t" value={EMPTY_REGISTER_INPUT} onChange={() => undefined} />);

    fireEvent.change(screen.getByLabelText("Choose the filled register"), {
      target: { files: [new File(["x"], "register.xlsx", { type: "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" })] },
    });

    expect(await screen.findByRole("alert")).toHaveTextContent("Text in B9 is not part of the register.");
  });
});

describe("the register of a request that exists (FA8)", () => {
  it("offers the form and the Excel page of the stored values, and the file of them", async () => {
    const blob = new Blob(["x"]);
    vi.mocked(downloadRegister).mockResolvedValue({ ok: true, data: blob });
    render(<RegisterRequestPanel templateId="t" requestId="q" view={view()} onChanged={() => undefined} />);

    expect(screen.getByTestId("register-view-form")).toBeInTheDocument();
    fireEvent.click(screen.getByTestId("register-tab-excel"));
    expect(screen.getByTestId("register-excel")).toBeInTheDocument();
    fireEvent.click(screen.getByTestId("register-download"));

    await waitFor(() => expect(saveBlob).toHaveBeenCalledWith(blob, "DCN-12.xlsx"));
    expect(downloadRegister).toHaveBeenCalledWith("t", "q");
  });

  it("offers no upload: a request is never changed by a file after it is raised", () => {
    render(<RegisterRequestPanel templateId="t" requestId="q" view={view()} onChanged={() => undefined} />);

    expect(screen.queryByTestId("register-upload")).not.toBeInTheDocument();
    expect(screen.queryByLabelText("Choose the filled register")).not.toBeInTheDocument();
  });
});
