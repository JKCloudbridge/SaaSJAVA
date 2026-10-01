/**
 * Types of the platform API, generated from platform-api-contract/src/main/resources/openapi/platform-api-v1.json.
 * Do not edit by hand: change the API, regenerate the document, then run `npm run api:generate`.
 */

export interface paths {
    "/api/v1/platform/status": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Platform status
         * @description Succeeds when the API is running and its database answered a query.
         */
        get: operations["getPlatformStatus"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/tenant/current": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * The organization of this host name
         * @description Answers with the organization the request's host name addresses. A host that addresses no organization, or an unknown one, is NOT_FOUND; a suspended, deactivated or not yet open one is TENANT_UNAVAILABLE. The organization can never be chosen by a header, parameter or body.
         */
        get: operations["getCurrentTenant"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
}
export type webhooks = Record<string, never>;
export interface components {
    schemas: {
        ApiError: {
            code: components["schemas"]["ErrorCode"];
            fields?: {
                [key: string]: string[];
            };
            message: string;
            requestId: string;
            traceId?: string;
        };
        ApiErrorResponse: {
            error: components["schemas"]["ApiError"];
        };
        ApiResponsePlatformStatus: {
            data: components["schemas"]["PlatformStatus"];
        };
        ApiResponseTenantSummary: {
            data: components["schemas"]["TenantSummary"];
        };
        /**
         * @description Stable machine-readable error code. Branch on this, never on the message.
         * @enum {string}
         */
        ErrorCode: "VALIDATION_ERROR" | "MALFORMED_REQUEST" | "UNAUTHENTICATED" | "FORBIDDEN" | "NOT_FOUND" | "METHOD_NOT_ALLOWED" | "NOT_ACCEPTABLE" | "CONFLICT" | "CONCURRENT_MODIFICATION" | "PAYLOAD_TOO_LARGE" | "UNSUPPORTED_MEDIA_TYPE" | "RATE_LIMITED" | "INTERNAL_ERROR" | "SERVICE_UNAVAILABLE" | "TENANT_UNAVAILABLE";
        Pagination: {
            hasMore: boolean;
            /** Format: int32 */
            limit: number;
            nextCursor?: string;
        };
        PlatformStatus: {
            apiVersion: string;
            /** Format: date-time */
            databaseTime: string;
            /** Format: date-time */
            serverTime: string;
            service: string;
        };
        TenantSummary: {
            displayName: string;
            slug: string;
        };
    };
    responses: never;
    parameters: never;
    requestBodies: never;
    headers: never;
    pathItems: never;
}
export type $defs = Record<string, never>;
export interface operations {
    getPlatformStatus: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ApiResponsePlatformStatus"];
                };
            };
            /** @description Error. The code says what went wrong; the message is safe to show. */
            default: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ApiErrorResponse"];
                };
            };
        };
    };
    getCurrentTenant: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ApiResponseTenantSummary"];
                };
            };
            /** @description Error. The code says what went wrong; the message is safe to show. */
            default: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ApiErrorResponse"];
                };
            };
        };
    };
}
