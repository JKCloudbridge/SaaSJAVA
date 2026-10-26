import { useEffect, useState } from "react";
import { api } from "@/lib/api/client";
import type { ApiFailure } from "@/lib/api/errors";
import { FORGERY_COOKIE, FORGERY_HEADER, readCookie } from "@/lib/api/forgery";
import type { components } from "@/lib/api/generated/schema";
import { call, type Outcome } from "@/lib/records/call";

/**
 * The calls of the register form of a change request (Functional track FA8, ADR-0202) and how it is written on a screen.
 * The register is the Document Change Register: one set of values, drawn as a form and as an Excel page. Every call is made
 * the way the record screens make theirs (`call`), and what comes back is the API's own data or its own failure, in its own
 * words. Nothing here decides a permission: `canComplete` is the API's, and every request is decided again.
 */

export type RegisterInput = components["schemas"]["RegisterInput"];
export type RegisterView = components["schemas"]["RegisterView"];
export type RegisterCompletion = components["schemas"]["RegisterCompletion"];
export type RegisterFileView = components["schemas"]["RegisterFileView"];

/** The most characters of each typed box (the same bounds as the server; the server checks again). */
export const REGISTER_LIMITS = {
  requestNo: 30,
  changeRequested: 4000,
  basisForChange: 4000,
  horizontalDeployment: 2000,
  obsoletedFormat: 1000,
  department: 1000,
  approvedBy: 2000,
  signatureNote: 500,
} as const;

/** The boxes a person types when they ask, all empty. */
export const EMPTY_REGISTER_INPUT: RegisterInput = {
  requestNo: "",
  changeRequested: "",
  basisForChange: "",
  horizontalDeployment: "",
  obsoletedFormat: "",
  obsoletedEffectiveOn: "",
};

const UNREADABLE: ApiFailure = {
  code: "UNREADABLE_RESPONSE",
  message: "The server answered in a way this application does not understand.",
};

const XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

function unwrap<T>(answer: Promise<Outcome<{ data: T } | undefined>>): Promise<Outcome<T>> {
  return answer.then((outcome): Outcome<T> => {
    if (!outcome.ok) {
      return outcome;
    }
    return outcome.data?.data === undefined ? { ok: false, failure: UNREADABLE } : { ok: true, data: outcome.data.data };
  });
}

/** The register of a request the member may see. */
export function getRegister(templateId: string, requestId: string): Promise<Outcome<RegisterView>> {
  return unwrap(
    call(() =>
      api.GET("/api/v1/templates/{templateId}/change-requests/{requestId}/register", {
        params: { path: { templateId, requestId } },
      }),
    ),
  );
}

/** Completes the bottom of the register while the request is in progress. */
export function completeRegister(
  templateId: string,
  requestId: string,
  body: RegisterCompletion,
): Promise<Outcome<RegisterView>> {
  return unwrap(
    call(() =>
      api.PUT("/api/v1/templates/{templateId}/change-requests/{requestId}/register", {
        params: { path: { templateId, requestId } },
        body,
      }),
    ),
  );
}

/** The register as it would be drawn for a request about the template with these typed boxes; nothing is stored. */
export function drawRegister(templateId: string, body: RegisterInput): Promise<Outcome<RegisterView>> {
  return unwrap(
    call(() => api.POST("/api/v1/templates/{templateId}/change-requests/register/page", { params: { path: { templateId } }, body })),
  );
}

/** The register of a request as an Excel file. */
export function downloadRegister(templateId: string, requestId: string): Promise<Outcome<Blob>> {
  return call(() =>
    api.GET("/api/v1/templates/{templateId}/change-requests/{requestId}/register/file", {
      params: { path: { templateId, requestId } },
      parseAs: "blob",
    }),
  ).then((outcome): Outcome<Blob> => (outcome.ok ? { ok: true, data: outcome.data as unknown as Blob } : outcome));
}

/** The register for a request about the template, with these typed boxes, as an Excel file to fill there. */
export function downloadRegisterDraft(templateId: string, body: RegisterInput): Promise<Outcome<Blob>> {
  return call(() =>
    api.POST("/api/v1/templates/{templateId}/change-requests/register/file", {
      params: { path: { templateId } },
      body,
      parseAs: "blob",
    }),
  ).then((outcome): Outcome<Blob> => (outcome.ok ? { ok: true, data: outcome.data as unknown as Blob } : outcome));
}

/**
 * Sends a filled register file to be read: the typed boxes come back to fill the form with. The file goes up as it is, as
 * one raw body (it cannot be one of the generated client's typed calls) and is kept nowhere.
 */
export function readRegisterFile(templateId: string, file: Blob): Promise<Outcome<RegisterFileView>> {
  const address = `/api/v1/templates/${encodeURIComponent(templateId)}/change-requests/register/read`;
  return unwrap(
    call(async () => {
      const token = readCookie(FORGERY_COOKIE);
      const response = await fetch(address, {
        method: "POST",
        credentials: "include",
        headers: { "Content-Type": "application/octet-stream", ...(token ? { [FORGERY_HEADER]: token } : {}) },
        body: file,
      });
      const body: unknown = await response.json().catch(() => undefined);
      return response.ok
        ? { data: body as { data: RegisterFileView } | undefined, response }
        : { error: body, response };
    }),
  );
}

/** Saves a blob under a name, the way the browser saves any file. */
export function saveBlob(blob: Blob, fileName: string): void {
  const url = URL.createObjectURL(blob);
  const link = document.createElement("a");
  link.href = url;
  link.download = fileName;
  link.click();
  URL.revokeObjectURL(url);
}

/** The name a register is saved under: the request number, with anything odd made a hyphen. */
export function registerFileName(requestNo: string): string {
  const base = requestNo.trim() === "" ? "Change-request-register" : requestNo.trim().replace(/[^A-Za-z0-9._-]/g, "-");
  return `${base.slice(0, 40)}.xlsx`;
}

/** Whether a file looks like an Excel workbook (the server checks the bytes). */
export function looksLikeWorkbook(file: File): boolean {
  return file.name.toLowerCase().endsWith(".xlsx") || file.type === XLSX;
}

export type RegisterState =
  | { status: "loading" }
  | { status: "ready"; view: RegisterView }
  | { status: "failed"; failure: ApiFailure };

/** The register of one request, read again whenever `refresh` changes. */
export function useRegister(
  templateId: string,
  requestId: string,
  refresh: number,
): { state: RegisterState; set: (view: RegisterView) => void } {
  const [loaded, setLoaded] = useState<{ key: string; outcome: Outcome<RegisterView> } | null>(null);
  const key = `${templateId}|${requestId}|${refresh}`;
  useEffect(() => {
    let live = true;
    void getRegister(templateId, requestId).then((outcome) => {
      if (live) {
        setLoaded({ key, outcome });
      }
    });
    return () => {
      live = false;
    };
  }, [templateId, requestId, key]);
  const set = (view: RegisterView) => setLoaded({ key, outcome: { ok: true, data: view } });
  if (!loaded) {
    return { state: { status: "loading" }, set };
  }
  return {
    state: loaded.outcome.ok
      ? { status: "ready", view: loaded.outcome.data }
      : { status: "failed", failure: loaded.outcome.failure },
    set,
  };
}
