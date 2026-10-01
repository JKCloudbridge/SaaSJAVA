import type { components } from "./generated/schema";
import { REQUEST_ID_HEADER, TRACE_ID_HEADER } from "./client";

export type ErrorCode = components["schemas"]["ErrorCode"];

/**
 * What a failed call looks like to the rest of the frontend, whatever went wrong: an error answer in the API's
 * error model, an answer that is not that model (for example a gateway error page), or no answer at all.
 */
export interface ApiFailure {
  /** The API's stable code, or one of the two client-side codes below. Branch on this, never on the message. */
  code: ErrorCode | "UNREADABLE_RESPONSE" | "NETWORK_ERROR";
  /** Safe to show to a person. */
  message: string;
  /** Invalid fields, for validation errors. */
  fields?: Record<string, string[]>;
  /** The request ID: quote it to support, it finds the request in every log. */
  requestId?: string;
  traceId?: string;
  status?: number;
}

/**
 * Every code the API can send. Typed as a record over the generated type, so when the backend adds a code the
 * frontend stops compiling until it is listed here: contract drift shows up at build time, not in production.
 */
const KNOWN_CODES: Record<ErrorCode, true> = {
  VALIDATION_ERROR: true,
  MALFORMED_REQUEST: true,
  UNAUTHENTICATED: true,
  FORBIDDEN: true,
  NOT_FOUND: true,
  METHOD_NOT_ALLOWED: true,
  NOT_ACCEPTABLE: true,
  CONFLICT: true,
  CONCURRENT_MODIFICATION: true,
  PAYLOAD_TOO_LARGE: true,
  UNSUPPORTED_MEDIA_TYPE: true,
  RATE_LIMITED: true,
  INTERNAL_ERROR: true,
  SERVICE_UNAVAILABLE: true,
  TENANT_UNAVAILABLE: true,
};

function isErrorCode(value: unknown): value is ErrorCode {
  return typeof value === "string" && Object.hasOwn(KNOWN_CODES, value);
}

/** Builds a failure from an answer the API gave. `body` is the parsed error body, when there was one. */
export function failureFromResponse(body: unknown, response: Response): ApiFailure {
  const requestId = response.headers.get(REQUEST_ID_HEADER) ?? undefined;
  const traceId = response.headers.get(TRACE_ID_HEADER) ?? undefined;
  const error = (body as { error?: Partial<components["schemas"]["ApiError"]> } | undefined)?.error;
  if (error && isErrorCode(error.code) && typeof error.message === "string") {
    return {
      code: error.code,
      message: error.message,
      fields: error.fields,
      requestId: error.requestId ?? requestId,
      traceId: error.traceId ?? traceId,
      status: response.status,
    };
  }
  return {
    code: "UNREADABLE_RESPONSE",
    message: "The server answered in a way this application does not understand.",
    requestId,
    traceId,
    status: response.status,
  };
}

/** Builds a failure for a call that never got an answer (offline, refused connection, blocked request). */
export function failureFromNetworkError(): ApiFailure {
  return {
    code: "NETWORK_ERROR",
    message: "The server could not be reached. Check your connection and try again.",
  };
}
