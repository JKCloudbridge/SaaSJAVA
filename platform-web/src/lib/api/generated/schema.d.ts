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
         * @description The signed-in user. 401 UNAUTHENTICATED without a valid token. Says nothing about what the user may do in an organization: permissions are decided elsewhere, per organization. On the platform host it also lists the platform roles, for presentation only.
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
    "/api/v1/licences": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * The licence pools of the organization
         * @description Per licence type: how many licences the organization holds, how many are assigned and how many are free. For the administrators of the organization of the host. NOT_FOUND on the platform host.
         */
        get: operations["listLicencePools"];
        put?: never;
        post?: never;
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
    "/api/v1/members/{membershipId}/licence": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        /**
         * Give a member a licence
         * @description Moves the member from the licence type they hold, if any. CONFLICT when none is free of that type or the member is not active; two administrators asking for the last free licence have one winner. A licence counts assignments only: it grants no permission.
         */
        put: operations["assignMemberLicence"];
        post?: never;
        /**
         * Take a member licence back
         * @description The licence returns to the pool. Nothing happens when the member holds none.
         */
        delete: operations["releaseMemberLicence"];
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
    "/api/v1/organization/sign-out-all": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Sign everybody else out of the organization
         * @description Ends every session and token bound to this organization host except the caller own. For the administrators of the organization of the host; audited.
         */
        post: operations["signOutEveryoneElse"];
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
    "/api/v1/platform/features": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * The feature keys
         * @description For platform administrators, billing and support.
         */
        get: operations["listFeatures"];
        put?: never;
        /**
         * Add a feature key
         * @description CONFLICT when the key exists. For platform administrators and billing.
         */
        post: operations["addFeature"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/platform/licence-types": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * The licence types
         * @description For platform administrators, billing and support.
         */
        get: operations["listLicenceTypes"];
        put?: never;
        /**
         * Add a licence type
         * @description CONFLICT when the key exists. For platform administrators and billing.
         */
        post: operations["addLicenceType"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/platform/organizations": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * List the organizations (platform console)
         * @description By short name, one page at a time. For platform administrators, support and billing. Never shows members or business data. Platform host only.
         */
        get: operations["listPlatformOrganizations"];
        put?: never;
        /**
         * Set up an organization for a client
         * @description Creates the organization on a plan and invites its first administrator. The organization stays closed (its host answers not available) until that person accepts, which opens it. The answer is the same whether or not the address has an account. For platform administrators.
         */
        post: operations["provisionOrganization"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/platform/organizations/{organizationId}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * One organization (platform console)
         * @description Status, subscription, licence pools, features and the state of the first-administrator invitation (never its address). For platform administrators, support and billing.
         */
        get: operations["getPlatformOrganization"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/platform/organizations/{organizationId}/deactivate": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Close an organization for good
         * @description Final. Needs a reason and the short name of the organization typed again as confirmation. Open invitations are withdrawn and sessions end. Also cancels an organization that was still being set up. For platform administrators.
         */
        post: operations["deactivateOrganization"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/platform/organizations/{organizationId}/entitlements/{feature}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        /**
         * Switch a feature on or off for an organization
         * @description Over the plan default; an absent value removes the override. For platform administrators.
         */
        put: operations["setOrganizationEntitlement"];
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/platform/organizations/{organizationId}/first-administrator": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Invite a new first administrator
         * @description For an organization that is being set up, or that lost every administrator. Always answers 202 with the same text whether or not the address has an account. For platform administrators.
         */
        post: operations["inviteFirstAdministrator"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/platform/organizations/{organizationId}/first-administrator/resend": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Send the first-administrator invitation again
         * @description A new link replaces the old one and the time starts again. CONFLICT when it is no longer open. For platform administrators and support.
         */
        post: operations["resendFirstAdministratorInvitation"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/platform/organizations/{organizationId}/pools/{licenceType}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        /**
         * Set the size of a licence pool of an organization
         * @description Not below the licences in use (CONFLICT). For platform administrators.
         */
        put: operations["setOrganizationLicencePool"];
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/platform/organizations/{organizationId}/reinstate": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Reinstate a suspended organization
         * @description People sign in again; sessions that ended do not come back. Needs a reason. For platform administrators.
         */
        post: operations["reinstateOrganization"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/platform/organizations/{organizationId}/sign-out-all": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Sign everybody out of an organization
         * @description Ends every session and token bound to the organization host. Needs a reason. For platform administrators and support.
         */
        post: operations["signOutOrganizationEverywhere"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/platform/organizations/{organizationId}/subscription": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        /**
         * Change the subscription of an organization
         * @description Plan, status and dates; also puts an organization without a plan on one. A plan that would leave a licence pool below the licences in use is refused (CONFLICT). No payment is involved. For platform administrators and billing.
         */
        put: operations["changeOrganizationSubscription"];
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/platform/organizations/{organizationId}/support-access": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * The support-access requests of an organization
         * @description For platform administrators and support.
         */
        get: operations["listOrganizationSupportAccess"];
        put?: never;
        /**
         * Ask an organization for support access
         * @description A reason and 15 to 240 minutes. Nothing is granted until an administrator of the organization approves it. One open request per person and organization (CONFLICT). For platform administrators and support.
         */
        post: operations["requestSupportAccess"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/platform/organizations/{organizationId}/support-access/{grantId}/cancel": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Withdraw your own open request
         * @description Only the person who asked can. CONFLICT when it is no longer open.
         */
        post: operations["cancelSupportAccessRequest"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/platform/organizations/{organizationId}/suspend": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Suspend an organization
         * @description Its host answers not available at once and everybody signed in loses access. Needs a reason (kept in the audit trail only). For platform administrators.
         */
        post: operations["suspendOrganization"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/platform/people": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Who holds a platform role
         * @description For platform administrators. Platform host only.
         */
        get: operations["listPlatformPeople"];
        put?: never;
        /**
         * Give a platform role to a person
         * @description The person must already have an active account. VALIDATION_ERROR when no active account has the address, CONFLICT when the person holds the role. Audited. For platform administrators.
         */
        post: operations["grantPlatformRole"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/platform/people/{assignmentId}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post?: never;
        /**
         * Take a platform role away
         * @description CONFLICT for the last platform administrator. Audited. For platform administrators.
         */
        delete: operations["revokePlatformRole"];
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/platform/plans": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * The plans
         * @description Default licence quantities and included features. For platform administrators, billing and support.
         */
        get: operations["listPlans"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/platform/plans/{key}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        /**
         * Create a plan, or replace the one with this key
         * @description Organizations keep their pools until their subscription changes plan. For platform administrators and billing.
         */
        put: operations["savePlan"];
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/platform/sessions/lookup": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * The live sign-ins of a person
         * @description When each began and ends and which host it works on; never a token or a secret. An unknown address gives an empty list. For platform administrators and support.
         */
        post: operations["lookUpSessions"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/platform/sessions/sign-out": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Sign a person out everywhere
         * @description Ends every session and token of the person on every host. Answers 204 whether or not the address has an account. For platform administrators and support.
         */
        post: operations["signOutPersonEverywhere"];
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
    "/api/v1/support-access": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Support-access requests of the organization
         * @description Who asked, why, and what became of it. For the administrators of the organization of the host. NOT_FOUND on the platform host.
         */
        get: operations["listSupportAccess"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/support-access/{grantId}/approve": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Approve a support-access request
         * @description Opens a window of at most the minutes asked for and four hours; it ends by itself. CONFLICT when the request is no longer open.
         */
        post: operations["approveSupportAccess"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/support-access/{grantId}/deny": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Deny a support-access request
         * @description CONFLICT when the request is no longer open.
         */
        post: operations["denySupportAccess"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/support-access/{grantId}/revoke": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * End an approved support access now
         * @description CONFLICT when the access is not approved.
         */
        post: operations["revokeSupportAccess"];
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
        AddCatalogueItemRequest: {
            key: string;
            name: string;
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
        ApiPageResponsePlatformOrganizationSummary: {
            data: components["schemas"]["PlatformOrganizationSummary"][];
            pagination: components["schemas"]["Pagination"];
        };
        ApiResponseCatalogueItem: {
            data: components["schemas"]["CatalogueItem"];
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
        ApiResponseListCatalogueItem: {
            data: components["schemas"]["CatalogueItem"][];
        };
        ApiResponseListInvitationView: {
            data: components["schemas"]["InvitationView"][];
        };
        ApiResponseListLicencePoolView: {
            data: components["schemas"]["LicencePoolView"][];
        };
        ApiResponseListMemberView: {
            data: components["schemas"]["MemberView"][];
        };
        ApiResponseListOrganizationSummary: {
            data: components["schemas"]["OrganizationSummary"][];
        };
        ApiResponseListPlanInfo: {
            data: components["schemas"]["PlanInfo"][];
        };
        ApiResponseListPlatformPersonView: {
            data: components["schemas"]["PlatformPersonView"][];
        };
        ApiResponseListSessionInfo: {
            data: components["schemas"]["SessionInfo"][];
        };
        ApiResponseListSupportAccessView: {
            data: components["schemas"]["SupportAccessView"][];
        };
        ApiResponseOrganizationCreated: {
            data: components["schemas"]["OrganizationCreated"];
        };
        ApiResponsePlanInfo: {
            data: components["schemas"]["PlanInfo"];
        };
        ApiResponsePlatformOrganizationDetail: {
            data: components["schemas"]["PlatformOrganizationDetail"];
        };
        ApiResponsePlatformOrganizationSummary: {
            data: components["schemas"]["PlatformOrganizationSummary"];
        };
        ApiResponsePlatformPersonView: {
            data: components["schemas"]["PlatformPersonView"];
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
        ApproveSupportAccessRequest: {
            /** Format: int32 */
            minutes?: number;
        };
        CatalogueItem: {
            key: string;
            name: string;
        };
        ChangePasswordRequest: {
            currentPassword: string;
            newPassword: string;
        };
        ChangeSubscriptionRequest: {
            /** Format: date-time */
            periodEndsAt?: string;
            planKey?: string;
            reason: string;
            status?: string;
            /** Format: date-time */
            trialEndsAt?: string;
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
            platformRoles: string[];
        };
        EntitlementInfo: {
            enabled?: boolean;
            inPlan?: boolean;
            key: string;
            name: string;
            override?: boolean;
        };
        /**
         * @description Stable machine-readable error code. Branch on this, never on the message.
         * @enum {string}
         */
        ErrorCode: "VALIDATION_ERROR" | "MALFORMED_REQUEST" | "UNAUTHENTICATED" | "FORBIDDEN" | "NOT_FOUND" | "METHOD_NOT_ALLOWED" | "NOT_ACCEPTABLE" | "CONFLICT" | "CONCURRENT_MODIFICATION" | "PAYLOAD_TOO_LARGE" | "UNSUPPORTED_MEDIA_TYPE" | "RATE_LIMITED" | "INTERNAL_ERROR" | "SERVICE_UNAVAILABLE" | "TENANT_UNAVAILABLE";
        FirstAdministratorInfo: {
            /** Format: date-time */
            expiresAt: string;
            /** Format: uuid */
            id: string;
            /** Format: int32 */
            sentCount?: number;
            status: string;
        };
        FirstAdministratorRequest: {
            email: string;
        };
        ForgotPasswordRequest: {
            email: string;
        };
        GrantPlatformRoleRequest: {
            email: string;
            role: string;
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
        LicencePoolView: {
            /** Format: int32 */
            assigned?: number;
            /** Format: int32 */
            available?: number;
            licenceType: string;
            name: string;
            /** Format: int32 */
            quantity?: number;
        };
        LifecycleRequest: {
            confirm?: string;
            reason: string;
        };
        MemberView: {
            administrator?: boolean;
            displayName: string;
            email: string;
            foundingAdministrator?: boolean;
            /** Format: uuid */
            id: string;
            licence?: string;
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
        PlanInfo: {
            features: string[];
            key: string;
            licences: {
                [key: string]: number;
            };
            name: string;
            /** Format: int32 */
            trialDays?: number;
        };
        PlatformOrganizationDetail: {
            displayName: string;
            entitlements: components["schemas"]["EntitlementInfo"][];
            firstAdministrator?: components["schemas"]["FirstAdministratorInfo"];
            /** Format: uuid */
            id: string;
            pools: components["schemas"]["LicencePoolView"][];
            slug: string;
            status: string;
            /** Format: date-time */
            statusChangedAt: string;
            subscription?: components["schemas"]["SubscriptionInfo"];
        };
        PlatformOrganizationSummary: {
            displayName: string;
            /** Format: uuid */
            id: string;
            plan?: string;
            slug: string;
            status: string;
            subscriptionStatus?: string;
            /** Format: date-time */
            trialEndsAt?: string;
            trialExpired?: boolean;
        };
        PlatformPersonView: {
            displayName: string;
            email: string;
            /** Format: uuid */
            id: string;
            role: string;
            /** Format: date-time */
            since: string;
        };
        PlatformStatus: {
            apiVersion: string;
            /** Format: date-time */
            databaseTime: string;
            /** Format: date-time */
            serverTime: string;
            service: string;
        };
        ProvisionOrganizationRequest: {
            displayName: string;
            email: string;
            planKey: string;
            slug: string;
        };
        ReasonRequest: {
            reason: string;
        };
        RequestAccepted: {
            message: string;
        };
        ResetPasswordRequest: {
            newPassword: string;
            token: string;
        };
        SavePlanRequest: {
            features: string[];
            licences: {
                [key: string]: number;
            };
            name: string;
            /** Format: int32 */
            trialDays?: number;
        };
        SessionInfo: {
            /** Format: date-time */
            expires: string;
            kind: string;
            organization?: string;
            /** Format: date-time */
            started: string;
        };
        SessionLookupRequest: {
            email: string;
            reason: string;
        };
        SetAdministratorRequest: {
            administrator: boolean;
        };
        SetEntitlementRequest: {
            enabled?: boolean;
            reason: string;
        };
        SetLicenceRequest: {
            licenceType: string;
        };
        SetPoolRequest: {
            /** Format: int32 */
            quantity: number;
            reason: string;
        };
        SignInRequest: {
            email: string;
            password: string;
        };
        SignUpRequest: {
            email: string;
        };
        SubscriptionInfo: {
            /** Format: date-time */
            periodEndsAt?: string;
            planKey: string;
            planName: string;
            /** Format: date-time */
            startedAt: string;
            status: string;
            /** Format: date-time */
            trialEndsAt?: string;
            trialExpired?: boolean;
        };
        SupportAccessRequestBody: {
            /** Format: int32 */
            minutes: number;
            reason: string;
        };
        SupportAccessView: {
            /** Format: date-time */
            accessExpiresAt?: string;
            active?: boolean;
            /** Format: uuid */
            id: string;
            reason: string;
            /** Format: date-time */
            requestedAt: string;
            /** Format: int32 */
            requestedMinutes?: number;
            requester: string;
            status: string;
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
    listLicencePools: {
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
                    "application/json": components["schemas"]["ApiResponseListLicencePoolView"];
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
    assignMemberLicence: {
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
                "application/json": components["schemas"]["SetLicenceRequest"];
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
    releaseMemberLicence: {
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
    signOutEveryoneElse: {
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
    listFeatures: {
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
                    "application/json": components["schemas"]["ApiResponseListCatalogueItem"];
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
    addFeature: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["AddCatalogueItemRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ApiResponseCatalogueItem"];
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
    listLicenceTypes: {
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
                    "application/json": components["schemas"]["ApiResponseListCatalogueItem"];
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
    addLicenceType: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["AddCatalogueItemRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ApiResponseCatalogueItem"];
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
    listPlatformOrganizations: {
        parameters: {
            query?: {
                limit?: number;
                cursor?: string;
                search?: string;
            };
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
                    "application/json": components["schemas"]["ApiPageResponsePlatformOrganizationSummary"];
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
    provisionOrganization: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["ProvisionOrganizationRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ApiResponsePlatformOrganizationSummary"];
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
    getPlatformOrganization: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                organizationId: string;
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
                    "application/json": components["schemas"]["ApiResponsePlatformOrganizationDetail"];
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
    deactivateOrganization: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                organizationId: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["LifecycleRequest"];
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
    setOrganizationEntitlement: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                organizationId: string;
                feature: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["SetEntitlementRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ApiResponsePlatformOrganizationDetail"];
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
    inviteFirstAdministrator: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                organizationId: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["FirstAdministratorRequest"];
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
    resendFirstAdministratorInvitation: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                organizationId: string;
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
    setOrganizationLicencePool: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                organizationId: string;
                licenceType: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["SetPoolRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ApiResponsePlatformOrganizationDetail"];
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
    reinstateOrganization: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                organizationId: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["LifecycleRequest"];
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
    signOutOrganizationEverywhere: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                organizationId: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["ReasonRequest"];
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
    changeOrganizationSubscription: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                organizationId: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["ChangeSubscriptionRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ApiResponsePlatformOrganizationDetail"];
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
    listOrganizationSupportAccess: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                organizationId: string;
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
                    "application/json": components["schemas"]["ApiResponseListSupportAccessView"];
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
    requestSupportAccess: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                organizationId: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["SupportAccessRequestBody"];
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
    cancelSupportAccessRequest: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                organizationId: string;
                grantId: string;
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
    suspendOrganization: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                organizationId: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["LifecycleRequest"];
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
    listPlatformPeople: {
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
                    "application/json": components["schemas"]["ApiResponseListPlatformPersonView"];
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
    grantPlatformRole: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["GrantPlatformRoleRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ApiResponsePlatformPersonView"];
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
    revokePlatformRole: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                assignmentId: string;
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
    listPlans: {
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
                    "application/json": components["schemas"]["ApiResponseListPlanInfo"];
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
    savePlan: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                key: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["SavePlanRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ApiResponsePlanInfo"];
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
    lookUpSessions: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["SessionLookupRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ApiResponseListSessionInfo"];
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
    signOutPersonEverywhere: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["SessionLookupRequest"];
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
    listSupportAccess: {
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
                    "application/json": components["schemas"]["ApiResponseListSupportAccessView"];
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
    approveSupportAccess: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                grantId: string;
            };
            cookie?: never;
        };
        requestBody?: {
            content: {
                "application/json": components["schemas"]["ApproveSupportAccessRequest"];
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
    denySupportAccess: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                grantId: string;
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
    revokeSupportAccess: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                grantId: string;
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
