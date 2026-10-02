/**
 * Types of the platform API, generated from platform-api-contract/src/main/resources/openapi/platform-api-v1.json.
 * Do not edit by hand: change the API, regenerate the document, then run `npm run api:generate`.
 */

export interface paths {
    "/api/v1/auth/csrf": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Receive the forgery-protection cookie
         * @description Answers 204 and sets the XSRF-TOKEN cookie. A browser page reads that cookie and sends its value in the X-XSRF-TOKEN header with every state-changing request, which the server compares with the cookie.
         */
        get: operations["getCsrfToken"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/auth/invitations/accept": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Accept an invitation as a signed-in person
         * @description For a person who already has an account and is signed in as the invited address. Creates the membership and answers the organization. Anybody else, and any unusable link, gets the same VALIDATION_ERROR on the field token, and the link stays usable for the right person.
         */
        post: operations["acceptInvitation"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/auth/invitations/accept-new": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Accept an invitation by choosing a name and a password
         * @description Public: the token from the mailed link is the proof. Creates the account and the membership. Answers the organization to sign in at. An unusable link, or an address that has an account by now, is a VALIDATION_ERROR on the field token; a password that breaks the policy is one on password and leaves the link usable.
         */
        post: operations["acceptInvitationAsNewPerson"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/auth/invitations/preview": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Read what an invitation link is for
         * @description Public: the token from the mailed link is the proof. Answers the organization's name, the invited address and whether that address already has an account (which decides whether the person chooses a password or signs in). A link that is unknown, used, replaced, revoked or expired is a VALIDATION_ERROR on the field token, always with the same message.
         */
        post: operations["previewInvitation"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/auth/me": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Who the caller is
         * @description The signed-in user. 401 UNAUTHENTICATED without a valid token. Says nothing about what the user may do: permissions are decided elsewhere, per organization.
         */
        get: operations["getCurrentUser"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/auth/password": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Change the password of the signed-in user
         * @description Needs the current password and a new one that meets the password policy. Ends every session of the user, including this one: sign in again afterwards.
         */
        post: operations["changePassword"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/auth/password/forgot": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Ask for a password-reset link
         * @description Always answers 202 with the same text, whether or not the address has an account: if it has an active one, an e-mail with a link follows. Only on the platform host. A locked account can be reset. Too many requests answer 429 RATE_LIMITED.
         */
        post: operations["requestPasswordReset"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/auth/password/reset": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Set a new password with the token from the e-mailed link
         * @description Answers 204 and ends every session of the user, on every device; the person signs in again. A link that is unknown, used, replaced or expired is a VALIDATION_ERROR on the field token. A password that breaks the policy is a VALIDATION_ERROR on the field newPassword and leaves the link usable.
         */
        post: operations["completePasswordReset"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/auth/refresh": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Replace the session tokens
         * @description Uses the refresh cookie. Answers 204 with new cookies, or 401 UNAUTHENTICATED with the cookies removed when the refresh token is unknown, expired, revoked or was already used.
         */
        post: operations["refreshSession"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/auth/sign-in": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Check an email address and a password
         * @description On success answers 204 and sets the short-lived login cookie; the browser then continues with the sign-in page navigation (/api/v1/auth/start) to receive its session cookies. Every kind of failure (unknown address, wrong password, locked, disabled, not yet verified) answers the same 401 UNAUTHENTICATED; too many attempts answer 429 RATE_LIMITED.
         */
        post: operations["signIn"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/auth/sign-out": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * End the current sign-in
         * @description Revokes the tokens the browser presents and removes the cookies. Always answers 204: signing out when not signed in is not an error.
         */
        post: operations["signOut"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/auth/sign-out-all": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * End every sign-in of the user
         * @description Revokes every session and token of the signed-in user, on every device and instance, at once.
         */
        post: operations["signOutEverywhere"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/auth/sign-up": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Start a sign-up with an e-mail address
         * @description Always answers 202 with the same text, whether or not the address already has an account: if it may sign up, an e-mail with a link follows. Only on the platform host. Asking again is the way to have the e-mail sent again; a newer link replaces the older one. Too many requests answer 429 RATE_LIMITED.
         */
        post: operations["requestSignUp"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/auth/sign-up/complete": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Create the account with the token from the e-mailed link
         * @description Answers 204 when the account was created; the person then signs in. A link that is unknown, used, replaced or expired is a VALIDATION_ERROR on the field token, always with the same message. A password that breaks the policy is a VALIDATION_ERROR on the field password and leaves the link usable.
         */
        post: operations["completeSignUp"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/auth/switch": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Ask to continue in another of your organizations
         * @description Answers the destination host and a one-time proof, valid for a minute. The browser opens the destination's switch page with the proof after the #. An organization that does not exist and one the caller does not belong to give the same NOT_FOUND.
         */
        post: operations["switchOrganization"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/auth/switch/complete": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Continue in this organization with the one-time proof
         * @description Public, on an organization host: the proof from the previous host is the credential. On success answers 204 and sets the short-lived login cookie; the browser then continues with the sign-in navigation (/api/v1/auth/start). A proof that is unknown, used, expired, made for another organization, or whose person is not a member here is a VALIDATION_ERROR on the field token, always with the same message.
         */
        post: operations["completeSwitch"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/invitations": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * List the invitations of the organization
         * @description Newest first, with their state: OPEN, EXPIRED, ACCEPTED or REVOKED. For administrators.
         */
        get: operations["listInvitations"];
        put?: never;
        /**
         * Invite an address into the organization
         * @description Always answers 202 with the same text, whether or not the address has an account or is already a member: if it can be invited, an e-mail with a link follows. Inviting an address that has an open invitation sends it again. The invitation grants nothing until accepted. For administrators of the organization of the host. Too many requests answer 429 RATE_LIMITED.
         */
        post: operations["inviteMember"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/invitations/{invitationId}/resend": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Send an open invitation again
         * @description A new link replaces the old one and the time starts again. Answers 202 like the invitation. CONFLICT when the invitation is no longer open; NOT_FOUND for an invitation of another organization.
         */
        post: operations["resendInvitation"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/invitations/{invitationId}/revoke": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Withdraw an open invitation
         * @description Its link stops working at once. CONFLICT when the invitation is no longer open; NOT_FOUND for an invitation of another organization.
         */
        post: operations["revokeInvitation"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/members": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * List the members of the organization
         * @description For the administrators of the organization of the host. NOT_FOUND on the platform host, FORBIDDEN for a member who is not an administrator.
         */
        get: operations["listMembers"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/members/{membershipId}/administrator": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        /**
         * Name a member an administrator, or release them
         * @description A stop-gap until access policies exist. The last administrator cannot be released (CONFLICT). Only an active member can be an administrator.
         */
        put: operations["setMemberAdministrator"];
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/members/{membershipId}/deactivate": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Deactivate a member
         * @description The member is signed out of this organization at once and cannot get back in until reactivated; their other organizations are untouched. CONFLICT when already deactivated or when this is the last administrator. NOT_FOUND for a member of another organization.
         */
        post: operations["deactivateMember"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/members/{membershipId}/reactivate": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Reactivate a deactivated member
         * @description The person can sign in again; sessions that ended do not come back and the administrator marker is not restored. CONFLICT when the member is not deactivated.
         */
        post: operations["reactivateMember"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/organizations": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * The organizations the signed-in person is an active member of
         * @description For the organization switcher, on any host: the answer is the caller's own memberships, not the organization the host names. Each entry carries the host the server built for it.
         */
        get: operations["listMyOrganizations"];
        put?: never;
        /**
         * Found an organization
         * @description The signed-in person creates an organization and becomes its founding administrator. The short name (slug) becomes the first label of the organization's host name. Answers 201 with the host to sign in at. A name or slug that is not acceptable or not free is a VALIDATION_ERROR on its field; a person who already founded as many organizations as allowed is FORBIDDEN. Only on the platform host.
         */
        post: operations["createOrganization"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
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
        AcceptInvitationRequest: {
            displayName: string;
            password: string;
            token: string;
        };
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
        ApiResponseCurrentUser: {
            data: components["schemas"]["CurrentUser"];
        };
        ApiResponseInvitationAccepted: {
            data: components["schemas"]["InvitationAccepted"];
        };
        ApiResponseInvitationPreview: {
            data: components["schemas"]["InvitationPreview"];
        };
        ApiResponseListInvitationView: {
            data: components["schemas"]["InvitationView"][];
        };
        ApiResponseListMemberView: {
            data: components["schemas"]["MemberView"][];
        };
        ApiResponseListOrganizationSummary: {
            data: components["schemas"]["OrganizationSummary"][];
        };
        ApiResponseOrganizationCreated: {
            data: components["schemas"]["OrganizationCreated"];
        };
        ApiResponsePlatformStatus: {
            data: components["schemas"]["PlatformStatus"];
        };
        ApiResponseRequestAccepted: {
            data: components["schemas"]["RequestAccepted"];
        };
        ApiResponseSwitchTarget: {
            data: components["schemas"]["SwitchTarget"];
        };
        ApiResponseTenantSummary: {
            data: components["schemas"]["TenantSummary"];
        };
        ChangePasswordRequest: {
            currentPassword: string;
            newPassword: string;
        };
        CompleteSignUpRequest: {
            displayName: string;
            password: string;
            token: string;
        };
        CompleteSwitchRequest: {
            token: string;
        };
        CreateOrganizationRequest: {
            displayName: string;
            slug: string;
        };
        CurrentUser: {
            displayName: string;
            email: string;
            id: string;
        };
        /**
         * @description Stable machine-readable error code. Branch on this, never on the message.
         * @enum {string}
         */
        ErrorCode: "VALIDATION_ERROR" | "MALFORMED_REQUEST" | "UNAUTHENTICATED" | "FORBIDDEN" | "NOT_FOUND" | "METHOD_NOT_ALLOWED" | "NOT_ACCEPTABLE" | "CONFLICT" | "CONCURRENT_MODIFICATION" | "PAYLOAD_TOO_LARGE" | "UNSUPPORTED_MEDIA_TYPE" | "RATE_LIMITED" | "INTERNAL_ERROR" | "SERVICE_UNAVAILABLE" | "TENANT_UNAVAILABLE";
        ForgotPasswordRequest: {
            email: string;
        };
        InvitationAccepted: {
            displayName: string;
            host: string;
            slug: string;
        };
        InvitationLinkRequest: {
            token: string;
        };
        InvitationPreview: {
            email: string;
            existingAccount?: boolean;
            organizationName: string;
        };
        InvitationView: {
            administrator?: boolean;
            /** Format: date-time */
            createdAt: string;
            email: string;
            /** Format: date-time */
            expiresAt: string;
            /** Format: uuid */
            id: string;
            /** Format: int32 */
            sentCount?: number;
            status: string;
        };
        InviteRequest: {
            administrator?: boolean;
            email: string;
        };
        MemberView: {
            administrator?: boolean;
            displayName: string;
            email: string;
            foundingAdministrator?: boolean;
            /** Format: uuid */
            id: string;
            /** Format: date-time */
            since: string;
            status: string;
            you?: boolean;
        };
        OrganizationCreated: {
            displayName: string;
            host: string;
            slug: string;
        };
        OrganizationSummary: {
            administrator?: boolean;
            displayName: string;
            host: string;
            slug: string;
        };
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
        RequestAccepted: {
            message: string;
        };
        ResetPasswordRequest: {
            newPassword: string;
            token: string;
        };
        SetAdministratorRequest: {
            administrator: boolean;
        };
        SignInRequest: {
            email: string;
            password: string;
        };
        SignUpRequest: {
            email: string;
        };
        SwitchOrganizationRequest: {
            slug: string;
        };
        SwitchTarget: {
            host: string;
            token: string;
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
    getCsrfToken: {
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
                content?: never;
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
    acceptInvitation: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["InvitationLinkRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ApiResponseInvitationAccepted"];
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
    acceptInvitationAsNewPerson: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["AcceptInvitationRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ApiResponseInvitationAccepted"];
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
    previewInvitation: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["InvitationLinkRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ApiResponseInvitationPreview"];
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
    getCurrentUser: {
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
                    "application/json": components["schemas"]["ApiResponseCurrentUser"];
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
    changePassword: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["ChangePasswordRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
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
    requestPasswordReset: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["ForgotPasswordRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ApiResponseRequestAccepted"];
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
    completePasswordReset: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["ResetPasswordRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
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
    refreshSession: {
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
                content?: never;
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
    signIn: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["SignInRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
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
    signOut: {
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
                content?: never;
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
    signOutEverywhere: {
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
                content?: never;
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
    requestSignUp: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["SignUpRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ApiResponseRequestAccepted"];
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
    completeSignUp: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["CompleteSignUpRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
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
    switchOrganization: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["SwitchOrganizationRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ApiResponseSwitchTarget"];
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
    completeSwitch: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["CompleteSwitchRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
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
    listInvitations: {
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
                    "application/json": components["schemas"]["ApiResponseListInvitationView"];
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
    inviteMember: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["InviteRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ApiResponseRequestAccepted"];
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
    resendInvitation: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                invitationId: string;
            };
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
                    "application/json": components["schemas"]["ApiResponseRequestAccepted"];
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
    revokeInvitation: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                invitationId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
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
    listMembers: {
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
                    "application/json": components["schemas"]["ApiResponseListMemberView"];
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
    setMemberAdministrator: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                membershipId: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["SetAdministratorRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
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
    deactivateMember: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                membershipId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
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
    reactivateMember: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                membershipId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
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
    listMyOrganizations: {
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
                    "application/json": components["schemas"]["ApiResponseListOrganizationSummary"];
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
    createOrganization: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["CreateOrganizationRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ApiResponseOrganizationCreated"];
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
