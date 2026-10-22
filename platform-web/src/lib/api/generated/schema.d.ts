/**
 * Types of the platform API, generated from platform-api-contract/src/main/resources/openapi/platform-api-v1.json.
 * Do not edit by hand: change the API, regenerate the document, then run `npm run api:generate`.
 */

export interface paths {
    "/api/v1/abilities": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * The abilities the platform knows
         * @description What a profile, an access policy or an individual grant can hold. For members who manage access, invite members or see members. NOT_FOUND on the platform host, FORBIDDEN without one of those abilities.
         */
        get: operations["listAbilities"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/access-policies": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * List the access policies of the organization
         * @description With the number of members who hold each. For members who manage access. NOT_FOUND on the platform host, FORBIDDEN without the ability.
         */
        get: operations["listAccessPolicies"];
        put?: never;
        /**
         * Create an access policy
         * @description Abilities added to the members it is assigned to; optionally it uses a licence of one type from the organization's pool when assigned. VALIDATION_ERROR for a name in use, an unknown ability or an unknown licence type. Audited.
         */
        post: operations["createAccessPolicy"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/access-policies/{policyId}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        /**
         * Change an access policy
         * @description Takes effect for its members at once. CONFLICT for a licence type change while members hold the policy, or when the change would leave nobody who can manage access. NOT_FOUND for a policy of another organization. Audited.
         */
        put: operations["updateAccessPolicy"];
        post?: never;
        /**
         * Remove an access policy
         * @description CONFLICT while members still hold it. Audited.
         */
        delete: operations["deleteAccessPolicy"];
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/access-policies/{policyId}/data-access": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * The permission matrix of an access policy
         * @description For members who manage access. NOT_FOUND for a policy of another organization.
         */
        get: operations["getAccessPolicyDataAccess"];
        /**
         * Replace the permission matrix of an access policy
         * @description What is not listed is not allowed. Takes effect at once for everyone who holds the policy, also through a group. VALIDATION_ERROR for an unknown object, field or action. Audited.
         */
        put: operations["replaceAccessPolicyDataAccess"];
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/applications": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * The applications the signed-in member may use, with their navigation
         * @description An application is listed when it names the profile of the member or names no profile. Inside it only what the member may open is listed (Sprint 18): an object or record page entry needs the permission to read the object, a tab entry needs the tab and its custom page to exist; a heading that leads nowhere and an application with nothing left are left out. Items that point to something that no longer exists are left out. Presentation only: every page a link opens is decided again by the server. NOT_FOUND on the platform host.
         */
        get: operations["getMyApplications"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/applications/custom-pages/{customPageApiName}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * The script of a custom page the signed-in member may open
         * @description For a member of an application that lists a tab showing the page. The script is the organization's own text, checked against the hash that was published; the platform never runs it. NOT_FOUND for a page that does not exist or that the member may not open (the same answer), SERVICE_UNAVAILABLE (with Retry-After) when the object store cannot give it.
         */
        get: operations["getCustomPageScript"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/approvals/delegations": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Delegations
         * @description The caller's own by default. Name a delegator, or ask for all, only with approvals.manage (FORBIDDEN otherwise).
         */
        get: operations["listApprovalDelegations"];
        put?: never;
        /**
         * Name a delegate for a range of days
         * @description The delegate is asked beside the delegator (the delegator keeps the task) whenever a step becomes active within the range; tasks already assigned are not moved. No chains and none to oneself; the range is at most 366 days, both days count, in the time zone of the delegation. The delegate must be an active member who holds a licence. A member makes one for themselves; approvals.manage is needed for another person. Audited.
         */
        post: operations["createApprovalDelegation"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/approvals/delegations/{id}": {
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
         * End a delegation
         * @description The delegator, or a member who holds approvals.manage. Tasks the delegate already holds stay. NOT_FOUND for a delegation that does not exist.
         */
        delete: operations["revokeApprovalDelegation"];
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/approvals/inbox": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * The caller's tasks
         * @description Three tabs: TO_APPROVE (open tasks), DELEGATED (open tasks that came through a delegation) and DECIDED (approved, rejected or handed on). Open tasks are oldest first, decided ones newest first; cursor paging. Each task names its record by the title only when the caller can read the record, and says so when they cannot: an approval grants no access. Narrow by object and process.
         */
        get: operations["listApprovalTasks"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/approvals/inbox/summary": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * How many tasks wait for the caller, and whether to show the link
         * @description For the header: the number of open tasks and whether the member has anything to do with approvals (the plan has the feature, or they were asked or submitted something).
         */
        get: operations["getApprovalInboxSummary"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/approvals/instances": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Approval requests the caller may see
         * @description Newest first, cursor paging. A member sees the requests they submitted or were asked to decide; a member who holds approvals.view-all or approvals.manage sees every request for the records they can read. Narrow by status, object, process, record, or attention (requests that nobody can decide right now).
         */
        get: operations["listApprovals"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/approvals/instances/{id}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * One approval request
         * @description The steps with the people asked, what the caller may do now, and the history for those who may see it (the submitter, anyone who was asked, and members with approvals.view-all for a record they can read). The history never shows a value of the record. NOT_FOUND for what the caller may not see.
         */
        get: operations["getApproval"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/approvals/instances/{id}/approve": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Approve the caller's task
         * @description Allowed to a person who holds an open task of the active step, whatever their abilities; the task is checked in the transaction, with their standing (an active member who holds the licence their profile needs). On a step that needs any one approver the first approval completes it and the second approver's call is CONCURRENT_MODIFICATION. The comment is text of a person: at most 2000 characters, kept in the history and shown only to those who may see it; it is never logged and never sent in an event or a mail. The version is the one the caller read; a request that changed since is answered CONCURRENT_MODIFICATION and names nothing. NOT_FOUND for what the caller may not see.
         */
        post: operations["approveApproval"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/approvals/instances/{id}/assign": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Ask one more person to decide the active step
         * @description For a member who holds approvals.manage: the way out of a step that nobody can decide (its attention is cleared). The person must be an active member with a licence and hold no task of the step yet. The comment is text of a person: at most 2000 characters, kept in the history and shown only to those who may see it; it is never logged and never sent in an event or a mail. The version is the one the caller read; a request that changed since is answered CONCURRENT_MODIFICATION and names nothing. NOT_FOUND for what the caller may not see.
         */
        post: operations["assignApproval"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/approvals/instances/{id}/cancel": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * End a request as an administrator
         * @description A member who holds approvals.override-lock; FORBIDDEN otherwise. The record is unlocked. This is the only way a request becomes CANCELLED. The comment is text of a person: at most 2000 characters, kept in the history and shown only to those who may see it; it is never logged and never sent in an event or a mail. The version is the one the caller read; a request that changed since is answered CONCURRENT_MODIFICATION and names nothing. NOT_FOUND for what the caller may not see.
         */
        post: operations["cancelApproval"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/approvals/instances/{id}/outcome": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Make the outcome updates again after they were refused
         * @description For a member who holds approvals.manage, when the request ended but a rule refused what the process sets on the record (the request shows the stable reason). It writes the same updates to the record as it is now, so the record version the caller read is sent back; it is refused while a newer request of the record is pending. Audited. The version is the one the caller read; a request that changed since is answered CONCURRENT_MODIFICATION and names nothing. NOT_FOUND for what the caller may not see.
         */
        post: operations["reapplyApprovalOutcome"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/approvals/instances/{id}/reassign": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Hand the caller's task to one other person
         * @description To one person who is an active member with a licence and holds no task of the step yet (so a task never goes back or loops); the submitter is excluded unless the process allows it. Recorded with both names. The comment is text of a person: at most 2000 characters, kept in the history and shown only to those who may see it; it is never logged and never sent in an event or a mail. The version is the one the caller read; a request that changed since is answered CONCURRENT_MODIFICATION and names nothing. NOT_FOUND for what the caller may not see.
         */
        post: operations["reassignApproval"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/approvals/instances/{id}/recall": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Withdraw a request
         * @description The person who submitted it, or a member who holds approvals.override-lock. The record is unlocked and the outcome updates for RECALLED are made in the same transaction. The comment is text of a person: at most 2000 characters, kept in the history and shown only to those who may see it; it is never logged and never sent in an event or a mail. The version is the one the caller read; a request that changed since is answered CONCURRENT_MODIFICATION and names nothing. NOT_FOUND for what the caller may not see.
         */
        post: operations["recallApproval"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/approvals/instances/{id}/reject": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Reject the caller's task
         * @description Ends the step and the request as REJECTED, whatever the completion rule. The comment is required (VALIDATION_ERROR otherwise). The comment is text of a person: at most 2000 characters, kept in the history and shown only to those who may see it; it is never logged and never sent in an event or a mail. The version is the one the caller read; a request that changed since is answered CONCURRENT_MODIFICATION and names nothing. NOT_FOUND for what the caller may not see.
         */
        post: operations["rejectApproval"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/approvals/people": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * People who can be asked to decide
         * @description Active members who hold the licence their profile needs, found by a part of their name, at most 50 at a time; the text must match, so the list of members cannot be read page by page. For the pickers of a reassignment, a delegation and an approver rule. Names and identifiers only.
         */
        get: operations["searchApprovalPeople"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/audit-events": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * The audit events of the organization
         * @description Newest first, one page at a time, with filters for time, person, kind and target. For members who may view the audit trail. NOT_FOUND on the platform host, FORBIDDEN without the ability, VALIDATION_ERROR for a filter that is malformed.
         */
        get: operations["listAuditEvents"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
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
    "/api/v1/automation/health": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * The health of automation in the organization
         * @description Counts of what waits, what retries, what was set aside, which events were given up on, which approvals have nobody to decide them or are overdue, and which mails are stuck, each with the place to look. Counts only: never a value of a record. Needs automation.view. Organization hosts only.
         */
        get: operations["getAutomationHealth"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/data-access/mine": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * What the signed-in member may do with data
         * @description Their effective permissions on objects and fields (profile while licensed, access policies, the access policies of their groups, individual grants), with implied actions written out. Presentation only: every request is decided again by the server. NOT_FOUND on the platform host.
         */
        get: operations["getMyDataAccess"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/data-catalogue": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * The objects and fields a permission matrix can be about, and the actions
         * @description Objects arrive with the metadata engine (Sprint 10); until then a deployment lists none. For members who manage access. NOT_FOUND on the platform host, FORBIDDEN without the ability.
         */
        get: operations["getDataCatalogue"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/embedded-tokens": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * An access token for the script of a custom page
         * @description For a member of the organization of the host, with their own token. The new token is the member's own, narrowed: it ends with the sign-in it came from, lives a few minutes, works only as a bearer token, and reaches the data interface only (records, files, searches, reading definitions); everything else answers FORBIDDEN. Every call it makes is decided by the member's own permissions. It is shown once and stored only as a hash. A token of this kind cannot ask for another (FORBIDDEN). NOT_FOUND on the platform host; RATE_LIMITED past the hourly allowance. Audited.
         */
        post: operations["issueEmbeddedToken"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/groups": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * List the public groups of the organization
         * @description With their people, nested groups and access policies. For members who manage access. NOT_FOUND on the platform host, FORBIDDEN without the ability.
         */
        get: operations["listGroups"];
        put?: never;
        /**
         * Create a public group
         * @description A named set of people and groups. VALIDATION_ERROR for a name in use. Audited.
         */
        post: operations["createGroup"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/groups/{groupId}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Read one public group
         * @description NOT_FOUND for a group of another organization.
         */
        get: operations["getGroup"];
        /**
         * Rename a public group
         * @description VALIDATION_ERROR for a name in use. NOT_FOUND for a group of another organization. Audited.
         */
        put: operations["updateGroup"];
        post?: never;
        /**
         * Remove a public group
         * @description Everyone who held something through the group loses it at once. CONFLICT when that would leave nobody who can manage access. Audited.
         */
        delete: operations["deleteGroup"];
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/groups/{groupId}/members": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Put a person or another group into a group
         * @description Name exactly one of membershipId and groupId. CONFLICT when the group would contain itself, directly or through other groups, or when the person is not an active member. Audited.
         */
        post: operations["addGroupMember"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/groups/{groupId}/members/groups/{innerGroupId}": {
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
         * Take a nested group out of a group
         * @description Takes effect at once. CONFLICT when that would leave nobody who can manage access. Audited.
         */
        delete: operations["removeGroupGroup"];
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/groups/{groupId}/members/people/{membershipId}": {
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
         * Take a person out of a group
         * @description Takes effect at once. CONFLICT when that would leave nobody who can manage access. Audited.
         */
        delete: operations["removeGroupPerson"];
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/groups/{groupId}/policies": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Give an access policy to a group
         * @description Everyone in the group, directly or through nested groups, holds what the policy gives. CONFLICT for a policy that needs a licence (a licence belongs to a person). Audited.
         */
        post: operations["giveGroupPolicy"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/groups/{groupId}/policies/{policyId}": {
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
         * Take an access policy from a group
         * @description Takes effect at once. CONFLICT when that would leave nobody who can manage access. Audited.
         */
        delete: operations["takeGroupPolicy"];
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
         * Create a new member by inviting an address
         * @description Always answers 202 with the same text, whether or not the address has an account or is already a member: if it can be invited, an e-mail with a link follows. Inviting an address that has an open invitation updates and sends it again. The administrator chooses the profile and role; the person only sets a password. Active=false saves it without sending. Nothing is granted until accepted. For members who may invite. VALIDATION_ERROR for a profile or role of another organization, FORBIDDEN for a profile with abilities the caller lacks, 429 RATE_LIMITED.
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
    "/api/v1/licence-types": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * The licence types a profile or access policy can use
         * @description The platform's catalogue of licence types, for the setup screens. For members who manage access, invite members or see members. NOT_FOUND on the platform host, FORBIDDEN without one of those abilities.
         */
        get: operations["listLicenceTypes_1"];
        put?: never;
        post?: never;
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
         * @description Per licence type: how many licences the organization holds, how many are assigned (for profiles and for licence-bound access policies) and how many are free. For members who manage licences. NOT_FOUND on the platform host.
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
         * @description With each member's profile, role, access policies and licence. For members who may see members. NOT_FOUND on the platform host, FORBIDDEN without the ability.
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
    "/api/v1/members/{membershipId}/access": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Everything that decides what one member may do
         * @description Their profile, role, access policies, individual grants (with the notes) and the resulting abilities. For members who manage access. NOT_FOUND for a member of another organization.
         */
        get: operations["getMemberAccess"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/members/{membershipId}/data-access": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * The permissions on data granted to one member directly
         * @description For members who manage access. NOT_FOUND for a member of another organization.
         */
        get: operations["getMemberDataAccess"];
        /**
         * Replace the permissions on data granted to one member directly
         * @description What is not listed is not granted. VALIDATION_ERROR for an unknown object, field or action; CONFLICT for a member who is not active. Audited.
         */
        put: operations["replaceMemberDataAccess"];
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
         * @description The member is signed out of this organization at once, their licences go back to the pool, and they cannot get back in until reactivated; their other organizations are untouched. CONFLICT when already deactivated or when this is the last member who can manage access. NOT_FOUND for a member of another organization.
         */
        post: operations["deactivateMember"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/members/{membershipId}/grants": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Give one member one ability directly
         * @description With an optional short note why, shown only to members who manage access. VALIDATION_ERROR for an unknown ability. Audited.
         */
        post: operations["grantMemberAbility"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/members/{membershipId}/grants/{ability}": {
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
         * Take a directly given ability back
         * @description Nothing happens when the member does not hold it. CONFLICT when the change would leave nobody who can manage access.
         */
        delete: operations["revokeMemberAbility"];
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
         * Give a member the licence their profile needs
         * @description For a member who has none (for example one who joined when none was free). CONFLICT when none is free of that type, the member is not active or has no profile; two administrators asking for the last free licence have one winner. A licence counts assignments only: it grants no permission.
         */
        put: operations["giveMemberLicence"];
        post?: never;
        /**
         * Take a member licence back
         * @description The licence for their profile returns to the pool and the profile gives no abilities until one is held again. Nothing happens when the member holds none. CONFLICT when the change would leave nobody who can manage access.
         */
        delete: operations["releaseMemberLicence"];
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/members/{membershipId}/policies": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Give a member an access policy
         * @description A policy that needs a licence uses one from the pool: CONFLICT when none is free, two administrators asking for the last one have one winner. For members who manage access; audited.
         */
        post: operations["assignMemberPolicy"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/members/{membershipId}/policies/{policyId}": {
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
         * Take an access policy from a member
         * @description Its licence goes back to the pool. Nothing happens when the member does not hold it. CONFLICT when the change would leave nobody who can manage access.
         */
        delete: operations["unassignMemberPolicy"];
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/members/{membershipId}/profile": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        /**
         * Give a member a profile
         * @description The member then holds a licence of the profile's type, taken from the pool (the one they held for the old profile goes back). CONFLICT when none is free, the member is not active, or the change would leave nobody who can manage access. For members who manage access; audited.
         */
        put: operations["setMemberProfile"];
        post?: never;
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
         * @description The person can sign in again with the organization's default profile and nothing else (what they held ended with their membership), and a licence if one is free; sessions that ended do not come back. CONFLICT when the member is not deactivated.
         */
        post: operations["reactivateMember"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/members/{membershipId}/role": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        /**
         * Place a member in the role hierarchy, or take them out of it
         * @description A role decides which records the member may see once records exist; it gives no ability. For members who manage access; audited.
         */
        put: operations["setMemberRole"];
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/metadata/applications": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * The applications of the organization, as defined
         * @description For members who may view objects and fields: the whole definition, including the profiles that see each application. A member's own menu is GET /api/v1/applications.
         */
        get: operations["listApplicationDefinitions"];
        put?: never;
        /**
         * Add an application
         * @description Live at once, as a release of one change. The definition is checked against the schema of the same name in components.schemas of this document; VALIDATION_ERROR names every place that is wrong, for example definition.sections[1].fields[0].behaviour. A navigation item opens an object, a record page or a path inside the organization, never an arbitrary address. CONFLICT when an object or record page it opens does not exist, or at the limit. Audited.
         */
        post: operations["createApplication"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/metadata/applications/{applicationApiName}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * One application, as defined
         * @description NOT_FOUND for an application the organization does not have.
         */
        get: operations["getApplicationDefinition"];
        /**
         * Change an application
         * @description Live at once, as a release of one change. The API name never changes. The definition is checked against the schema of the same name in components.schemas of this document; VALIDATION_ERROR names every place that is wrong, for example definition.sections[1].fields[0].behaviour. CONCURRENT_MODIFICATION when someone changed it since it was read. Audited.
         */
        put: operations["updateApplication"];
        post?: never;
        /**
         * Remove an application
         * @description Live at once, as a release of one change. NOT_FOUND for an application the organization does not have. Audited.
         */
        delete: operations["deleteApplication"];
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/metadata/approvals": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * The approval processes of the organization
         * @description For members who may view approvals. Name an object to list only its processes. NOT_FOUND for an object the organization does not have.
         */
        get: operations["listApprovalProcesses"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/metadata/approvals/catalogue": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * What an approval process can be made of
         * @description The kinds of approver rule with what each one's value names, the completion rules, the outcomes, the states of a request and of a step, and the limits the platform enforces. The screens build their forms and filters from this and hold no list of their own. For members who may view approvals.
         */
        get: operations["getApprovalCatalogue"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/metadata/approvals/choices": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * The profiles, roles and public groups an approver rule can name
         * @description For the builder of approval processes. Needs approvals.manage.
         */
        get: operations["getApprovalChoices"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/metadata/approvals/{objectApiName}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Add an approval process to an object
         * @description Live at once, as a release of one change. The name is turned into the permanent API name with the ending __c. The entry criteria are in the expression language (true or false, they may use TODAY() and NOW()); the steps name who is asked by closed kinds of rule; the outcomes set fields of the record to literals. The request is checked here (a VALIDATION_ERROR names every place); whether the fields it names exist is checked after the change and a CONFLICT names what is wrong. A request that is already pending keeps the definition it started under. CONFLICT for an object whose records belong to the platform, at the limit, when the plan has no approvals, or when the result would break a dependency. Audited by name and counts, never by the text.
         */
        post: operations["createApprovalProcess"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/metadata/approvals/{objectApiName}/check": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Try entry criteria on sample values, and the steps and outcomes on the object
         * @description Answers what would happen to a record with these values: RUNS (the criteria are true, or there are none), SKIPPED (false or no value: the record could not be submitted) or ERROR with the place when the evaluation itself fails. Criteria that are not valid are a VALIDATION_ERROR naming the place. There are no records yet, so the values are typed as text for the type of each field. Name steps and outcomes to learn, by place, which fields they name do not exist or cannot be used and which literals do not fit. Nothing is stored and nothing is logged. Needs approvals.manage. Who would be asked for a real record is another question: see resolve.
         */
        post: operations["checkApprovalProcess"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/metadata/approvals/{objectApiName}/resolve": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Who would each step ask for a record?
         * @description Works out, for a record the caller can read, the people each of the given steps would ask, the people left out and why (inactive, unlicensed, the submitter), and whether the fallback approver would be used or nobody could be asked. The very resolution a real request uses; nothing is stored, locked or sent. Needs approvals.manage.
         */
        post: operations["resolveApprovalProcess"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/metadata/approvals/{objectApiName}/{processApiName}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * One approval process
         * @description NOT_FOUND for a process the object does not have.
         */
        get: operations["getApprovalProcess"];
        /**
         * Change an approval process
         * @description Live at once, as a release of one change. The API name and the object never change. A change of the entry criteria, the steps, the outcomes, the fallback or the self-approval rule makes a new version of the definition; requests already pending keep the version they started under. The entry criteria are in the expression language (true or false, they may use TODAY() and NOW()); the steps name who is asked by closed kinds of rule; the outcomes set fields of the record to literals. The request is checked here (a VALIDATION_ERROR names every place); whether the fields it names exist is checked after the change and a CONFLICT names what is wrong. A request that is already pending keeps the definition it started under. CONCURRENT_MODIFICATION when someone changed it since it was read.
         */
        put: operations["updateApprovalProcess"];
        post?: never;
        /**
         * Remove an approval process
         * @description Live at once, as a release of one change. CONFLICT while the process has a request waiting for a decision: switch it off and remove it when they have ended. The undo of the release makes it again, as a new process.
         */
        delete: operations["deleteApprovalProcess"];
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/metadata/approvals/{objectApiName}/{processApiName}/activation": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Switch an approval process on or off
         * @description Live at once, as a release of one change, and without a new version of the definition. A process that is switched off starts no new request; requests already pending finish and keep their lock. CONFLICT when the plan has no approvals and the process is being switched on. CONCURRENT_MODIFICATION when someone changed it since it was read.
         */
        post: operations["activateApprovalProcess"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/metadata/change-sets": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * The change sets of the organization
         * @description Open drafts first, then published and discarded ones. For members who may view objects and fields. NOT_FOUND on the platform host.
         */
        get: operations["listChangeSets"];
        put?: never;
        /**
         * Start a change set
         * @description A named group of intended changes that is published all together or not at all. A draft is invisible to everything that reads the organization's metadata until it is published. VALIDATION_ERROR for a name in use, CONFLICT at the limit of open change sets. Audited.
         */
        post: operations["createChangeSet"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/metadata/change-sets/{id}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * One change set with its changes
         * @description NOT_FOUND for a change set the organization does not have.
         */
        get: operations["getChangeSet"];
        put?: never;
        post?: never;
        /**
         * Discard an open change set
         * @description Nothing it holds was ever live. CONFLICT for one that was published or discarded. Audited.
         */
        delete: operations["discardChangeSet"];
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/metadata/change-sets/{id}/changes": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Add a change to an open change set
         * @description The change carries the request the live endpoint takes. It is not applied: nothing is checked beyond its shape until the set is checked or published. VALIDATION_ERROR for a change that does not say what it needs, CONFLICT for a set that is no longer open or full. Audited.
         */
        post: operations["addChange"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/metadata/change-sets/{id}/changes/{changeId}": {
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
         * Take a change out of an open change set
         * @description NOT_FOUND for a change the set does not have. Audited.
         */
        delete: operations["removeChange"];
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/metadata/change-sets/{id}/preview": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Show what a change set would make, without keeping anything
         * @description The same check as validate, and in addition the affected objects as they would be after publishing. Needs the ability to view and either to manage or to publish.
         */
        post: operations["previewChangeSet"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/metadata/change-sets/{id}/publish": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Publish a change set
         * @description Puts all of it live or none of it, as one release. CONFLICT with every problem (each naming what depends on it) when it cannot be published, or when it is no longer open. Needs the abilities to publish and to view. Audited.
         */
        post: operations["publishChangeSet"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/metadata/change-sets/{id}/validate": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Check a change set without keeping anything
         * @description Applies the changes by the real rules, checks what depends on what, and rolls everything back. The answer lists every problem (each naming what depends on it) and what would be added, changed or removed. Needs the ability to view and either to manage or to publish.
         */
        post: operations["validateChangeSet"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/metadata/custom-page-assets": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Upload the script of a custom page
         * @description The body is the text of the script, as it is (UTF-8, not a form), with the length announced (Content-Length; chunked bodies are refused), at most the limit of the organization (PAYLOAD_TOO_LARGE above it). The answer names the asset: put its identifier, hash and size in the content of a custom page. The same script uploaded twice is one asset (200 instead of 201). The platform stores the text and never runs it. CONFLICT at the most scripts an organization may keep; SERVICE_UNAVAILABLE (with Retry-After) when the object store does not answer, and nothing is stored then. Needs the abilities to manage and to publish metadata. Audited by identifier and size, never by content.
         */
        post: operations["uploadCustomPageScript"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/metadata/custom-pages": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * The custom pages of the organization, as defined
         * @description For members who may view objects and fields.
         */
        get: operations["listCustomPageDefinitions"];
        put?: never;
        /**
         * Add a custom page
         * @description Live at once, as a release of one change. The definition is checked against the schema of the same name in components.schemas of this document; VALIDATION_ERROR names every place that is wrong, for example definition.sections[1].fields[0].behaviour. The content names the uploaded script (upload it first, see uploadCustomPageScript) with its hash and size; VALIDATION_ERROR when the script is not in this organization or the hash or size is not its own. The reference for the server is stored and never read or run. CONFLICT at the limit. Audited.
         */
        post: operations["createCustomPage"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/metadata/custom-pages/{apiName}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * One custom page, as defined
         * @description NOT_FOUND for a custom page the organization does not have.
         */
        get: operations["getCustomPageDefinition"];
        /**
         * Change a custom page
         * @description Live at once, as a release of one change. The API name never changes. The definition is checked against the schema of the same name in components.schemas of this document; VALIDATION_ERROR names every place that is wrong, for example definition.sections[1].fields[0].behaviour. CONCURRENT_MODIFICATION when someone changed it since it was read. Audited.
         */
        put: operations["updateCustomPage"];
        post?: never;
        /**
         * Remove a custom page
         * @description Live at once, as a release of one change. CONFLICT, naming what still uses it, when something depends on it. NOT_FOUND for a custom page the organization does not have. Audited.
         */
        delete: operations["deleteCustomPage"];
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/metadata/expressions/check": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Is this expression valid for an object?
         * @description Answers valid or not, and when not, where it is wrong (line and column) and what kind of problem it is; when valid, the type it gives and the fields it uses. A formula may also name the type it must give. Nothing is stored and nothing is logged. Needs metadata.view and either metadata.manage or metadata.publish.
         */
        post: operations["checkExpression"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/metadata/expressions/functions": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * The functions of the expression language
         * @description The fixed list: no function can be added by an organization. For members who may view objects and fields.
         */
        get: operations["listExpressionFunctions"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/metadata/expressions/test": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Try an expression that is not stored yet on sample values
         * @description Answers what a formula would give (VALUE) or what a rule would do (REJECTED or PASSED), or ERROR with the place when the evaluation itself fails. There are no records yet, so the values are typed as text for the type of each field. Nothing is stored and nothing is logged. Needs metadata.view and either metadata.manage or metadata.publish.
         */
        post: operations["testExpression"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/metadata/field-types": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * The field types an organization can choose from
         * @description With the settings each type takes and the constraints it allows. For members who may view objects and fields. NOT_FOUND on the platform host, FORBIDDEN without the ability.
         */
        get: operations["listFieldTypes"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/metadata/formula-report": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * The stored formulas that do not pass the expression language
         * @description Formulas saved before formulas were checked keep their text and are listed here with the place that is wrong; nothing is removed and they cannot be saved again until fixed. For members who may view objects and fields.
         */
        get: operations["listInvalidStoredFormulas"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/metadata/notification-templates": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * The text of every kind of notice an administrator may write
         * @description For each kind: the text in use (the platform's own until somebody writes one), the platform's own text to start from, the placeholders the kind allows and the version to send back. For members who may view metadata.
         */
        get: operations["listNotificationTemplates"];
        put?: never;
        /**
         * Write the text of a kind of notice
         * @description Live at once, as a release of one change. The text is plain: at most 80 characters of title on one line and 500 of body, no markup, no link and no address (the platform adds its own link). It may use the placeholders of its kind in braces; each stands for a label of the organization and never for a value of a record. Audited by kind, never by the text. VALIDATION_ERROR names every place that is wrong, also when the text has been written already for the kind.
         */
        post: operations["createNotificationTemplate"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/metadata/notification-templates/check": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Check a text and see it with sample labels
         * @description The real rules answer. Nothing is stored, audited or logged, and no part of the text is repeated in the answer. The preview uses sample labels and never a value of a record. Needs metadata.view and metadata.manage or metadata.publish.
         */
        post: operations["checkNotificationTemplate"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/metadata/notification-templates/{kind}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        /**
         * Change the text of a kind of notice
         * @description Live at once, as a release of one change. The text is plain: at most 80 characters of title on one line and 500 of body, no markup, no link and no address (the platform adds its own link). It may use the placeholders of its kind in braces; each stands for a label of the organization and never for a value of a record. Audited by kind, never by the text. CONCURRENT_MODIFICATION when someone changed it since it was read; NOT_FOUND when the platform's own text is in use.
         */
        put: operations["updateNotificationTemplate"];
        post?: never;
        /**
         * Go back to the platform's own text
         * @description Live at once, as a release of one change. Notices already created are not rewritten: their words are built when they are read, so they show the platform's text from now on. The undo of the release writes the text again.
         */
        delete: operations["resetNotificationTemplate"];
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/metadata/objects": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * The objects of the organization
         * @description The standard objects of the platform and the organization's own, with how many fields each has. For members who may view objects and fields. NOT_FOUND on the platform host, FORBIDDEN without the ability.
         */
        get: operations["listObjects"];
        put?: never;
        /**
         * Create a custom object
         * @description The name is turned into the permanent API name with the ending __c. VALIDATION_ERROR for a name in use or not allowed, CONFLICT at the limit of objects. Audited.
         */
        post: operations["createObject"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/metadata/objects/{objectApiName}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * One object with all its fields
         * @description System fields first, then the standard ones, then the organization's own. NOT_FOUND for an object the organization does not have.
         */
        get: operations["getObject"];
        /**
         * Change the labels of a custom object
         * @description The API name never changes. FORBIDDEN for an object the platform defines, CONCURRENT_MODIFICATION when someone changed it since it was read. Audited.
         */
        put: operations["updateObject"];
        post?: never;
        /**
         * Remove a custom object with its fields
         * @description The permissions on the object and its fields end with it. CONFLICT while fields of other objects point to it or it has records. FORBIDDEN for an object the platform defines. Audited.
         */
        delete: operations["deleteObject"];
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/metadata/objects/{objectApiName}/authorship": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Who made and who last changed each definition of an object
         * @description For the organization's own fields, record types, page layouts, record pages and validation rules, and the object itself when it is the organization's own: the display name of an active member or Platform, and when. A person who is no longer a member has no name. The platform's standard definitions have no entry. NOT_FOUND for an object the organization does not have, FORBIDDEN without the ability metadata.view.
         */
        get: operations["getObjectAuthorship"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/metadata/objects/{objectApiName}/fields": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Add a custom field to an object
         * @description To a custom object, or to a standard object that allows it. The name is turned into the permanent API name with the ending __c. VALIDATION_ERROR lists every problem with the type, its settings and its constraints. Audited.
         */
        post: operations["createField"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/metadata/objects/{objectApiName}/fields/{fieldApiName}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        /**
         * Change a custom field
         * @description The API name, the object and the type never change; a picklist keeps every value it had. FORBIDDEN for a field the platform defines, CONCURRENT_MODIFICATION when someone changed it since it was read. Audited.
         */
        put: operations["updateField"];
        post?: never;
        /**
         * Remove a custom field
         * @description The permissions on the field end with it. FORBIDDEN for a field the platform defines. Audited.
         */
        delete: operations["deleteField"];
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/metadata/objects/{objectApiName}/fields/{fieldApiName}/test": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Try a formula field on sample values
         * @description Answers what the formula would give for a record with these values (VALUE), or ERROR with the place. VALIDATION_ERROR when the field is not a formula. Nothing is stored and nothing is logged. Needs metadata.view and either metadata.manage or metadata.publish.
         */
        post: operations["testFormulaField"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/metadata/objects/{objectApiName}/fields/{fieldApiName}/values": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Add a value to a picklist the organization owns the list of
         * @description From the form where a record is written: adds one value, and for a dependent picklist the values of the other field it goes with. It only adds; a value that exists is never renamed, switched off or removed this way, and adding what is there already changes nothing. Needs the ability to add values to picklists, and the member must be allowed to edit the field. NOT_FOUND for a field that is not such a picklist or one the member cannot read, FORBIDDEN without the ability or the right to edit the field, VALIDATION_ERROR for a value the rules refuse. Audited by counts.
         */
        post: operations["addFieldValue"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/metadata/objects/{objectApiName}/layout-assignments": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * The layout assignments of an object
         * @description The default layout of the object and the layouts chosen for profiles, with or without a record type. A profile that no longer exists has no name here and is ignored when a layout is chosen. NOT_FOUND for an object the organization does not have.
         */
        get: operations["listLayoutAssignments"];
        put?: never;
        /**
         * Choose a layout for a profile (and a record type), or the default layout of the object
         * @description Live at once, as a release of one change. With no profile it is the default layout of the object. A record type needs a profile: a layout for a record type alone is set on the record type. CONFLICT when the layout or record type does not exist. Audited.
         */
        post: operations["createLayoutAssignment"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/metadata/objects/{objectApiName}/layout-assignments/{key}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * One layout assignment
         * @description The key is default, the identifier of the profile, or the identifier of the profile, a tilde and the API name of the record type. NOT_FOUND for an assignment the object does not have.
         */
        get: operations["getLayoutAssignment"];
        /**
         * Point a layout assignment at another layout
         * @description The key is default, the identifier of the profile, or the identifier of the profile, a tilde and the API name of the record type. Live at once, as a release of one change. CONCURRENT_MODIFICATION when someone changed it since it was read. Audited.
         */
        put: operations["updateLayoutAssignment"];
        post?: never;
        /**
         * Remove a layout assignment
         * @description The key is default, the identifier of the profile, or the identifier of the profile, a tilde and the API name of the record type. Live at once, as a release of one change. Audited.
         */
        delete: operations["deleteLayoutAssignment"];
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/metadata/objects/{objectApiName}/layout-resolution": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Which layout an object uses for a profile and a record type
         * @description The first rule of this order that applies: the profile together with the record type, the record type alone (the layout of the record type), the profile alone, the default layout of the object. The answer names the rule; NONE when the object has no layout.
         */
        get: operations["resolveLayout"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/metadata/objects/{objectApiName}/layouts": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * The page layouts of an object
         * @description For members who may view objects and fields. NOT_FOUND for an object the organization does not have.
         */
        get: operations["listLayouts"];
        put?: never;
        /**
         * Add a page layout to an object
         * @description Live at once, as a release of one change. The name is turned into the permanent API name with the ending __c. The definition is checked against the schema of the same name in components.schemas of this document; VALIDATION_ERROR names every place that is wrong, for example definition.sections[1].fields[0].behaviour. CONFLICT for an object whose records belong to the platform, at the limit, or when the result would break a dependency (a required field the layout does not show). Audited.
         */
        post: operations["createLayout"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/metadata/objects/{objectApiName}/layouts/{layoutApiName}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * One page layout
         * @description NOT_FOUND for a layout the object does not have.
         */
        get: operations["getLayout"];
        /**
         * Change a page layout
         * @description Live at once, as a release of one change. The API name and the object never change. The definition is checked against the schema of the same name in components.schemas of this document; VALIDATION_ERROR names every place that is wrong, for example definition.sections[1].fields[0].behaviour. CONCURRENT_MODIFICATION when someone changed it since it was read. Audited.
         */
        put: operations["updateLayout"];
        post?: never;
        /**
         * Remove a page layout
         * @description Live at once, as a release of one change. CONFLICT, naming what still uses it, when a record type, record page or assignment depends on it. Audited.
         */
        delete: operations["deleteLayout"];
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/metadata/objects/{objectApiName}/record-page-assignments": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * The record page assignments of an object
         * @description The default record page of the object and the record pages chosen for profiles and record types. A profile that no longer exists has no name here and is ignored when a record page is chosen. NOT_FOUND for an object the organization does not have.
         */
        get: operations["listRecordPageAssignments"];
        put?: never;
        /**
         * Choose a record page for a profile, a record type, or both, or the default of the object
         * @description Live at once, as a release of one change. With no profile and no record type it is the default record page of the object. CONFLICT when the record page or record type does not exist. Audited.
         */
        post: operations["createRecordPageAssignment"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/metadata/objects/{objectApiName}/record-page-assignments/{key}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * One record page assignment
         * @description The key is default, type and a tilde and the API name of a record type for the record-type-alone rule, the identifier of the profile, or the identifier of the profile, a tilde and the API name of the record type. NOT_FOUND for an assignment the object does not have.
         */
        get: operations["getRecordPageAssignment"];
        /**
         * Point a record page assignment at another record page
         * @description The key is default, type and a tilde and the API name of a record type for the record-type-alone rule, the identifier of the profile, or the identifier of the profile, a tilde and the API name of the record type. Live at once, as a release of one change. CONCURRENT_MODIFICATION when someone changed it since it was read. Audited.
         */
        put: operations["updateRecordPageAssignment"];
        post?: never;
        /**
         * Remove a record page assignment
         * @description The key is default, type and a tilde and the API name of a record type for the record-type-alone rule, the identifier of the profile, or the identifier of the profile, a tilde and the API name of the record type. Live at once, as a release of one change. Audited.
         */
        delete: operations["deleteRecordPageAssignment"];
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/metadata/objects/{objectApiName}/record-page-resolution": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Which record page an object uses for a profile and a record type
         * @description The first rule of this order that applies: the profile together with the record type, the record type alone, the profile alone, the default record page of the object. The answer names the rule; NONE when the object has no record page.
         */
        get: operations["resolveRecordPage"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/metadata/objects/{objectApiName}/record-pages": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * The record pages of an object
         * @description For members who may view objects and fields. NOT_FOUND for an object the organization does not have.
         */
        get: operations["listRecordPages"];
        put?: never;
        /**
         * Add a record page to an object
         * @description Live at once, as a release of one change. The definition is checked against the schema of the same name in components.schemas of this document; VALIDATION_ERROR names every place that is wrong, for example definition.sections[1].fields[0].behaviour. Conditions that decide when a component or action is shown are closed data (a field compared with a value, or an ability, combined with all/any to two levels); there is no expression text. CONFLICT when the layout or a field it names does not exist or a related list does not follow a relationship. Audited.
         */
        post: operations["createRecordPage"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/metadata/objects/{objectApiName}/record-pages/{pageApiName}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * One record page
         * @description NOT_FOUND for a record page the object does not have.
         */
        get: operations["getRecordPage"];
        /**
         * Change a record page
         * @description Live at once, as a release of one change. The API name and the object never change. The definition is checked against the schema of the same name in components.schemas of this document; VALIDATION_ERROR names every place that is wrong, for example definition.sections[1].fields[0].behaviour. CONCURRENT_MODIFICATION when someone changed it since it was read. Audited.
         */
        put: operations["updateRecordPage"];
        post?: never;
        /**
         * Remove a record page
         * @description Live at once, as a release of one change. CONFLICT, naming the application, when a navigation item opens it. Audited.
         */
        delete: operations["deleteRecordPage"];
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/metadata/objects/{objectApiName}/record-type-assignments": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Every record type assignment of an object
         * @description A record type with no profile assigned is open to every profile; with at least one it is open only to the profiles named. A profile that no longer exists has no name here; its assignment still restricts the record type until it is removed. NOT_FOUND for an object the organization does not have.
         */
        get: operations["listRecordTypeAssignmentsOfObject"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/metadata/objects/{objectApiName}/record-types": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * The record types of an object
         * @description For members who may view objects and fields. NOT_FOUND for an object the organization does not have, on the platform host.
         */
        get: operations["listRecordTypes"];
        put?: never;
        /**
         * Add a record type to an object
         * @description Live at once, as a release of one change. The name is turned into the permanent API name with the ending __c. VALIDATION_ERROR lists every problem with the fields and picklist values named; CONFLICT for an object whose records belong to the platform, at the limit, or when the result would break a dependency. Audited.
         */
        post: operations["createRecordType"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/metadata/objects/{objectApiName}/record-types/{recordTypeApiName}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * One record type
         * @description NOT_FOUND for a record type the object does not have.
         */
        get: operations["getRecordType"];
        /**
         * Change a record type
         * @description Live at once, as a release of one change. The API name and the object never change. CONCURRENT_MODIFICATION when someone changed it since it was read. Audited.
         */
        put: operations["updateRecordType"];
        post?: never;
        /**
         * Remove a record type
         * @description Live at once, as a release of one change. NOT_FOUND for a record type the object does not have. Audited.
         */
        delete: operations["deleteRecordType"];
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/metadata/objects/{objectApiName}/record-types/{recordType}/profiles": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * The profiles that may use a record type
         * @description A record type with no profile assigned is open to every profile; with at least one it is open only to the profiles named. An empty list means the record type is open to every profile. NOT_FOUND for an object the organization does not have.
         */
        get: operations["listRecordTypeAssignments"];
        put?: never;
        /**
         * Let a profile use a record type
         * @description A record type with no profile assigned is open to every profile; with at least one it is open only to the profiles named. Live at once, as a release of one change. The first assignment of a record type closes it to every other profile; the record type in the path is the one meant. With defaultForProfile the record type becomes the one a new record of the profile gets when it names none, and the profile's previous default of the object loses the mark. CONFLICT when the record type does not exist. VALIDATION_ERROR for a profile that does not exist or a default that is not in use. Audited.
         */
        post: operations["createRecordTypeAssignment"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/metadata/objects/{objectApiName}/record-types/{recordType}/profiles/{profileId}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * One profile's use of a record type
         * @description NOT_FOUND for an assignment the record type does not have.
         */
        get: operations["getRecordTypeAssignment"];
        /**
         * Make a record type the default of a profile, or take the mark off
         * @description Live at once, as a release of one change. Making it the default takes the mark off the profile's previous default of the object. CONCURRENT_MODIFICATION when someone changed it since it was read. Audited.
         */
        put: operations["updateRecordTypeAssignment"];
        post?: never;
        /**
         * Stop a profile from using a record type
         * @description A record type with no profile assigned is open to every profile; with at least one it is open only to the profiles named. Removing the last assignment of a record type opens it to every profile again. Records that already have the type keep it. Live at once, as a release of one change. Audited.
         */
        delete: operations["deleteRecordTypeAssignment"];
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/metadata/objects/{objectApiName}/relationships": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * The relationships of an object, in both directions
         * @description The objects this one points at (parents), the lists of other objects that point at it (children), and the objects related through a junction object (many-to-many). Read from the lookup and master-detail fields, with what happens to a child when its parent is removed. NOT_FOUND for an object the organization does not have.
         */
        get: operations["getObjectRelationships"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/metadata/objects/{objectApiName}/validation-rules": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * The validation rules of an object
         * @description For members who may view objects and fields. NOT_FOUND for an object the organization does not have.
         */
        get: operations["listValidationRules"];
        put?: never;
        /**
         * Add a validation rule to an object
         * @description Live at once, as a release of one change. The name is turned into the permanent API name with the ending __c. The condition is in the expression language and is true when the record must be REJECTED; a blank answer does not reject. It is parsed here (a VALIDATION_ERROR names the place); whether its fields exist and whether it gives true or false is checked after the change, and a CONFLICT names what is wrong. CONFLICT for an object whose records belong to the platform, at the limit, or when the result would break a dependency. Audited by name and counts, never by the text.
         */
        post: operations["createValidationRule"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/metadata/objects/{objectApiName}/validation-rules/{ruleApiName}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * One validation rule
         * @description NOT_FOUND for a rule the object does not have.
         */
        get: operations["getValidationRule"];
        /**
         * Change a validation rule
         * @description Live at once, as a release of one change. The API name and the object never change. The condition is in the expression language and is true when the record must be REJECTED; a blank answer does not reject. It is parsed here (a VALIDATION_ERROR names the place); whether its fields exist and whether it gives true or false is checked after the change, and a CONFLICT names what is wrong. CONCURRENT_MODIFICATION when someone changed it since it was read.
         */
        put: operations["updateValidationRule"];
        post?: never;
        /**
         * Remove a validation rule
         * @description Live at once, as a release of one change. The undo of the release makes it again.
         */
        delete: operations["deleteValidationRule"];
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/metadata/objects/{objectApiName}/validation-rules/{ruleApiName}/test": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Try a validation rule on sample values
         * @description Answers what would happen to a record with these values: REJECTED with the message of the rule, PASSED, or ERROR with the place when the evaluation itself fails (for example a division by zero). There are no records yet, so the values are typed as text for the type of each field. Nothing is stored and nothing is logged. Needs metadata.view and either metadata.manage or metadata.publish.
         */
        post: operations["testValidationRule"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/metadata/releases": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * The history of publications
         * @description Newest first: each release says what it added, changed or removed, whether it was rolled back, and whether it is the latest. For members who may view objects and fields.
         */
        get: operations["listReleases"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/metadata/releases/latest/rollback": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Roll back the latest release
         * @description Undoes the latest release as a new release. Only the latest can be rolled back; rolling back a rollback redoes it. CONFLICT with every problem when it cannot be undone. Needs the abilities to publish and to view. Audited.
         */
        post: operations["rollbackLatestRelease"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/metadata/releases/latest/rollback-check": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Check what rolling back the latest release would do
         * @description Applies the undo by the real rules and rolls everything back. The answer lists every problem, including records or values that would be lost, and what would be undone.
         */
        post: operations["checkRollback"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/metadata/tabs": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * The tabs of the organization, as defined
         * @description For members who may view objects and fields.
         */
        get: operations["listTabDefinitions"];
        put?: never;
        /**
         * Add a tab
         * @description Live at once, as a release of one change. The definition is checked against the schema of the same name in components.schemas of this document; VALIDATION_ERROR names every place that is wrong, for example definition.sections[1].fields[0].behaviour. A tab of kind CUSTOM names the custom page it shows. CONFLICT when that page does not exist or at the limit. Audited.
         */
        post: operations["createTab"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/metadata/tabs/{apiName}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * One tab, as defined
         * @description NOT_FOUND for a tab the organization does not have.
         */
        get: operations["getTabDefinition"];
        /**
         * Change a tab
         * @description Live at once, as a release of one change. The API name never changes. The definition is checked against the schema of the same name in components.schemas of this document; VALIDATION_ERROR names every place that is wrong, for example definition.sections[1].fields[0].behaviour. CONCURRENT_MODIFICATION when someone changed it since it was read. Audited.
         */
        put: operations["updateTab"];
        post?: never;
        /**
         * Remove a tab
         * @description Live at once, as a release of one change. CONFLICT, naming what still uses it, when something depends on it. NOT_FOUND for a tab the organization does not have. Audited.
         */
        delete: operations["deleteTab"];
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/metadata/workflows": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * The workflows of the organization
         * @description For members who may view workflows. Name an object to list only its workflows. NOT_FOUND for an object the organization does not have.
         */
        get: operations["listWorkflows"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/metadata/workflows/action-types": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * What a workflow can be made of
         * @description The types of action with the settings each takes, the kinds of trigger with the properties each uses, and the limits the platform enforces. The screens build their palette and forms from this and hold no list of their own. For members who may view workflows.
         */
        get: operations["getWorkflowCatalogue"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/metadata/workflows/{objectApiName}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Add a workflow to an object
         * @description Live at once, as a release of one change. The name is turned into the permanent API name with the ending __c. The trigger, the condition and the actions are closed data: the condition is in the expression language (true or false, it may use TODAY() and NOW()), and the actions are of the types of this release. The request is checked here (a VALIDATION_ERROR names every place); whether the fields it names exist is checked after the change and a CONFLICT names what is wrong. Executions run after the commit of the record change, never inside it. CONFLICT for an object whose records belong to the platform, at the limit, when the plan has no workflows, or when the result would break a dependency. Audited by name and counts, never by the text.
         */
        post: operations["createWorkflow"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/metadata/workflows/{objectApiName}/check": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Try a condition on sample values, and the actions on the object
         * @description Answers what would happen to a record with these values: RUNS (the condition is true, or there is none), SKIPPED (false or no value) or ERROR with the place when the evaluation itself fails. A condition that is not valid is a VALIDATION_ERROR naming the place. There are no records yet, so the values are typed as text for the type of each field. Name actions to learn, by place, which fields they name do not exist or cannot be set, which literals do not fit and which required field of a created record is missing. Nothing is stored and nothing is logged. Needs workflows.manage.
         */
        post: operations["checkWorkflowCondition"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/metadata/workflows/{objectApiName}/{workflowApiName}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * One workflow
         * @description NOT_FOUND for a workflow the object does not have.
         */
        get: operations["getWorkflow"];
        /**
         * Change a workflow
         * @description Live at once, as a release of one change. The API name and the object never change. A change of the trigger, the condition or the actions makes a new version of the definition; executions already queued keep the version they were created under. The trigger, the condition and the actions are closed data: the condition is in the expression language (true or false, it may use TODAY() and NOW()), and the actions are of the types of this release. The request is checked here (a VALIDATION_ERROR names every place); whether the fields it names exist is checked after the change and a CONFLICT names what is wrong. Executions run after the commit of the record change, never inside it. CONCURRENT_MODIFICATION when someone changed it since it was read.
         */
        put: operations["updateWorkflow"];
        post?: never;
        /**
         * Remove a workflow
         * @description Live at once, as a release of one change. Its queued executions end as CANCELLED with the reason workflow_removed; finished ones stay in the history until the clean-up. The undo of the release makes it again, as a new workflow.
         */
        delete: operations["deleteWorkflow"];
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/metadata/workflows/{objectApiName}/{workflowApiName}/activation": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Switch a workflow on or off
         * @description Live at once, as a release of one change, and without a new version of the definition. A workflow that is switched off starts no new execution and also stops the queued ones (they end as SKIPPED with the reason workflow_inactive). CONFLICT when the plan has no workflows and the workflow is being switched on. CONCURRENT_MODIFICATION when someone changed it since it was read.
         */
        post: operations["activateWorkflow"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/notification-preferences": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * The caller's choices of which notices and mails they want
         * @description One entry per category and channel, with the words to show, whether it is on, and whether the platform fixes it on. A switch the caller never touched is on.
         */
        get: operations["getNotificationPreferences"];
        /**
         * Change some of the caller's choices
         * @description Only the switches named change. A switch the platform fixes on (the account category) is refused with VALIDATION_ERROR naming the place. A switch that is off creates no in-app notice from now on and sends no mail of that category; nothing already made is removed.
         */
        put: operations["updateNotificationPreferences"];
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/notifications": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * The caller's notices
         * @description Newest first, cursor paging. Only the caller's own notices are ever returned. The words are built now from the template of the kind, so a change of text reaches old notices; they hold labels and never a value of a record. The href leads to the inbox or to a record; the page it opens decides what the caller may see. Narrow to the unread ones with unread=true.
         */
        get: operations["listNotifications"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/notifications/read-all": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Mark all of the caller's notices read
         * @description Only the caller's own.
         */
        post: operations["markAllNotificationsRead"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/notifications/summary": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * How many notices the caller has not read
         * @description For the bell in the header: one count, cheap to ask on focus.
         */
        get: operations["getNotificationSummary"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/notifications/{id}/read": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Mark one of the caller's notices read
         * @description NOT_FOUND for a notice that is not the caller's, exactly like one that does not exist. Marking a read notice again changes nothing.
         */
        post: operations["markNotificationRead"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/objects/{objectApiName}/form": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Describe an object for the signed-in member: fields, record types, layout, permissions
         * @description What a screen needs to draw the list, the create form and the edit form of the object for this member, in one answer. Only the fields the member may read are described (the others are absent, not masked), restricted to what the record type offers, with what they may type, the comparisons a filter accepts and whether a list can be ordered by each. The layout is the object's page layout for the member's profile and the record type, reduced to what they see, or one the platform makes when the object has none. recordType describes the object for one of the record types the member may use (VALIDATION_ERROR at recordType for any other, answered like a type that does not exist); without it, recordId (the record being edited) decides by the type that record holds, and without both the member's default type does. layout names the page layout to draw (a record page names one); a name that is not a layout of the object is ignored and the member's own resolution decides. Presentation only: every request that follows is decided again. NOT_FOUND for an object that does not exist, that a platform module owns or that the caller may not read (all three answer alike). Also NOT_FOUND for a record they cannot reach. Organization hosts only.
         */
        get: operations["getObjectForm"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/objects/{objectApiName}/formula-recalculation": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Whether the stored formula results of an object are current
         * @description Editing a formula leaves the results stored in existing records as they were; a background pass rewrites the ones that differ, without a new version and without an audit record per record (one audit record per pass, with counts). The state says whether the results belong to the formulas as they are now (CURRENT) or not (NEEDED, PENDING, RUNNING, CLOSING), the progress of the pass in flight and the counts of the last one. While the results are not current a filter or an order on a formula field is refused. There is no screen for this yet. Needs the ability to view metadata. Organization hosts only. NOT_FOUND for an object that does not exist, that a platform module owns (a user, a profile ...) or that the caller may not read: all three answer alike.
         */
        get: operations["getFormulaRecalculation"];
        put?: never;
        /**
         * Start the recalculation of the stored formula results of an object
         * @description Starts a pass over the live records of the object even when the results are believed to be current, for example after records were changed by a path that does not calculate formulas. A pass that is already waiting or running does the work: asking again changes nothing. The answer is the state (the pass runs in the background, in batches). CONFLICT for an object without formulas. There is no screen for this yet. Needs the ability to publish metadata. Organization hosts only. NOT_FOUND for an object that does not exist, that a platform module owns (a user, a profile ...) or that the caller may not read: all three answer alike.
         */
        post: operations["startFormulaRecalculation"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/objects/{objectApiName}/imports": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * The caller's latest imports into an object
         * @description Newest first, at most twenty. NOT_FOUND for an object that does not exist, that a platform module owns or that the caller may not read, and for an import that is not the caller's. FORBIDDEN for a caller who may read the object but not create records in it.
         */
        get: operations["listImports"];
        put?: never;
        /**
         * Start a CSV import into an object
         * @description Checks the mapping against the object at once (VALIDATION_ERROR lists every problem). The import waits for its file: send it with the next request. Every row of the file will be written by the same steps as a record written through the API, as the caller, with the caller's permissions: a field the caller may not write, a formula or an auto-number cannot be mapped. CREATE makes a record for every row; UPSERT finds the record by the value of a field flagged as an external identifier (one indexed read) and updates it, or makes it. A link can be written as the other record's identifier or, with a mapping such as 'field:externalIdField', as its external identifier. An empty cell leaves a field as it is. A dry run checks every row by the real write and saves nothing. CONFLICT at the most imports an organization can run at once. NOT_FOUND for an object that does not exist, that a platform module owns or that the caller may not read, and for an import that is not the caller's. FORBIDDEN for a caller who may read the object but not create records in it.
         */
        post: operations["startImport"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/objects/{objectApiName}/imports/{importId}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * How far an import has got
         * @description The state, the rows read, made, changed and refused, and why it stopped when it failed. NOT_FOUND for an object that does not exist, that a platform module owns or that the caller may not read, and for an import that is not the caller's. FORBIDDEN for a caller who may read the object but not create records in it.
         */
        get: operations["getImport"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/objects/{objectApiName}/imports/{importId}/cancel": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Stop an import
         * @description The import stops at the end of the batch it is in; what it already wrote stays (there is no undo). CONFLICT when it is not waiting or running. Audited. NOT_FOUND for an object that does not exist, that a platform module owns or that the caller may not read, and for an import that is not the caller's. FORBIDDEN for a caller who may read the object but not create records in it.
         */
        post: operations["cancelImport"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/objects/{objectApiName}/imports/{importId}/errors": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * The report of the rows of an import that failed
         * @description A CSV file: the number of the row (data rows, from 1), the reason, and the row as the file had it, so it can be corrected and sent again. A reason is a message of the write steps and never repeats a value. Removed some days after the import ended. NOT_FOUND when there is no report. NOT_FOUND for an object that does not exist, that a platform module owns or that the caller may not read, and for an import that is not the caller's. FORBIDDEN for a caller who may read the object but not create records in it.
         */
        get: operations["downloadImportErrors"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/objects/{objectApiName}/imports/{importId}/file": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        /**
         * Send the file of an import
         * @description The body is the CSV file as it is (UTF-8, the delimiter chosen when the import was started, a header row first, values that hold the delimiter or a line break in double quotes), with its length announced (chunked bodies are refused), at most 20 MiB and 100,000 rows. A file that is not text is refused. CONFLICT when the file was already sent or when another import of the object is waiting or running. The import is queued and runs in the background; watch it with the import endpoint. SERVICE_UNAVAILABLE (503) when the object store does not answer. NOT_FOUND for an object that does not exist, that a platform module owns or that the caller may not read, and for an import that is not the caller's. FORBIDDEN for a caller who may read the object but not create records in it.
         */
        put: operations["sendImportFile"];
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/objects/{objectApiName}/index-status": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Where the indexed fields of an object stand
         * @description For each field flagged as indexed (and each field whose index is being removed): waiting, filling with the number of records done, complete, or clearing. Queries use a field's index only when it is complete. Also, for each unique field and each junction: waiting for the copy of the field to be complete, enforcing (existing records are being checked in; new values are refused already), conflict (two existing records hold the same value: the count is given and the check resumes when the data is fixed) or enforced. Needs the ability to view metadata. Organization hosts only. NOT_FOUND for an object that does not exist, that a platform module owns (a user, a profile ...) or that the caller may not read: all three answer alike.
         */
        get: operations["getObjectIndexStatus"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/objects/{objectApiName}/lookups/{fieldApiName}/labels": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * The names of the records a link field points to
         * @description For the identifiers a list or a form holds in a lookup or master-detail field (at most 100). Each record is named by the field that names a record of the target object for the caller (the first searchable text field, else the first required one, else the auto-number); a person is named by their display name while their membership is active. An identifier that is missing from the answer names a record the caller may not read, one that does not exist, one in the recycle bin, or a person who is no longer a member, and the answer does not say which. The target is judged by the caller's own permissions and record-level security. A POST only because the identifiers travel in the body. VALIDATION_ERROR for a value that is not an identifier. NOT_FOUND for an object that does not exist, that a platform module owns or that the caller may not read, and for a field that is not a link of the object or that the caller may not read (all answer alike).
         */
        post: operations["getLookupLabels"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/objects/{objectApiName}/lookups/{fieldApiName}/options": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * The records a link field could point to, for a picker
         * @description Records of the target object the caller may read whose name contains the text, a few at a time (at most 25, 10 by default): a search over the searchable fields when the target has some, otherwise a match on the name field. Without text the first records in name order. People are offered while their membership is active. Nothing here lists a directory: the text must match. A POST only because the text travels in the body, never in the address, where every proxy on the way would log it. The write that follows is judged again (a link is accepted only to a record the caller can read). NOT_FOUND for an object that does not exist, that a platform module owns or that the caller may not read, and for a field that is not a link of the object or that the caller may not read (all answer alike).
         */
        post: operations["getLookupOptions"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/objects/{objectApiName}/record-page": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * The record page of an object, resolved for the signed-in member
         * @description The header, the tabs and the actions, each already filtered to what the caller may reach; a block or an action the caller may not use is left out, never shown as refused. With no recordType and no recordId the object's own default rule decides; with a recordId its own record type is used unless recordType overrides it. NOT_FOUND for an object the caller may not read or that does not exist, or a record they cannot reach.
         */
        get: operations["getRecordPage_1"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/objects/{objectApiName}/records": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * The live records of an object, in identifier order
         * @description One page at a time by the cursor of the previous page. For a filter, an order or links use the query endpoint. Only the records the caller may reach are listed: the ones they own, the ones below them in the role hierarchy, the ones a sharing rule or an explicit share gives them and, when the object is public, all of them. Fields the caller may not read are left out of every record. NOT_FOUND for an object that does not exist, that a platform module owns (a user, a profile ...) or that the caller may not read: all three answer alike.
         */
        get: operations["listRecords"];
        put?: never;
        /**
         * Create a record of an object
         * @description The write goes through these stages in this order, and the first stage that finds problems answers VALIDATION_ERROR with all of its problems, each with its place, and saves nothing: the record type (it exists, is in use and offers the fields written), the field permission (every field written must be one the caller may edit; a field the caller may not read is answered like a field that does not exist), the type of each value, the required fields (judged on the record as it will be), the linked records, the unique fields (no other live record may hold the same value; a record in the recycle bin does not count, and a text is compared exactly), the validation rules, then the formulas, which are calculated and stored. Defaults and auto-numbers are applied on create. Before any of this, a change of an existing record is judged like a read of it: NOT_FOUND when the caller cannot read the record, FORBIDDEN when they can read it but may not change it (the default of the object, the role hierarchy, sharing rules, shares, modify-all). A link is accepted only to a record the caller can read. A refusal of a duplicate says that the value is in use and never whose record holds it. NOT_FOUND for an object that does not exist, that a platform module owns (a user, a profile ...) or that the caller may not read: all three answer alike. FORBIDDEN for a caller who may read the object but not create in it. The caller owns the record. The answer leaves out the fields the caller may not read. Audited and announced by object, identifier, version and field names, never by values.
         */
        post: operations["createRecord"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/objects/{objectApiName}/records/bulk": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Create, change or delete many records of an object in one call
         * @description Every record goes through exactly the steps the same single request goes through (the gate, field permissions, types, required fields, links, unique values, validation rules, formulas, auto-numbers) and is audited and announced like it, once for each record; there is no faster private path. The answer gives the outcome of each record at its position in the request: CREATED, UPDATED, DELETED, FAILED (with the code, message and problems a single request would have answered) or ROLLED_BACK. By default each record stands on its own: one that is refused is undone alone and the others are saved. With allOrNone one refusal undoes the whole call (committed is false, nothing is saved). A change carries the version that was read; a conflict is the outcome of that record. At most 200 records and 8 MiB per call (settings of the deployment): above that the call is refused before anything is read (400 VALIDATION_ERROR for the count, 413 PAYLOAD_TOO_LARGE for the size). One audit record per call counts what it did, never a value. NOT_FOUND for an object that does not exist, that a platform module owns or that the caller may not read; FORBIDDEN for a caller who may read the object but not do the operation in it (decided once for the whole call, by the same gate as a single request). Whether the caller may change or delete a particular record is judged for each record like a single request: its outcome is NOT_FOUND when the caller cannot read the record and FORBIDDEN when they can read it but may not do this to it.
         */
        post: operations["bulkRecords"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/objects/{objectApiName}/records/query": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Ask a question of the records of an object: filter, order, fields, links, one page at a time
         * @description The body is a query model, never SQL: a tree of conditions (AND, OR, and comparisons by the kind of field), at most two sort keys, the fields to bring and the links to follow (one level). Names are looked up in the metadata and values are data. A field the caller may not read is answered like a field that does not exist, in a filter, an order, a field list or a link, so the answer never reveals what the caller may not see; records leave out such fields. The first stage that finds problems answers VALIDATION_ERROR with all its problems and their places, in the order: shape, fields, operators and values. A filter or an order on a field without a complete index is allowed only on an object with few records; on a larger one the answer is VALIDATION_ERROR at 'query' saying which field to flag as indexed (or which condition to add), never a slow request. A formula field can be used in a filter or an order (it is read from the stored result, without an index, so the same rule applies), but only while the stored results are current for the object's formulas: after a formula is edited the answer is VALIDATION_ERROR saying the results are being brought up to date, until the recalculation has finished (see the formula-recalculation endpoint). Records without a value come last in both directions; ties are broken by the identifier. There is no total: the page says whether another follows and gives a cursor that is good only for the same query. Only the records the caller may reach are searched. NOT_FOUND for an object that does not exist, that a platform module owns (a user, a profile ...) or that the caller may not read: all three answer alike.
         */
        post: operations["queryRecords"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/objects/{objectApiName}/records/search": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Search the words of the searchable fields of an object
         * @description A record must have every word of the text (accents and case do not matter); the last word counts as the beginning of a word, so a search can be run while a person types. The best matches come first (a match in the first searchable field counts most), then by identifier, one page at a time. Only the fields flagged as searchable that the caller may read are searched (a record never matches on a field the caller cannot see), and only the records the caller may reach are found; what comes back is shaped like the answer of a query and nothing is highlighted. A field is searched once its words are complete (the index status of the object says when). VALIDATION_ERROR for a text with no word, for an object with no searchable field the caller may read, and for a search whose every word is very common (type more). Search across objects is not offered. NOT_FOUND for an object that does not exist, that a platform module owns (a user, a profile ...) or that the caller may not read: all three answer alike.
         */
        post: operations["searchRecords"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/objects/{objectApiName}/records/validate": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Ask what the server would say to a save, without saving
         * @description The create or the change of a record (the one with recordId) run through every stage of the write pipeline (record type, field permission, the type of each value, required fields, linked records, unique fields, validation rules, formulas) and then undone: nothing is stored, no auto-number is used up, nothing is audited or announced. A save that would be refused is refused here with the same VALIDATION_ERROR, the same places and the same words, because it is the same code; one that would work answers with the results its formulas would give (and the formula fields it would leave empty). A change of an existing record is judged first like a real one: NOT_FOUND for a record the caller cannot read, FORBIDDEN for one they may read and not change. FORBIDDEN for a caller who may read the object but not create (or change) in it. NOT_FOUND for an object that does not exist, that a platform module owns or that the caller may not read (all three answer alike).
         */
        post: operations["validateRecord"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/objects/{objectApiName}/records/{id}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * One record
         * @description NOT_FOUND for an object that does not exist, that a platform module owns (a user, a profile ...) or that the caller may not read: all three answer alike. NOT_FOUND also for a record that does not exist in this organization, or that is in the recycle bin.
         */
        get: operations["getRecord"];
        put?: never;
        post?: never;
        /**
         * Move a record to the recycle bin
         * @description The record can be restored for the retention period, then it is removed for good. A second delete of the same record is a CONFLICT (409): it is already in the recycle bin. NOT_FOUND for an object that does not exist, that a platform module owns (a user, a profile ...) or that the caller may not read: all three answer alike. FORBIDDEN without permission to delete in the object, or for a record the caller can read but may not delete; NOT_FOUND for a record the caller cannot read. A master takes its details with it only when the caller may delete them too.
         */
        delete: operations["deleteRecord"];
        options?: never;
        head?: never;
        /**
         * Change some values of a record
         * @description The write goes through these stages in this order, and the first stage that finds problems answers VALIDATION_ERROR with all of its problems, each with its place, and saves nothing: the record type (it exists, is in use and offers the fields written), the field permission (every field written must be one the caller may edit; a field the caller may not read is answered like a field that does not exist), the type of each value, the required fields (judged on the record as it will be), the linked records, the unique fields (no other live record may hold the same value; a record in the recycle bin does not count, and a text is compared exactly), the validation rules, then the formulas, which are calculated and stored. Defaults and auto-numbers are applied on create. Before any of this, a change of an existing record is judged like a read of it: NOT_FOUND when the caller cannot read the record, FORBIDDEN when they can read it but may not change it (the default of the object, the role hierarchy, sharing rules, shares, modify-all). A link is accepted only to a record the caller can read. A refusal of a duplicate says that the value is in use and never whose record holds it. Only the fields named change; a null value or a name in clear removes a value. The request carries the version that was read: CONCURRENT_MODIFICATION (409) when the record has another version now, and nothing is changed. CONFLICT (409) when the record is in the recycle bin. NOT_FOUND for an object that does not exist, that a platform module owns (a user, a profile ...) or that the caller may not read: all three answer alike. FORBIDDEN without permission to update the object.
         */
        patch: operations["updateRecord"];
        trace?: never;
    };
    "/api/v1/objects/{objectApiName}/records/{id}/approval": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * The approval panel of a record
         * @description Whether the record is locked, the pending request or the last one that ended, the processes the caller could submit it under, and what the caller may do now. The record must be readable by the caller; otherwise it does not exist. A field the caller may not read is never shown, and neither is any value of the record.
         */
        get: operations["getRecordApproval"];
        put?: never;
        /**
         * Submit a record for approval
         * @description For a member who may edit the record. The process is the one named, or the only active one whose entry criteria the record meets (none, or more than one, is a CONFLICT that says so). CONFLICT when the first step names nobody who can decide, when the plan has no approvals or when another person's request is pending; a request the caller submitted already is returned unchanged. The record is locked in the same transaction. The comment is text of a person: at most 2000 characters, kept in the history and shown only to those who may see it; it is never logged and never sent in an event or a mail.
         */
        post: operations["submitRecordForApproval"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/objects/{objectApiName}/records/{id}/files": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * The files of a record
         * @description In the order they were stored. Needs the right to read the record. NOT_FOUND for an object or record that does not exist, that the caller may not reach, or that is in the recycle bin: all answer alike. SERVICE_UNAVAILABLE (503, with Retry-After) when the object store does not answer; nothing is stored then.
         */
        get: operations["listRecordFiles"];
        put?: never;
        /**
         * Store a file on a record
         * @description The body is the bytes of the file, as they are (not a form), with the length announced (Content-Length; chunked bodies are refused). The name is the header 'X-File-Name', percent-encoded as a URI component (never a query parameter: a request's path and query string are the part of a request that a log at DEBUG prints, and a file's name is a value typed by a person), the type the Content-Type of the request. The server cleans the name (only its last part is kept), decides the type, refuses names and types on the denied list and files that start like a program or a page a browser would run (VALIDATION_ERROR), refuses a file above the largest size (PAYLOAD_TOO_LARGE, checked on the announced length and on the bytes that pass) and a record that has the most files (CONFLICT). The size and the SHA-256 in the answer are those of the bytes that were stored. A failed upload leaves nothing. Needs the right to change the record. Audited by identifiers and size, never by name or content. NOT_FOUND for an object or record that does not exist, that the caller may not reach, or that is in the recycle bin: all answer alike. SERVICE_UNAVAILABLE (503, with Retry-After) when the object store does not answer; nothing is stored then.
         */
        post: operations["uploadRecordFile"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/objects/{objectApiName}/records/{id}/files/{fileId}": {
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
         * Remove a file from a record
         * @description The file is gone at once; its bytes are removed from the object store by a background pass. Needs the right to change the record. Audited. NOT_FOUND for an object or record that does not exist, that the caller may not reach, or that is in the recycle bin: all answer alike. SERVICE_UNAVAILABLE (503, with Retry-After) when the object store does not answer; nothing is stored then.
         */
        delete: operations["deleteRecordFile"];
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/objects/{objectApiName}/records/{id}/files/{fileId}/content": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * The bytes of a file
         * @description Sent as an attachment with the type the server decided, with nosniff and a sandbox policy, never to be shown in the page. Authorized on every request: needs the right to read the record. Audited by identifiers. NOT_FOUND for an object or record that does not exist, that the caller may not reach, or that is in the recycle bin: all answer alike. SERVICE_UNAVAILABLE (503, with Retry-After) when the object store does not answer; nothing is stored then.
         */
        get: operations["downloadRecordFile"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/objects/{objectApiName}/records/{id}/owner": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Give a record to another owner
         * @description The new owner must be an active member. The request carries the version that was read: CONCURRENT_MODIFICATION (409) when the record has another version now. Needs the right to change the record. The record keeps its shares. Audited and announced like a change of the record. NOT_FOUND for an object that does not exist, that a platform module owns (a user, a profile ...) or that the caller may not read: all three answer alike.
         */
        post: operations["changeRecordOwner"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/objects/{objectApiName}/records/{id}/share-targets": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * The roles or public groups a record can be shared with
         * @description For whoever may share the record (the right to change it): the names and identifiers of the live roles (kind ROLE) or public groups (kind GROUP) of the organization whose name contains the search text, at most 25, by name. It says nothing about the people in them. VALIDATION_ERROR for another kind or a search text over 80 characters. NOT_FOUND for an object that does not exist, that a platform module owns (a user, a profile ...) or that the caller may not read: all three answer alike. NOT_FOUND also for a record the caller cannot reach; FORBIDDEN for a caller who can read the record but not change it.
         */
        get: operations["listRecordShareTargets"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/objects/{objectApiName}/records/{id}/shares": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Who a record is shared with
         * @description For whoever may change the record. NOT_FOUND for an object that does not exist, that a platform module owns (a user, a profile ...) or that the caller may not read: all three answer alike. NOT_FOUND also for a record the caller cannot reach; FORBIDDEN for a caller who can read the record but not change it.
         */
        get: operations["listRecordShares"];
        /**
         * Share a record with a user, a public group or a role
         * @description ROLE reaches the members whose own role it is; ROLE_AND_SUBORDINATES reaches the members of that role and of every role below it. Sharing again with the same user, group or role changes the level (and for a role the kind). READ lets them read the record, READ_WRITE lets them read and change it; a share never gives the right to delete. Visible at the next question on every instance, and allowed while an approval has the record locked. Needs the right to change the record. VALIDATION_ERROR for the owner, for someone who is not a member, for a group or role that does not exist; CONFLICT at the most shares a record can have. Audited by kind and level, never by a name or a value. NOT_FOUND for an object that does not exist, that a platform module owns (a user, a profile ...) or that the caller may not read: all three answer alike.
         */
        put: operations["shareRecord"];
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/objects/{objectApiName}/records/{id}/shares/{granteeId}": {
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
         * Take a share back
         * @description NOT_FOUND when the record is not shared with that user, group or role (the identifier is the grantee's, whatever its kind). Needs the right to change the record. Audited. NOT_FOUND for an object that does not exist, that a platform module owns (a user, a profile ...) or that the caller may not read: all three answer alike.
         */
        delete: operations["unshareRecord"];
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/objects/{objectApiName}/recycle-bin": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * The records of an object that are in the recycle bin
         * @description In identifier order, one page at a time. Needs permission to delete in the object. Shows the records the caller may bring back (those they may delete). NOT_FOUND for an object that does not exist, that a platform module owns (a user, a profile ...) or that the caller may not read: all three answer alike.
         */
        get: operations["listDeletedRecords"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/objects/{objectApiName}/recycle-bin/{id}": {
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
         * Remove a record in the recycle bin for good
         * @description Needs permission to delete in the object and the right to delete the record: NOT_FOUND for a record the caller cannot read, FORBIDDEN for one they can read but may not delete. CONFLICT (409) for a live record: delete it first. This cannot be undone. NOT_FOUND for an object that does not exist, that a platform module owns (a user, a profile ...) or that the caller may not read: all three answer alike.
         */
        delete: operations["purgeRecord"];
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/objects/{objectApiName}/recycle-bin/{id}/restore": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Take a record out of the recycle bin
         * @description Needs permission to delete in the object and the right to delete the record: NOT_FOUND for a record the caller cannot read, FORBIDDEN for one they can read but may not delete. CONFLICT (409) for a record that is not in the recycle bin, or whose value in a unique field is in use now. NOT_FOUND for an object that does not exist, that a platform module owns (a user, a profile ...) or that the caller may not read: all three answer alike.
         */
        post: operations["restoreRecord"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/objects/{objectApiName}/views": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * The saved list views of an object that the caller sees
         * @description The shared views of the object, then the caller's own personal ones, each with the filter, the order and the columns it was saved with. A view is not a permission: running it is an ordinary query, decided by the caller's own permissions and record-level security. NOT_FOUND for an object that does not exist, that a platform module owns or that the caller may not read (all three answer alike).
         */
        get: operations["listListViews"];
        put?: never;
        /**
         * Save a list view
         * @description PERSONAL is for the caller only; SHARED is for everyone who may read the object and needs the ability to share list views (FORBIDDEN without it). The definition is checked by the first three stages of the query engine (shape, fields, operators and values) against what the caller may read: VALIDATION_ERROR lists the places that are wrong under definition. CONFLICT for a name already used among the views the caller sees in the object, and at the most views allowed. Nothing is run and no record is read. Audited by identifier and scope. NOT_FOUND for an object that does not exist, that a platform module owns or that the caller may not read (all three answer alike).
         */
        post: operations["createListView"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/objects/{objectApiName}/views/{viewId}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        /**
         * Change a saved list view
         * @description The request carries the version that was read: CONCURRENT_MODIFICATION (409) when the view has changed since, and nothing is changed. A personal view is changed only by the person who made it; a shared view needs the ability to share list views, whoever made it, and only its maker can make it personal again. NOT_FOUND for a view that does not exist or that is another person's personal view. NOT_FOUND for an object that does not exist, that a platform module owns or that the caller may not read (all three answer alike).
         */
        put: operations["updateListView"];
        post?: never;
        /**
         * Remove a saved list view
         * @description The version that was read must be given: CONCURRENT_MODIFICATION (409) when the view has changed since. The same rights as changing it. NOT_FOUND for a view that does not exist or that is another person's personal view. NOT_FOUND for an object that does not exist, that a platform module owns or that the caller may not read (all three answer alike).
         */
        delete: operations["deleteListView"];
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/organization/leave": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Leave the organization
         * @description The caller's membership ends: they are signed out of this organization, their licences go back to the pool, and an administrator can let them back in. Their other organizations are untouched. CONFLICT for the last member who can manage access. Any active member may leave.
         */
        post: operations["leaveOrganization"];
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
         * @description Ends every session and token bound to this organization host except the caller own. For members who may sign people out; audited.
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
    "/api/v1/platform/audit-events": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * The audit events of the platform (platform console)
         * @description Newest first, one page at a time, with filters for time, person, kind and target. For platform administrators. Shows platform events only, never an organization's own administration or data. Platform host only.
         */
        get: operations["listPlatformAuditEvents"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/platform/automation/health": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * The health of automation across organizations (platform console)
         * @description Counts of what waits, what is set aside, what is overdue and what is stuck, across all organizations, without any name or value. For platform administrators and support. Platform host only.
         */
        get: operations["getPlatformAutomationHealth"];
        put?: never;
        post?: never;
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
         * @description A SEAT (the right to occupy a seat; profiles belong to one) or an ADD_ON (sold on top, for example the licence of a standard access policy); SEAT when not given. CONFLICT when the key exists. For platform administrators and billing.
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
    "/api/v1/profiles": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * List the profiles of the organization
         * @description With the number of members who hold each. For members who manage access, invite members or see members. NOT_FOUND on the platform host, FORBIDDEN without one of those abilities.
         */
        get: operations["listProfiles"];
        put?: never;
        /**
         * Create a profile
         * @description A named base set of abilities that belongs to one licence type. VALIDATION_ERROR for a name in use, an unknown ability or an unknown licence type. For members who manage access; audited.
         */
        post: operations["createProfile"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/profiles/{profileId}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        /**
         * Change a profile
         * @description Takes effect for its members at once. CONFLICT for the administrator profile, for a licence type change while members hold the profile, or when the change would leave nobody who can manage access. NOT_FOUND for a profile of another organization. Audited.
         */
        put: operations["updateProfile"];
        post?: never;
        /**
         * Remove a profile
         * @description CONFLICT for a system profile, the default profile or a profile members still hold. Audited.
         */
        delete: operations["deleteProfile"];
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/profiles/{profileId}/data-access": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * The permission matrix of a profile
         * @description For members who manage access. The administrator profile answers everything. NOT_FOUND for a profile of another organization.
         */
        get: operations["getProfileDataAccess"];
        /**
         * Replace the permission matrix of a profile
         * @description What is not listed is not allowed. Counts for its members while they hold the licence of the profile. VALIDATION_ERROR for an unknown object, field or action; CONFLICT for the administrator profile. Audited.
         */
        put: operations["replaceProfileDataAccess"];
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/profiles/{profileId}/default": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Make a profile the default for new members
         * @description CONFLICT for the administrator profile. Audited.
         */
        post: operations["makeProfileDefault"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/roles": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * List the roles of the organization
         * @description The hierarchy as a flat list with each role's parent. For members who manage access, invite members or see members. NOT_FOUND on the platform host, FORBIDDEN without one of those abilities.
         */
        get: operations["listRoles"];
        put?: never;
        /**
         * Create a role
         * @description VALIDATION_ERROR for a name in use or a parent of another organization. For members who manage access; audited.
         */
        post: operations["createRole"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/roles/{roleId}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        /**
         * Change or move a role
         * @description CONFLICT when the move would put the role below itself or one of its sub-roles (a loop). NOT_FOUND for a role of another organization. Audited.
         */
        put: operations["updateRole"];
        post?: never;
        /**
         * Remove a role
         * @description CONFLICT while the role has sub-roles or members hold it. Audited.
         */
        delete: operations["deleteRole"];
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/sharing/objects": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * How the records of every object are shared by default
         * @description One entry per object of the organization: the organization-wide default and the hierarchy switch, and whether an administrator has set the object up (an object nobody set up is PUBLIC_READ_WRITE with the hierarchy on: every member who may read the object reads every record). NOT_FOUND on the platform host, FORBIDDEN without the ability to manage sharing.
         */
        get: operations["listObjectSharing"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/sharing/objects/{objectApiName}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * How the records of one object are shared by default
         * @description NOT_FOUND for an object that does not exist. NOT_FOUND on the platform host, FORBIDDEN without the ability to manage sharing.
         */
        get: operations["getObjectSharing"];
        /**
         * Set the organization-wide default of an object
         * @description PRIVATE: members reach only the records they own, the records of the people below them (when the hierarchy switch is on), the records a rule gives them and the records shared with them or with a group they are in. PUBLIC_READ: every member reads every record, changes need the same mechanisms. PUBLIC_READ_WRITE: every member reads and changes every record. Members who hold view-all or modify-all on the object are not held by the default. The change is immediate on every instance and no record is rewritten. The request carries the version that was read once the object has been set up (CONCURRENT_MODIFICATION otherwise). Audited. NOT_FOUND on the platform host, FORBIDDEN without the ability to manage sharing.
         */
        put: operations["setObjectSharing"];
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/sharing/rules": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * The sharing rules of the organization
         * @description All of them, or those of one object. NOT_FOUND on the platform host, FORBIDDEN without the ability to manage sharing.
         */
        get: operations["listSharingRules"];
        put?: never;
        /**
         * Create a sharing rule
         * @description OWNER: the records owned by the members of a role (or of a role and its subordinates) are given to a role, a role and its subordinates or a public group. GROUP: the same for the records owned by the members of a public group. CRITERIA: the records that meet a condition on their fields (the model of the record query, on fields flagged as indexed) are given. The level is READ or READ_WRITE; a rule never gives the right to delete. VALIDATION_ERROR lists every problem with its place. CONFLICT at the most rules per object. Audited by names, never by the condition. NOT_FOUND on the platform host, FORBIDDEN without the ability to manage sharing.
         */
        post: operations["createSharingRule"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/sharing/rules/{ruleId}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * One sharing rule
         * @description NOT_FOUND for a rule of another organization. NOT_FOUND on the platform host, FORBIDDEN without the ability to manage sharing.
         */
        get: operations["getSharingRule"];
        /**
         * Change a sharing rule
         * @description The object and the kind of a rule do not change. The request carries the version that was read (CONCURRENT_MODIFICATION otherwise). Takes effect at the next question on every instance. Audited. NOT_FOUND on the platform host, FORBIDDEN without the ability to manage sharing.
         */
        put: operations["updateSharingRule"];
        post?: never;
        /**
         * Remove a sharing rule
         * @description What the rule gave is taken back at the next question. Audited. NOT_FOUND on the platform host, FORBIDDEN without the ability to manage sharing.
         */
        delete: operations["deleteSharingRule"];
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/sheets/{ownerObject}/{ownerId}/versions": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * The versions of a sheet the caller may see
         * @description Newest first, without their grids (a list never reads a grid). A caller who may only read the record sees the versions that are in use or were (ACTIVE, SUPERSEDED, RETIRED); a caller who may change it sees every version, drafts included. canChange says whether the screen may offer to start or save a draft; every request that follows is decided again. NOT_FOUND for an object that cannot own a sheet, for a record that does not exist and for a record the caller may not read (all three answer alike). A sheet has no permission of its own: reading it needs the right to read the record and changing it the right to update the record.
         */
        get: operations["listSheetVersions"];
        put?: never;
        /**
         * Start a new draft version of a sheet
         * @description The first version is blank and names the shape of the sheet (dimension, and optionally rowsMode); every later one is a copy of the version named by basedOn, or of the active version (the latest when none is active), and keeps the shape: another shape is VALIDATION_ERROR. Versions are numbered automatically and a number is never reused. There is at most one draft at a time: CONFLICT names the number of the existing one, and two requests that arrive together get one draft and one answer. CONFLICT also at the most versions a sheet may have. FORBIDDEN for a caller who may read the record but not update it. Audited by the number of the version and the shape, never by content. NOT_FOUND for an object that cannot own a sheet, for a record that does not exist and for a record the caller may not read (all three answer alike). A sheet has no permission of its own: reading it needs the right to read the record and changing it the right to update the record.
         */
        post: operations["createSheetVersion"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/sheets/{ownerObject}/{ownerId}/versions/{versionNo}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * One version of a sheet with its grid
         * @description The only call that reads a grid. NOT_FOUND also for a version that does not exist and for a version the caller may not see (a caller who may only read the record sees ACTIVE, SUPERSEDED and RETIRED versions; a draft or a version in review answers like a version that does not exist). NOT_FOUND for an object that cannot own a sheet, for a record that does not exist and for a record the caller may not read (all three answer alike). A sheet has no permission of its own: reading it needs the right to read the record and changing it the right to update the record.
         */
        get: operations["getSheetVersion"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/sheets/{ownerObject}/{ownerId}/versions/{versionNo}/grid": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        /**
         * Save the grid of a draft version
         * @description The body is the grid itself (the schema SheetGrid of this document), as JSON, and nothing else; the row version that was read goes in the version parameter. The size is checked before anything is parsed, from the length the client announces and again on the bytes read (so a body without a length is bounded too): PAYLOAD_TOO_LARGE above the limit of the organization. Then the grid is checked in a fixed order and the first stage with a problem answers alone, with every place of that stage under grid: VALIDATION_ERROR. A grid is stored in a canonical form, so the same grid always has the same hash. CONCURRENT_MODIFICATION (409) when the draft has changed since it was read, and nothing changes; CONFLICT for a version that is no longer a draft. FORBIDDEN for a caller who may read the record but not update it. Audited by the number, the size, the hash and counts, never by content. NOT_FOUND for an object that cannot own a sheet, for a record that does not exist and for a record the caller may not read (all three answer alike). A sheet has no permission of its own: reading it needs the right to read the record and changing it the right to update the record.
         */
        put: operations["saveSheetGrid"];
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
    "/api/v1/workflows/executions": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * The executions of workflows
         * @description Newest first, one page at a time, with filters for the workflow, the object, the status, the trigger, the record and the time the execution was queued. dead=true lists the dead letters (the same as status=DEAD_LETTERED). For members who may view workflows. NOT_FOUND on the platform host, FORBIDDEN without the ability, VALIDATION_ERROR for a filter that is malformed.
         */
        get: operations["listWorkflowExecutions"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/workflows/executions/dead-letters/summary": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * How many dead letters wait for a person
         * @description Counted by workflow and stable reason, the largest group first. A dead letter that has been replayed is not counted. For members who may view workflows.
         */
        get: operations["getWorkflowDeadLetterSummary"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/workflows/executions/replay": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Run several dead letters again
         * @description At most 200. One result per execution, each in a savepoint of its own: one that cannot be replayed does not stop the others, and there is no all-or-nothing form. Needs workflows.replay.
         */
        post: operations["replayWorkflowExecutions"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/workflows/executions/{executionId}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * One execution with its steps
         * @description Each step shows its status, how many attempts ended, when the next one is due and the stable reason and failure type of the last failure; never a value. NOT_FOUND for an execution the organization does not have.
         */
        get: operations["getWorkflowExecution"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/workflows/executions/{executionId}/cancel": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Cancel an execution that has not started
         * @description For an execution that is queued, including one that waits for its next attempt. CONFLICT when it is running or finished. Needs workflows.manage.
         */
        post: operations["cancelWorkflowExecution"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/workflows/executions/{executionId}/replay": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Run a dead letter again
         * @description Makes a new execution that resumes at the step that failed, or a later one named in fromStep (a step that completed is never run again), against the record as it is now. The condition is not judged again. Replaying twice, or at the same moment, makes one new execution: the answer is then ALREADY_REPLAYED with the one that exists. Only a dead letter is replayed (NOT_REPLAYABLE). A switched-off or removed workflow, a missing plan feature and a record that is gone end the replay as for any execution. Needs workflows.replay. NOT_FOUND for an execution the organization does not have.
         */
        post: operations["replayWorkflowExecution"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/workflows/parked-events": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Events the platform gave up on that workflows depend on
         * @description The record events and releases whose handlers kept failing and were set aside (at most 200, newest first). Never the payload. For members who may view workflows.
         */
        get: operations["listWorkflowParkedEvents"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/workflows/parked-events/{eventId}/redrive": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Put a parked event back
         * @description The event is delivered again as if it were new. Handlers that already handled it skip it, so nothing is done twice; one that did not handles it now (a release whose schedules were never made makes them). Redriving twice changes nothing the second time (NOT_FOUND). Needs workflows.replay.
         */
        post: operations["redriveWorkflowParkedEvent"];
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
        AbilityInfo: {
            description: string;
            key: string;
            name: string;
        };
        AcceptInvitationRequest: {
            displayName?: string;
            password: string;
            token: string;
        };
        AccessPolicyRef: {
            /** Format: uuid */
            id: string;
            licenceType?: string;
            name: string;
        };
        AccessPolicyView: {
            abilities: string[];
            description: string;
            /** Format: int32 */
            groups?: number;
            /** Format: uuid */
            id: string;
            managed: boolean;
            /** Format: int32 */
            members?: number;
            name: string;
            requiredLicenceType?: string;
        };
        AddCatalogueItemRequest: {
            key: string;
            name: string;
        };
        AddFieldValueRequest: {
            controllingValues?: string[];
            label?: string;
            value: string;
        };
        AddGroupMemberRequest: {
            /** Format: uuid */
            groupId?: string;
            /** Format: uuid */
            membershipId?: string;
        };
        AddLicenceTypeRequest: {
            key: string;
            kind?: string;
            name: string;
        };
        AddedFieldValueView: {
            added: boolean;
            controllingValues: string[];
            label: string;
            value: string;
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
        /** @description The permanent name of a layout, a record page, an application, a tab or a custom page. It ends with __c. */
        ApiNameShape: string;
        ApiPageResponseApprovalInstanceSummaryView: {
            data: components["schemas"]["ApprovalInstanceSummaryView"][];
            pagination: components["schemas"]["Pagination"];
        };
        ApiPageResponseApprovalTaskView: {
            data: components["schemas"]["ApprovalTaskView"][];
            pagination: components["schemas"]["Pagination"];
        };
        ApiPageResponseAuditEventView: {
            data: components["schemas"]["AuditEventView"][];
            pagination: components["schemas"]["Pagination"];
        };
        ApiPageResponseNotificationView: {
            data: components["schemas"]["NotificationView"][];
            pagination: components["schemas"]["Pagination"];
        };
        ApiPageResponsePlatformOrganizationSummary: {
            data: components["schemas"]["PlatformOrganizationSummary"][];
            pagination: components["schemas"]["Pagination"];
        };
        ApiPageResponseQueriedRecordView: {
            data: components["schemas"]["QueriedRecordView"][];
            pagination: components["schemas"]["Pagination"];
        };
        ApiPageResponseRecordView: {
            data: components["schemas"]["RecordView"][];
            pagination: components["schemas"]["Pagination"];
        };
        ApiPageResponseWorkflowExecutionView: {
            data: components["schemas"]["WorkflowExecutionView"][];
            pagination: components["schemas"]["Pagination"];
        };
        ApiResponseAccessPolicyView: {
            data: components["schemas"]["AccessPolicyView"];
        };
        ApiResponseAddedFieldValueView: {
            data: components["schemas"]["AddedFieldValueView"];
        };
        ApiResponseApplicationView: {
            data: components["schemas"]["ApplicationView"];
        };
        ApiResponseApprovalCatalogueView: {
            data: components["schemas"]["ApprovalCatalogueView"];
        };
        ApiResponseApprovalChoicesView: {
            data: components["schemas"]["ApprovalChoicesView"];
        };
        ApiResponseApprovalDelegationView: {
            data: components["schemas"]["ApprovalDelegationView"];
        };
        ApiResponseApprovalInboxSummaryView: {
            data: components["schemas"]["ApprovalInboxSummaryView"];
        };
        ApiResponseApprovalInstanceView: {
            data: components["schemas"]["ApprovalInstanceView"];
        };
        ApiResponseApprovalProcessCheckView: {
            data: components["schemas"]["ApprovalProcessCheckView"];
        };
        ApiResponseApprovalProcessView: {
            data: components["schemas"]["ApprovalProcessView"];
        };
        ApiResponseApprovalResolutionView: {
            data: components["schemas"]["ApprovalResolutionView"];
        };
        ApiResponseAutomationHealthView: {
            data: components["schemas"]["AutomationHealthView"];
        };
        ApiResponseBulkRecordsResponse: {
            data: components["schemas"]["BulkRecordsResponse"];
        };
        ApiResponseCatalogueItem: {
            data: components["schemas"]["CatalogueItem"];
        };
        ApiResponseChangeSetReportView: {
            data: components["schemas"]["ChangeSetReportView"];
        };
        ApiResponseChangeSetView: {
            data: components["schemas"]["ChangeSetView"];
        };
        ApiResponseCurrentUser: {
            data: components["schemas"]["CurrentUser"];
        };
        ApiResponseCustomPageAssetView: {
            data: components["schemas"]["CustomPageAssetView"];
        };
        ApiResponseCustomPageScriptView: {
            data: components["schemas"]["CustomPageScriptView"];
        };
        ApiResponseCustomPageView: {
            data: components["schemas"]["CustomPageView"];
        };
        ApiResponseDataAccessView: {
            data: components["schemas"]["DataAccessView"];
        };
        ApiResponseDataCatalogue: {
            data: components["schemas"]["DataCatalogue"];
        };
        ApiResponseEmbeddedTokenView: {
            data: components["schemas"]["EmbeddedTokenView"];
        };
        ApiResponseExpressionCheckView: {
            data: components["schemas"]["ExpressionCheckView"];
        };
        ApiResponseExpressionTestView: {
            data: components["schemas"]["ExpressionTestView"];
        };
        ApiResponseFieldView: {
            data: components["schemas"]["FieldView"];
        };
        ApiResponseFormulaRecalculationView: {
            data: components["schemas"]["FormulaRecalculationView"];
        };
        ApiResponseGroupView: {
            data: components["schemas"]["GroupView"];
        };
        ApiResponseImportJobView: {
            data: components["schemas"]["ImportJobView"];
        };
        ApiResponseInvitationAccepted: {
            data: components["schemas"]["InvitationAccepted"];
        };
        ApiResponseInvitationPreview: {
            data: components["schemas"]["InvitationPreview"];
        };
        ApiResponseLayoutAssignmentView: {
            data: components["schemas"]["LayoutAssignmentView"];
        };
        ApiResponseLayoutResolutionView: {
            data: components["schemas"]["LayoutResolutionView"];
        };
        ApiResponseLayoutView: {
            data: components["schemas"]["LayoutView"];
        };
        ApiResponseLicenceTypeItem: {
            data: components["schemas"]["LicenceTypeItem"];
        };
        ApiResponseListAbilityInfo: {
            data: components["schemas"]["AbilityInfo"][];
        };
        ApiResponseListAccessPolicyView: {
            data: components["schemas"]["AccessPolicyView"][];
        };
        ApiResponseListApplicationView: {
            data: components["schemas"]["ApplicationView"][];
        };
        ApiResponseListApprovalDelegationView: {
            data: components["schemas"]["ApprovalDelegationView"][];
        };
        ApiResponseListApprovalPersonView: {
            data: components["schemas"]["ApprovalPersonView"][];
        };
        ApiResponseListApprovalProcessView: {
            data: components["schemas"]["ApprovalProcessView"][];
        };
        ApiResponseListAuthorshipView: {
            data: components["schemas"]["AuthorshipView"][];
        };
        ApiResponseListCatalogueItem: {
            data: components["schemas"]["CatalogueItem"][];
        };
        ApiResponseListChangeSetView: {
            data: components["schemas"]["ChangeSetView"][];
        };
        ApiResponseListCustomPageView: {
            data: components["schemas"]["CustomPageView"][];
        };
        ApiResponseListFieldTypeView: {
            data: components["schemas"]["FieldTypeView"][];
        };
        ApiResponseListFormulaReportItemView: {
            data: components["schemas"]["FormulaReportItemView"][];
        };
        ApiResponseListFunctionView: {
            data: components["schemas"]["FunctionView"][];
        };
        ApiResponseListGroupView: {
            data: components["schemas"]["GroupView"][];
        };
        ApiResponseListImportJobView: {
            data: components["schemas"]["ImportJobView"][];
        };
        ApiResponseListInvitationView: {
            data: components["schemas"]["InvitationView"][];
        };
        ApiResponseListLayoutAssignmentView: {
            data: components["schemas"]["LayoutAssignmentView"][];
        };
        ApiResponseListLayoutView: {
            data: components["schemas"]["LayoutView"][];
        };
        ApiResponseListLicencePoolView: {
            data: components["schemas"]["LicencePoolView"][];
        };
        ApiResponseListLicenceTypeItem: {
            data: components["schemas"]["LicenceTypeItem"][];
        };
        ApiResponseListListViewView: {
            data: components["schemas"]["ListViewView"][];
        };
        ApiResponseListLookupOptionView: {
            data: components["schemas"]["LookupOptionView"][];
        };
        ApiResponseListMemberView: {
            data: components["schemas"]["MemberView"][];
        };
        ApiResponseListMyApplicationView: {
            data: components["schemas"]["MyApplicationView"][];
        };
        ApiResponseListObjectSharingView: {
            data: components["schemas"]["ObjectSharingView"][];
        };
        ApiResponseListObjectSummaryView: {
            data: components["schemas"]["ObjectSummaryView"][];
        };
        ApiResponseListOrganizationSummary: {
            data: components["schemas"]["OrganizationSummary"][];
        };
        ApiResponseListParkedEventView: {
            data: components["schemas"]["ParkedEventView"][];
        };
        ApiResponseListPlanInfo: {
            data: components["schemas"]["PlanInfo"][];
        };
        ApiResponseListPlatformPersonView: {
            data: components["schemas"]["PlatformPersonView"][];
        };
        ApiResponseListProfileView: {
            data: components["schemas"]["ProfileView"][];
        };
        ApiResponseListRecordFileView: {
            data: components["schemas"]["RecordFileView"][];
        };
        ApiResponseListRecordPageAssignmentView: {
            data: components["schemas"]["RecordPageAssignmentView"][];
        };
        ApiResponseListRecordPageView: {
            data: components["schemas"]["RecordPageView"][];
        };
        ApiResponseListRecordShareView: {
            data: components["schemas"]["RecordShareView"][];
        };
        ApiResponseListRecordTypeAssignmentView: {
            data: components["schemas"]["RecordTypeAssignmentView"][];
        };
        ApiResponseListRecordTypeView: {
            data: components["schemas"]["RecordTypeView"][];
        };
        ApiResponseListReleaseView: {
            data: components["schemas"]["ReleaseView"][];
        };
        ApiResponseListRoleView: {
            data: components["schemas"]["RoleView"][];
        };
        ApiResponseListSessionInfo: {
            data: components["schemas"]["SessionInfo"][];
        };
        ApiResponseListShareTargetView: {
            data: components["schemas"]["ShareTargetView"][];
        };
        ApiResponseListSharingRuleView: {
            data: components["schemas"]["SharingRuleView"][];
        };
        ApiResponseListSupportAccessView: {
            data: components["schemas"]["SupportAccessView"][];
        };
        ApiResponseListTabView: {
            data: components["schemas"]["TabView"][];
        };
        ApiResponseListValidationRuleView: {
            data: components["schemas"]["ValidationRuleView"][];
        };
        ApiResponseListViewView: {
            data: components["schemas"]["ListViewView"];
        };
        ApiResponseListWorkflowView: {
            data: components["schemas"]["WorkflowView"][];
        };
        ApiResponseLookupLabelsView: {
            data: components["schemas"]["LookupLabelsView"];
        };
        ApiResponseMemberAccessView: {
            data: components["schemas"]["MemberAccessView"];
        };
        ApiResponseNotificationPreferencesView: {
            data: components["schemas"]["NotificationPreferencesView"];
        };
        ApiResponseNotificationSummaryView: {
            data: components["schemas"]["NotificationSummaryView"];
        };
        ApiResponseNotificationTemplateCheckView: {
            data: components["schemas"]["NotificationTemplateCheckView"];
        };
        ApiResponseNotificationTemplateView: {
            data: components["schemas"]["NotificationTemplateView"];
        };
        ApiResponseNotificationTemplatesView: {
            data: components["schemas"]["NotificationTemplatesView"];
        };
        ApiResponseObjectFormView: {
            data: components["schemas"]["ObjectFormView"];
        };
        ApiResponseObjectIndexStatusView: {
            data: components["schemas"]["ObjectIndexStatusView"];
        };
        ApiResponseObjectRelationshipsView: {
            data: components["schemas"]["ObjectRelationshipsView"];
        };
        ApiResponseObjectSharingView: {
            data: components["schemas"]["ObjectSharingView"];
        };
        ApiResponseObjectView: {
            data: components["schemas"]["ObjectView"];
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
        ApiResponseProfileView: {
            data: components["schemas"]["ProfileView"];
        };
        ApiResponseRecordApprovalView: {
            data: components["schemas"]["RecordApprovalView"];
        };
        ApiResponseRecordCheckView: {
            data: components["schemas"]["RecordCheckView"];
        };
        ApiResponseRecordFileView: {
            data: components["schemas"]["RecordFileView"];
        };
        ApiResponseRecordPageAssignmentView: {
            data: components["schemas"]["RecordPageAssignmentView"];
        };
        ApiResponseRecordPageDescriptorView: {
            data: components["schemas"]["RecordPageDescriptorView"];
        };
        ApiResponseRecordPageResolutionView: {
            data: components["schemas"]["RecordPageResolutionView"];
        };
        ApiResponseRecordPageView: {
            data: components["schemas"]["RecordPageView"];
        };
        ApiResponseRecordShareView: {
            data: components["schemas"]["RecordShareView"];
        };
        ApiResponseRecordTypeAssignmentView: {
            data: components["schemas"]["RecordTypeAssignmentView"];
        };
        ApiResponseRecordTypeView: {
            data: components["schemas"]["RecordTypeView"];
        };
        ApiResponseRecordView: {
            data: components["schemas"]["RecordView"];
        };
        ApiResponseReleaseView: {
            data: components["schemas"]["ReleaseView"];
        };
        ApiResponseRequestAccepted: {
            data: components["schemas"]["RequestAccepted"];
        };
        ApiResponseRoleView: {
            data: components["schemas"]["RoleView"];
        };
        ApiResponseSharingRuleView: {
            data: components["schemas"]["SharingRuleView"];
        };
        ApiResponseSheetVersionSummaryView: {
            data: components["schemas"]["SheetVersionSummaryView"];
        };
        ApiResponseSheetVersionView: {
            data: components["schemas"]["SheetVersionView"];
        };
        ApiResponseSheetVersionsView: {
            data: components["schemas"]["SheetVersionsView"];
        };
        ApiResponseSwitchTarget: {
            data: components["schemas"]["SwitchTarget"];
        };
        ApiResponseTabView: {
            data: components["schemas"]["TabView"];
        };
        ApiResponseTenantSummary: {
            data: components["schemas"]["TenantSummary"];
        };
        ApiResponseValidationRuleView: {
            data: components["schemas"]["ValidationRuleView"];
        };
        ApiResponseWorkflowBulkReplayView: {
            data: components["schemas"]["WorkflowBulkReplayView"];
        };
        ApiResponseWorkflowCatalogueView: {
            data: components["schemas"]["WorkflowCatalogueView"];
        };
        ApiResponseWorkflowConditionCheckView: {
            data: components["schemas"]["WorkflowConditionCheckView"];
        };
        ApiResponseWorkflowDeadLetterSummaryView: {
            data: components["schemas"]["WorkflowDeadLetterSummaryView"];
        };
        ApiResponseWorkflowExecutionView: {
            data: components["schemas"]["WorkflowExecutionView"];
        };
        ApiResponseWorkflowReplayResultView: {
            data: components["schemas"]["WorkflowReplayResultView"];
        };
        ApiResponseWorkflowView: {
            data: components["schemas"]["WorkflowView"];
        };
        /** @description What an application contains beyond its name: an icon, the profiles that see it, and its navigation in order. */
        ApplicationDefinition: {
            iconKey?: string;
            items: components["schemas"]["NavigationItem"][];
            /** @description The profiles whose members see the application. Empty or left out: every member. */
            profiles?: string[];
        };
        ApplicationView: {
            apiName: string;
            definition: components["schemas"]["ApplicationDefinition"];
            description: string;
            label: string;
            /** Format: int64 */
            version: number;
        };
        ApprovalActionsView: {
            canApprove: boolean;
            canAssign: boolean;
            canCancel: boolean;
            canReapply: boolean;
            canReassign: boolean;
            canRecall: boolean;
            canReject: boolean;
            canSubmit: boolean;
        };
        ApprovalApproverSpec: {
            kind: string;
            value?: string;
        };
        ApprovalAssignmentView: {
            assignee: components["schemas"]["ApprovalPersonView"];
            /** Format: date-time */
            decidedAt?: string;
            forPerson?: components["schemas"]["ApprovalPersonView"];
            /** Format: uuid */
            id: string;
            status: string;
            via: string;
        };
        ApprovalCatalogueView: {
            approverKinds: components["schemas"]["ApproverKind"][];
            completions: components["schemas"]["Choice"][];
            limits: components["schemas"]["Limits"];
            outcomes: components["schemas"]["Choice"][];
            states: components["schemas"]["Choice"][];
            stepStates: components["schemas"]["Choice"][];
        };
        ApprovalChoiceView: {
            apiName: string;
            label: string;
        };
        ApprovalChoicesView: {
            groups: components["schemas"]["Named"][];
            profiles: components["schemas"]["Named"][];
            roles: components["schemas"]["Named"][];
        };
        ApprovalDecisionRequest: {
            comment?: string;
            /** Format: int64 */
            version: number;
        };
        ApprovalDelegationView: {
            delegate: components["schemas"]["ApprovalPersonView"];
            delegator: components["schemas"]["ApprovalPersonView"];
            /** Format: date */
            endsOn: string;
            /** Format: uuid */
            id: string;
            inForce: boolean;
            objectApiName?: string;
            processApiName?: string;
            /** Format: date */
            startsOn: string;
            timeZone: string;
        };
        ApprovalFieldSpec: {
            field: string;
            value: string;
        };
        ApprovalHistoryView: {
            actor?: components["schemas"]["ApprovalPersonView"];
            /** Format: date-time */
            at: string;
            comment?: string;
            /** Format: uuid */
            id: string;
            kind: string;
            onBehalfOf?: components["schemas"]["ApprovalPersonView"];
            reason?: string;
            /** Format: int32 */
            stepPosition?: number;
            target?: components["schemas"]["ApprovalPersonView"];
        };
        ApprovalInboxSummaryView: {
            available: boolean;
            /** Format: int32 */
            openTasks: number;
        };
        ApprovalInstanceSummaryView: {
            attention?: string;
            /** Format: int32 */
            currentStep?: number;
            currentStepLabel?: string;
            /** Format: date-time */
            finishedAt?: string;
            /** Format: uuid */
            id: string;
            objectApiName: string;
            outcomeFailure?: string;
            processApiName: string;
            processLabel: string;
            /** Format: int32 */
            processVersion: number;
            /** Format: uuid */
            recordId: string;
            /** Format: date-time */
            startedAt: string;
            status: string;
            /** Format: int32 */
            stepCount: number;
            submittedBy?: components["schemas"]["ApprovalPersonView"];
            /** Format: int64 */
            version: number;
        };
        ApprovalInstanceView: {
            actions: components["schemas"]["ApprovalActionsView"];
            history: components["schemas"]["ApprovalHistoryView"][];
            historyVisible: boolean;
            steps: components["schemas"]["ApprovalStepView"][];
            summary: components["schemas"]["ApprovalInstanceSummaryView"];
        };
        ApprovalOutcomesSpec: {
            approved?: components["schemas"]["ApprovalFieldSpec"][];
            recalled?: components["schemas"]["ApprovalFieldSpec"][];
            rejected?: components["schemas"]["ApprovalFieldSpec"][];
        };
        ApprovalPersonView: {
            name?: string;
            /** Format: uuid */
            userId: string;
        };
        ApprovalProcessActivationRequest: {
            active: boolean;
            /** Format: int64 */
            version: number;
        };
        ApprovalProcessCheckView: {
            outcome: string;
            problem?: components["schemas"]["ExpressionProblemView"];
            problems: {
                [key: string]: string[];
            };
        };
        ApprovalProcessView: {
            active: boolean;
            allowSelfApproval: boolean;
            apiName: string;
            /** Format: int32 */
            definitionVersion: number;
            description: string;
            entryCriteria?: string;
            /** Format: int32 */
            escalateAfterHours?: number;
            fallback?: components["schemas"]["ApprovalApproverSpec"];
            label: string;
            objectApiName: string;
            outcomes: components["schemas"]["ApprovalOutcomesSpec"];
            /** Format: int32 */
            remindAfterHours?: number;
            steps: components["schemas"]["ApprovalStepSpec"][];
            /** Format: int64 */
            version: number;
        };
        ApprovalReapplyRequest: {
            /** Format: int64 */
            recordVersion: number;
            /** Format: int64 */
            version: number;
        };
        ApprovalReassignRequest: {
            comment?: string;
            /** Format: uuid */
            toUserId: string;
            /** Format: int64 */
            version: number;
        };
        ApprovalResolutionView: {
            steps: components["schemas"]["ResolvedStep"][];
        };
        ApprovalStepSpec: {
            approvers: components["schemas"]["ApprovalApproverSpec"][];
            completion: string;
            label: string;
            name: string;
            skipWhenNobody?: boolean;
        };
        ApprovalStepView: {
            /** Format: date-time */
            activatedAt?: string;
            assignments: components["schemas"]["ApprovalAssignmentView"][];
            completion: string;
            /** Format: date-time */
            finishedAt?: string;
            label: string;
            name: string;
            /** Format: int32 */
            position: number;
            status: string;
        };
        ApprovalTaskView: {
            actions: components["schemas"]["ApprovalActionsView"];
            /** Format: uuid */
            assignmentId: string;
            /** Format: date-time */
            decidedAt?: string;
            forPerson?: components["schemas"]["ApprovalPersonView"];
            instance: components["schemas"]["ApprovalInstanceSummaryView"];
            objectLabel?: string;
            recordReadable: boolean;
            recordTitle?: string;
            /** Format: date-time */
            since: string;
            status: string;
            stepLabel: string;
            via: string;
        };
        ApproveSupportAccessRequest: {
            /** Format: int32 */
            minutes?: number;
        };
        ApproverKind: {
            kind: string;
            summary: string;
            valueRefersTo: string;
        };
        AssignPolicyRequest: {
            /** Format: uuid */
            policyId: string;
        };
        AssignProfileRequest: {
            /** Format: uuid */
            profileId: string;
        };
        AssignRoleRequest: {
            /** Format: uuid */
            roleId?: string;
        };
        AuditEventView: {
            /** Format: uuid */
            actorUserId?: string;
            attributes: {
                [key: string]: string;
            };
            /** Format: uuid */
            id: string;
            newValue?: string;
            objectKey?: string;
            /** Format: date-time */
            occurredAt: string;
            oldValue?: string;
            outcome: string;
            reason?: string;
            recordId?: string;
            source: string;
            type: string;
        };
        AuthorshipView: {
            apiName: string;
            /** Format: date-time */
            createdAt: string;
            createdBy?: string;
            kind: string;
            /** Format: date-time */
            updatedAt: string;
            updatedBy?: string;
        };
        AutomationHealthItemView: {
            /** Format: int64 */
            count: number;
            href?: string;
            key: string;
            label: string;
            needsAttention: boolean;
        };
        AutomationHealthSectionView: {
            items: components["schemas"]["AutomationHealthItemView"][];
            key: string;
            title: string;
        };
        AutomationHealthView: {
            /** Format: date-time */
            generatedAt: string;
            sections: components["schemas"]["AutomationHealthSectionView"][];
        };
        BulkRecordItem: {
            clear?: string[];
            fields?: {
                [key: string]: unknown;
            };
            /** Format: uuid */
            id?: string;
            recordType?: string;
            /** Format: int64 */
            version?: number;
        };
        BulkRecordResult: {
            code?: string;
            fields?: {
                [key: string]: string[];
            };
            /** Format: uuid */
            id?: string;
            message?: string;
            /** Format: int32 */
            position?: number;
            status: string;
            /** Format: int64 */
            version?: number;
        };
        BulkRecordsRequest: {
            allOrNone?: boolean;
            operation: string;
            records: components["schemas"]["BulkRecordItem"][];
        };
        BulkRecordsResponse: {
            allOrNone?: boolean;
            committed?: boolean;
            /** Format: int32 */
            failed?: number;
            operation: string;
            results: components["schemas"]["BulkRecordResult"][];
            /** Format: int32 */
            succeeded?: number;
        };
        CatalogueActionType: {
            assignedObject?: string;
            assignments: string;
            phase: string;
            settings: components["schemas"]["CatalogueSetting"][];
            summary: string;
            type: string;
        };
        CatalogueItem: {
            key: string;
            name: string;
            removalNote?: string;
        };
        CatalogueLimits: {
            /** Format: int32 */
            maxActionsPerWorkflow: number;
            /** Format: int32 */
            maxAssignmentsPerAction: number;
            /** Format: int32 */
            maxAttempts: number;
            /** Format: int32 */
            maxChainDepth: number;
            /** Format: int32 */
            maxLiteralLength: number;
        };
        CatalogueSetting: {
            allowed: string[];
            help: string;
            key: string;
            refersTo: string;
            required: boolean;
        };
        CatalogueTriggerKind: {
            kind: string;
            phase: string;
            properties: string[];
            scheduled: boolean;
            summary: string;
        };
        ChangeOwnerRequest: {
            /** Format: uuid */
            ownerId: string;
            /** Format: int64 */
            version: number;
        };
        ChangePasswordRequest: {
            currentPassword: string;
            newPassword: string;
        };
        ChangeRequest: {
            createApplication?: components["schemas"]["CreateApplicationRequest"];
            createApprovalProcess?: components["schemas"]["CreateApprovalProcessRequest"];
            createCustomPage?: components["schemas"]["CreateCustomPageRequest"];
            createField?: components["schemas"]["CreateFieldRequest"];
            createLayout?: components["schemas"]["CreateLayoutRequest"];
            createLayoutAssignment?: components["schemas"]["CreateLayoutAssignmentRequest"];
            createNotificationTemplate?: components["schemas"]["CreateNotificationTemplateRequest"];
            createObject?: components["schemas"]["CreateObjectRequest"];
            createRecordPage?: components["schemas"]["CreateRecordPageRequest"];
            createRecordPageAssignment?: components["schemas"]["CreateRecordPageAssignmentRequest"];
            createRecordType?: components["schemas"]["CreateRecordTypeRequest"];
            createRecordTypeAssignment?: components["schemas"]["CreateRecordTypeAssignmentRequest"];
            createTab?: components["schemas"]["CreateTabRequest"];
            createValidationRule?: components["schemas"]["CreateValidationRuleRequest"];
            createWorkflow?: components["schemas"]["CreateWorkflowRequest"];
            itemApiName?: string;
            kind: string;
            objectApiName?: string;
            updateApplication?: components["schemas"]["UpdateApplicationRequest"];
            updateApprovalProcess?: components["schemas"]["UpdateApprovalProcessRequest"];
            updateCustomPage?: components["schemas"]["UpdateCustomPageRequest"];
            updateField?: components["schemas"]["UpdateFieldRequest"];
            updateLayout?: components["schemas"]["UpdateLayoutRequest"];
            updateLayoutAssignment?: components["schemas"]["UpdateLayoutAssignmentRequest"];
            updateNotificationTemplate?: components["schemas"]["UpdateNotificationTemplateRequest"];
            updateObject?: components["schemas"]["UpdateObjectRequest"];
            updateRecordPage?: components["schemas"]["UpdateRecordPageRequest"];
            updateRecordPageAssignment?: components["schemas"]["UpdateRecordPageAssignmentRequest"];
            updateRecordType?: components["schemas"]["UpdateRecordTypeRequest"];
            updateRecordTypeAssignment?: components["schemas"]["UpdateRecordTypeAssignmentRequest"];
            updateTab?: components["schemas"]["UpdateTabRequest"];
            updateValidationRule?: components["schemas"]["UpdateValidationRuleRequest"];
            updateWorkflow?: components["schemas"]["UpdateWorkflowRequest"];
        };
        ChangeSetReportView: {
            items: components["schemas"]["ReleaseItemView"][];
            notes: string[];
            objects: components["schemas"]["ObjectView"][];
            problems: components["schemas"]["ProblemView"][];
            valid: boolean;
        };
        ChangeSetView: {
            /** Format: int32 */
            changeCount: number;
            changes: components["schemas"]["ChangeView"][];
            /** Format: date-time */
            createdAt: string;
            description: string;
            id: string;
            name: string;
            /** Format: int64 */
            releaseNumber?: number;
            status: string;
            /** Format: int64 */
            version: number;
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
        ChangeView: {
            id: string;
            itemApiName?: string;
            kind: string;
            objectApiName: string;
            /** Format: int32 */
            position: number;
        };
        CheckApprovalProcessRequest: {
            entryCriteria?: string;
            outcomes?: components["schemas"]["ApprovalOutcomesSpec"];
            steps?: components["schemas"]["ApprovalStepSpec"][];
            values?: {
                [key: string]: string;
            };
        };
        CheckExpressionRequest: {
            expression: string;
            objectApiName: string;
            purpose: string;
            resultType?: string;
        };
        CheckNotificationTemplateRequest: {
            body?: string;
            kind: string;
            title?: string;
        };
        CheckWorkflowConditionRequest: {
            actions?: components["schemas"]["WorkflowActionSpec"][];
            condition?: string;
            values?: {
                [key: string]: string;
            };
        };
        Choice: {
            code: string;
            summary: string;
        };
        CompleteSignUpRequest: {
            displayName: string;
            password: string;
            token: string;
        };
        CompleteSwitchRequest: {
            token: string;
        };
        CreateApplicationRequest: {
            definition: components["schemas"]["ApplicationDefinition"];
            description?: string;
            label: string;
            name: string;
        };
        CreateApprovalDelegationRequest: {
            /** Format: uuid */
            delegateId: string;
            /** Format: uuid */
            delegatorId?: string;
            /** Format: date */
            endsOn: string;
            objectApiName?: string;
            processApiName?: string;
            /** Format: date */
            startsOn: string;
            timeZone: string;
        };
        CreateApprovalProcessRequest: {
            active?: boolean;
            allowSelfApproval?: boolean;
            description?: string;
            entryCriteria?: string;
            /** Format: int32 */
            escalateAfterHours?: number;
            fallback?: components["schemas"]["ApprovalApproverSpec"];
            label: string;
            name: string;
            outcomes?: components["schemas"]["ApprovalOutcomesSpec"];
            /** Format: int32 */
            remindAfterHours?: number;
            steps: components["schemas"]["ApprovalStepSpec"][];
        };
        CreateChangeSetRequest: {
            description?: string;
            name: string;
        };
        CreateCustomPageRequest: {
            definition: components["schemas"]["CustomPageDefinition"];
            description?: string;
            label: string;
            name: string;
        };
        CreateFieldRequest: {
            defaultValue?: string;
            description?: string;
            externalId?: boolean;
            indexed?: boolean;
            label: string;
            name: string;
            required?: boolean;
            searchable?: boolean;
            settings?: components["schemas"]["FieldSettings"];
            type: string;
            unique?: boolean;
        };
        CreateLayoutAssignmentRequest: {
            layout: string;
            /** Format: uuid */
            profileId?: string;
            recordType?: string;
        };
        CreateLayoutRequest: {
            definition: components["schemas"]["PageLayoutDefinition"];
            description?: string;
            label: string;
            name: string;
        };
        CreateListViewRequest: {
            definition: components["schemas"]["ListViewDefinition"];
            label: string;
            scope: string;
        };
        CreateNotificationTemplateRequest: {
            body: string;
            kind: string;
            title: string;
        };
        CreateObjectRequest: {
            description?: string;
            label: string;
            name: string;
            pluralLabel: string;
        };
        CreateOrganizationRequest: {
            displayName: string;
            slug: string;
        };
        CreateRecordPageAssignmentRequest: {
            /** Format: uuid */
            profileId?: string;
            recordPage: string;
            recordType?: string;
        };
        CreateRecordPageRequest: {
            definition: components["schemas"]["RecordPageDefinition"];
            description?: string;
            label: string;
            name: string;
        };
        CreateRecordRequest: {
            fields: {
                [key: string]: unknown;
            };
            recordType?: string;
        };
        CreateRecordTypeAssignmentRequest: {
            defaultForProfile?: boolean;
            /** Format: uuid */
            profileId: string;
            recordType?: string;
        };
        CreateRecordTypeRequest: {
            active?: boolean;
            availableFields?: string[];
            defaultType?: boolean;
            description?: string;
            label: string;
            layout?: string;
            name: string;
            picklistSubsets?: components["schemas"]["PicklistSubset"][];
        };
        CreateTabRequest: {
            definition: components["schemas"]["TabDefinition"];
            description?: string;
            label: string;
            name: string;
        };
        CreateValidationRuleRequest: {
            active?: boolean;
            description?: string;
            errorField?: string;
            errorMessage: string;
            expression: string;
            label: string;
            name: string;
        };
        CreateWorkflowRequest: {
            actions: components["schemas"]["WorkflowActionSpec"][];
            active?: boolean;
            condition?: string;
            description?: string;
            label: string;
            name: string;
            trigger: components["schemas"]["WorkflowTriggerSpec"];
        };
        CurrentUser: {
            abilities: string[];
            displayName: string;
            email: string;
            id: string;
            platformRoles: string[];
        };
        CustomPageAssetView: {
            created: boolean;
            /** Format: uuid */
            id: string;
            sha256: string;
            /** Format: int64 */
            size: number;
        };
        /** @description What a custom page is beyond its name (Sprint 18, ADR-0127): the script that draws it, by content, and a reference to code for the server. The script runs in the member's browser inside a sandbox and talks to the platform's data interface with the member's own permissions. The reference for the server is stored so that nothing about the shape changes when decision D6 is made; the platform never reads it and never runs anything because of it. */
        CustomPageDefinition: {
            /** @description A reference to code that would run on the server. Kept for later; never read, never executed (decision D6). */
            backend?: {
                reference: string;
            };
            /** @description The uploaded script. Upload it first (POST /api/v1/metadata/custom-page-assets) and name the asset it gives back; the hash and size must be the asset's own. */
            frontend: {
                /** Format: uuid */
                assetId: string;
                sha256: string;
                size: number;
            };
        };
        CustomPageScriptView: {
            apiName: string;
            /** Format: uuid */
            assetId: string;
            code: string;
            label: string;
            sha256: string;
        };
        CustomPageView: {
            apiName: string;
            definition: components["schemas"]["CustomPageDefinition"];
            description: string;
            label: string;
            /** Format: int64 */
            version: number;
        };
        DataAccessView: {
            everything?: boolean;
            fields: components["schemas"]["PermissionEntry"][];
            objects: components["schemas"]["PermissionEntry"][];
        };
        DataAction: {
            implies: string[];
            key: string;
            title: string;
        };
        DataCatalogue: {
            fieldActions: components["schemas"]["DataAction"][];
            objectActions: components["schemas"]["DataAction"][];
            objects: components["schemas"]["DataObject"][];
        };
        DataField: {
            key: string;
            label: string;
        };
        DataObject: {
            fields: components["schemas"]["DataField"][];
            key: string;
            label: string;
            retained: boolean;
        };
        DeadLetterGroup: {
            /** Format: int64 */
            count: number;
            objectApiName: string;
            reason: string;
            workflow: string;
        };
        EmbeddedTokenRequest: {
            customPage?: string;
        };
        EmbeddedTokenView: {
            /** Format: date-time */
            expiresAt: string;
            token: string;
        };
        EntitlementInfo: {
            enabled?: boolean;
            inPlan?: boolean;
            key: string;
            name: string;
            override?: boolean;
            removalNote?: string;
        };
        /**
         * @description Stable machine-readable error code. Branch on this, never on the message.
         * @enum {string}
         */
        ErrorCode: "VALIDATION_ERROR" | "MALFORMED_REQUEST" | "UNAUTHENTICATED" | "FORBIDDEN" | "NOT_FOUND" | "METHOD_NOT_ALLOWED" | "NOT_ACCEPTABLE" | "CONFLICT" | "CONCURRENT_MODIFICATION" | "PAYLOAD_TOO_LARGE" | "UNSUPPORTED_MEDIA_TYPE" | "RATE_LIMITED" | "INTERNAL_ERROR" | "SERVICE_UNAVAILABLE" | "TENANT_UNAVAILABLE" | "RECORD_LOCKED";
        ExpressionCheckView: {
            fields: string[];
            problem?: components["schemas"]["ExpressionProblemView"];
            type?: string;
            usesClock: boolean;
            valid: boolean;
        };
        ExpressionProblemView: {
            /** Format: int32 */
            column?: number;
            kind: string;
            /** Format: int32 */
            line?: number;
            message: string;
        };
        ExpressionTestView: {
            message?: string;
            outcome: string;
            problem?: components["schemas"]["ExpressionProblemView"];
            type?: string;
            value?: string;
        };
        FieldIndexStatusView: {
            field: string;
            flagged: boolean;
            /** Format: int64 */
            recordsDone: number;
            state: string;
        };
        /** @description The API name of a field. */
        FieldNameShape: string;
        FieldSearchStatusView: {
            field: string;
            /** Format: int64 */
            recordsDone?: number;
            state: string;
        };
        FieldSettings: {
            dependency?: components["schemas"]["PicklistDependency"];
            /** Format: int32 */
            digits?: number;
            expression?: string;
            listLabel?: string;
            /** Format: int32 */
            maxLength?: number;
            onDelete?: string;
            /** Format: int32 */
            precision?: number;
            prefix?: string;
            reparentable?: boolean;
            resultType?: string;
            /** Format: int32 */
            scale?: number;
            /** Format: int64 */
            startAt?: number;
            targetObject?: string;
            values?: components["schemas"]["PicklistOption"][];
            /** Format: int32 */
            width?: number;
        };
        FieldTypeView: {
            allowsDefault: boolean;
            allowsRequired: boolean;
            allowsUnique: boolean;
            calculated: boolean;
            description: string;
            formulaResult: boolean;
            label: string;
            settings: string[];
            type: string;
        };
        FieldUniquenessStatusView: {
            /** Format: int64 */
            collidingRecords: number;
            field: string;
            partner?: string;
            /** Format: int64 */
            recordsDone: number;
            state: string;
        };
        FieldView: {
            apiName: string;
            codeValues?: boolean;
            defaultValue?: string;
            description: string;
            editable: boolean;
            externalId?: boolean;
            indexed?: boolean;
            kind: string;
            label: string;
            organizationValues?: boolean;
            platformWritten?: boolean;
            problem?: string;
            required: boolean;
            retired: boolean;
            searchable?: boolean;
            settings: components["schemas"]["FieldSettings"];
            type: string;
            unique: boolean;
            /** Format: int64 */
            version: number;
        };
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
        FormActionsView: {
            create: boolean;
            delete: boolean;
            update: boolean;
        };
        FormFieldView: {
            apiName: string;
            calculated: boolean;
            canAddValues?: boolean;
            codeValues?: boolean;
            defaultValue?: string;
            dependency?: components["schemas"]["PicklistDependency"];
            description: string;
            /** Format: int32 */
            digits?: number;
            editable: boolean;
            externalId: boolean;
            filterOperators: string[];
            kind: string;
            label: string;
            /** Format: int32 */
            maxLength?: number;
            /** Format: int32 */
            precision?: number;
            prefix?: string;
            required: boolean;
            resultType?: string;
            /** Format: int32 */
            scale?: number;
            searchable: boolean;
            sortable: boolean;
            targetLabel?: string;
            targetObject?: string;
            targetOpenable: boolean;
            type: string;
            unique: boolean;
            values?: components["schemas"]["PicklistOption"][];
            /** Format: int32 */
            width?: number;
        };
        FormLayoutView: {
            layout?: string;
            sections: components["schemas"]["FormSectionView"][];
            source: string;
        };
        FormRecordTypeView: {
            apiName: string;
            defaultType: boolean;
            description: string;
            label: string;
            selectable: boolean;
        };
        FormSectionView: {
            /** Format: int32 */
            columns: number;
            fields: string[];
            label: string;
        };
        FormulaRecalculationView: {
            /** Format: int32 */
            formulaFields: number;
            /** Format: date-time */
            lastFinishedAt?: string;
            /** Format: int64 */
            lastRecordsChanged: number;
            /** Format: int64 */
            lastRecordsExamined: number;
            /** Format: int64 */
            lastRecordsUnavailable: number;
            objectApiName: string;
            /** Format: int64 */
            recordsChanged: number;
            /** Format: int64 */
            recordsExamined: number;
            /** Format: int64 */
            recordsUnavailable: number;
            /** Format: date-time */
            startedAt?: string;
            startedBy?: string;
            state: string;
        };
        FormulaReportItemView: {
            fieldApiName: string;
            objectApiName: string;
            problem: string;
        };
        FunctionView: {
            description: string;
            name: string;
            rulesOnly: boolean;
            signature: string;
        };
        GrantAbilityRequest: {
            ability: string;
            reason?: string;
        };
        GrantPlatformRoleRequest: {
            email: string;
            role: string;
        };
        GrantView: {
            ability: string;
            reason: string;
            /** Format: date-time */
            since: string;
        };
        GroupRef: {
            direct?: boolean;
            /** Format: uuid */
            id: string;
            name: string;
        };
        GroupView: {
            description: string;
            groups: components["schemas"]["GroupRef"][];
            /** Format: uuid */
            id: string;
            name: string;
            people: string[];
            policies: components["schemas"]["AccessPolicyRef"][];
        };
        ImportJobView: {
            /** Format: date-time */
            createdAt: string;
            /** Format: int32 */
            createdRows?: number;
            dryRun?: boolean;
            errorsAvailable?: boolean;
            /** Format: date-time */
            expiresAt?: string;
            externalIdField?: string;
            /** Format: int32 */
            failedRows?: number;
            failure?: string;
            /** Format: date-time */
            finishedAt?: string;
            /** Format: uuid */
            id: string;
            object: string;
            operation: string;
            /** Format: int32 */
            processedRows?: number;
            state: string;
            /** Format: int32 */
            totalRows?: number;
            /** Format: int32 */
            updatedRows?: number;
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
            displayName?: string;
            email: string;
            existingAccount?: boolean;
            organizationName: string;
        };
        InvitationView: {
            /** Format: date-time */
            createdAt: string;
            displayName?: string;
            email: string;
            /** Format: date-time */
            expiresAt: string;
            /** Format: uuid */
            id: string;
            /** Format: uuid */
            profileId?: string;
            profileName?: string;
            roleName?: string;
            /** Format: int32 */
            sentCount?: number;
            status: string;
        };
        InviteRequest: {
            active?: boolean;
            displayName?: string;
            email: string;
            /** Format: uuid */
            profileId?: string;
            /** Format: uuid */
            roleId?: string;
        };
        LayoutAssignmentView: {
            key: string;
            layout: string;
            /** Format: uuid */
            profileId?: string;
            profileName?: string;
            recordType?: string;
            /** Format: int64 */
            version: number;
        };
        LayoutField: {
            /**
             * @description EDITABLE: a person may change it. READ_ONLY: shown, never changed on this page. REQUIRED: must have a value on this page, whatever the field itself says. HIDDEN: kept in the layout but not shown. Left out: EDITABLE.
             * @enum {string}
             */
            behaviour?: "EDITABLE" | "READ_ONLY" | "REQUIRED" | "HIDDEN";
            field: components["schemas"]["FieldNameShape"];
        };
        LayoutResolutionView: {
            layout?: string;
            rule: string;
        };
        LayoutSection: {
            /**
             * @description Left out: 1.
             * @enum {integer}
             */
            columns?: 1 | 2;
            fields: components["schemas"]["LayoutField"][];
            label: string;
        };
        LayoutView: {
            apiName: string;
            definition: components["schemas"]["PageLayoutDefinition"];
            description: string;
            label: string;
            objectApiName: string;
            /** Format: int64 */
            version: number;
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
        LicenceTypeItem: {
            key: string;
            kind: string;
            name: string;
        };
        LifecycleRequest: {
            confirm?: string;
            reason: string;
        };
        Limits: {
            /** Format: int32 */
            maxApproversPerStep: number;
            /** Format: int32 */
            maxCommentLength: number;
            /** Format: int32 */
            maxDelegationDays: number;
            /** Format: int32 */
            maxOutcomeFields: number;
            /** Format: int32 */
            maxSteps: number;
        };
        LinkedRecordView: {
            fields: {
                [key: string]: unknown;
            };
            id: string;
        };
        ListViewDefinition: {
            columns?: string[];
            filter?: components["schemas"]["QueryFilterNode"];
            sort?: components["schemas"]["QuerySortKey"][];
        };
        ListViewView: {
            definition: components["schemas"]["ListViewDefinition"];
            id: string;
            label: string;
            mine: boolean;
            objectApiName: string;
            scope: string;
            /** Format: date-time */
            updatedAt: string;
            /** Format: int64 */
            version: number;
        };
        LookupLabelsRequest: {
            ids: string[];
        };
        LookupLabelsView: {
            labels: {
                [key: string]: string;
            };
        };
        LookupOptionView: {
            id: string;
            label: string;
        };
        LookupOptionsRequest: {
            /** Format: int32 */
            limit?: number;
            text?: string;
        };
        MemberAccessView: {
            abilities: string[];
            data: components["schemas"]["DataAccessView"];
            grants: components["schemas"]["GrantView"][];
            groups: components["schemas"]["GroupRef"][];
            licenceHeld?: boolean;
            /** Format: uuid */
            membershipId: string;
            policies: components["schemas"]["AccessPolicyRef"][];
            /** Format: uuid */
            profileId?: string;
            profileLicenceType?: string;
            profileName?: string;
            /** Format: uuid */
            roleId?: string;
            roleName?: string;
        };
        MemberView: {
            displayName: string;
            email: string;
            foundingAdministrator?: boolean;
            /** Format: uuid */
            id: string;
            licence?: string;
            licensed?: boolean;
            policies: components["schemas"]["AccessPolicyRef"][];
            /** Format: uuid */
            profileId?: string;
            profileName?: string;
            /** Format: uuid */
            roleId?: string;
            roleName?: string;
            /** Format: date-time */
            since: string;
            status: string;
            you?: boolean;
        };
        MyApplicationView: {
            apiName: string;
            iconKey?: string;
            items: components["schemas"]["NavigationEntryView"][];
            label: string;
        };
        Named: {
            /** Format: uuid */
            id: string;
            name: string;
        };
        NavigationEntryView: {
            /** Format: uuid */
            assetId?: string;
            available: boolean;
            customPageApiName?: string;
            label: string;
            objectApiName?: string;
            pageApiName?: string;
            pageKey?: string;
            path?: string;
            tabApiName?: string;
            type: string;
        };
        /** @description One entry of an application's navigation. A TAB entry lists a tab of the organization (Sprint 18); its label is optional and replaces the label of the tab. */
        NavigationItem: {
            label?: string;
            object?: components["schemas"]["ObjectNameShape"];
            page?: components["schemas"]["ApiNameShape"];
            /** @description A page inside the organization: a path that starts with a slash. Never an address with a host. */
            path?: string;
            tab?: components["schemas"]["ApiNameShape"];
            /** @enum {string} */
            type: "OBJECT" | "RECORD_PAGE" | "LINK" | "HEADING" | "TAB";
        } & (unknown & unknown & unknown & unknown & unknown);
        NewSheetVersionRequest: {
            /** Format: int32 */
            basedOn?: number;
            /** Format: int32 */
            dimension?: number;
            rowsMode?: string;
        };
        NotificationPlaceholderView: {
            help: string;
            name: string;
        };
        NotificationPreferencesView: {
            choices: components["schemas"]["PreferenceChoiceView"][];
        };
        NotificationSummaryView: {
            /** Format: int32 */
            unread: number;
        };
        NotificationTemplateCheckView: {
            previewBody?: string;
            previewTitle?: string;
            problems: {
                [key: string]: string[];
            };
        };
        NotificationTemplateView: {
            body: string;
            category: string;
            custom: boolean;
            defaultBody: string;
            defaultTitle: string;
            kind: string;
            placeholders: components["schemas"]["NotificationPlaceholderView"][];
            summary: string;
            title: string;
            /** Format: int64 */
            version?: number;
        };
        NotificationTemplatesView: {
            /** Format: int32 */
            bodyMax: number;
            templates: components["schemas"]["NotificationTemplateView"][];
            /** Format: int32 */
            titleMax: number;
        };
        NotificationView: {
            body: string;
            category: string;
            /** Format: date-time */
            createdAt: string;
            href?: string;
            /** Format: uuid */
            id: string;
            kind: string;
            read: boolean;
            title: string;
        };
        ObjectFormView: {
            actions: components["schemas"]["FormActionsView"];
            fields: components["schemas"]["FormFieldView"][];
            label: string;
            layout: components["schemas"]["FormLayoutView"];
            objectApiName: string;
            pluralLabel: string;
            recordType?: string;
            recordTypes: components["schemas"]["FormRecordTypeView"][];
            searchEnabled: boolean;
            titleField?: string;
        };
        ObjectIndexStatusView: {
            fields: components["schemas"]["FieldIndexStatusView"][];
            objectApiName: string;
            search: components["schemas"]["FieldSearchStatusView"][];
            slots: {
                [key: string]: number;
            };
            uniqueness: components["schemas"]["FieldUniquenessStatusView"][];
        };
        /** @description The API name of an object. */
        ObjectNameShape: string;
        ObjectRelationshipsView: {
            children: components["schemas"]["RelationshipView"][];
            manyToMany: components["schemas"]["RelationshipView"][];
            parents: components["schemas"]["RelationshipView"][];
        };
        ObjectSharingView: {
            configured?: boolean;
            defaultAccess: string;
            grantViaHierarchy?: boolean;
            object: string;
            /** Format: int64 */
            version?: number;
        };
        ObjectSummaryView: {
            apiName: string;
            /** Format: int32 */
            customFieldCount: number;
            /** Format: int32 */
            fieldCount: number;
            kind: string;
            label: string;
            managedBy?: string;
            pluralLabel: string;
        };
        ObjectView: {
            apiName: string;
            description: string;
            editable: boolean;
            extensible: boolean;
            fields: components["schemas"]["FieldView"][];
            kind: string;
            label: string;
            managedBy?: string;
            pluralLabel: string;
            /** Format: int64 */
            version: number;
        };
        OrganizationCreated: {
            displayName: string;
            host: string;
            slug: string;
        };
        OrganizationSummary: {
            displayName: string;
            host: string;
            slug: string;
        };
        PageAction: {
            /**
             * @description SHARE opens the record's own sharing (Sprint 17/17A: who it is shared with, and its owner); shown only to whoever may change the record, exactly like EDIT.
             * @enum {string}
             */
            action: "EDIT" | "DELETE" | "CLONE" | "SHARE";
            label?: string;
            visibleWhen?: components["schemas"]["VisibilityRule"];
        };
        /** @description One building block of a record page. Its type decides which other properties it takes. */
        PageComponent: {
            columns?: components["schemas"]["FieldNameShape"][];
            field?: components["schemas"]["FieldNameShape"];
            highlightFields?: components["schemas"]["FieldNameShape"][];
            /** @description Names the component inside the page; unique in the page. */
            key: string;
            label?: string;
            /** @description Left out: 5. */
            limit?: number;
            object?: components["schemas"]["ObjectNameShape"];
            /**
             * @description Left out: MAIN.
             * @enum {string}
             */
            region?: "MAIN" | "SIDEBAR";
            tabs?: components["schemas"]["PageTab"][];
            titleField?: components["schemas"]["FieldNameShape"];
            /** @enum {string} */
            type: "HEADER" | "DETAILS" | "RELATED_LIST" | "TABS" | "ACTIVITY" | "HISTORY";
            visibleWhen?: components["schemas"]["VisibilityRule"];
        } & (unknown & unknown & unknown & unknown);
        /** @description The structure of a record form: sections with the fields in them, and how each field behaves. */
        PageLayoutDefinition: {
            sections: components["schemas"]["LayoutSection"][];
        };
        PageTab: {
            components: components["schemas"]["TabComponent"][];
            label: string;
        };
        Pagination: {
            hasMore: boolean;
            /** Format: int32 */
            limit: number;
            nextCursor?: string;
        };
        ParkedEventView: {
            /** Format: int32 */
            attempts: number;
            /** Format: uuid */
            id: string;
            lastFailureType?: string;
            /** Format: date-time */
            occurredAt: string;
            type: string;
        };
        PermissionEntry: {
            actions: string[];
            key: string;
        };
        PicklistDependency: {
            allowed: {
                [key: string]: string[];
            };
            controllingField: string;
        };
        PicklistOption: {
            active: boolean;
            label: string;
            value: string;
        };
        PicklistSubset: {
            field: string;
            values: string[];
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
        PreferenceChoiceRequest: {
            category: string;
            channel: string;
            enabled: boolean;
        };
        PreferenceChoiceView: {
            category: string;
            categoryLabel: string;
            channel: string;
            channelLabel: string;
            description: string;
            enabled: boolean;
            locked: boolean;
        };
        ProblemView: {
            dependent?: string;
            fields: string[];
            itemApiName?: string;
            kind: string;
            message: string;
            objectApiName?: string;
            /** Format: int32 */
            position?: number;
        };
        ProfileView: {
            abilities: string[];
            defaultProfile?: boolean;
            description: string;
            fullAccess?: boolean;
            /** Format: uuid */
            id: string;
            licenceType: string;
            /** Format: int32 */
            members?: number;
            name: string;
            system?: boolean;
        };
        ProvisionOrganizationRequest: {
            displayName: string;
            email: string;
            planKey: string;
            slug: string;
        };
        QueriedRecordView: {
            linked: {
                [key: string]: components["schemas"]["LinkedRecordView"];
            };
            record: components["schemas"]["RecordView"];
        };
        QueryFilterNode: {
            field?: string;
            items?: components["schemas"]["QueryFilterNode"][];
            op: string;
            values?: unknown[];
        };
        QueryLinkRequest: {
            field: string;
            fields: string[];
        };
        QueryRequest: {
            cursor?: string;
            fields?: string[];
            filter?: components["schemas"]["QueryFilterNode"];
            /** Format: int32 */
            limit?: number;
            resolve?: components["schemas"]["QueryLinkRequest"][];
            sort?: components["schemas"]["QuerySortKey"][];
        };
        QuerySortKey: {
            descending?: boolean;
            field: string;
        };
        ReasonRequest: {
            reason: string;
        };
        RecordApprovalView: {
            actions: components["schemas"]["ApprovalActionsView"];
            available: boolean;
            current?: components["schemas"]["ApprovalInstanceView"];
            locked: boolean;
            notSubmittableReason?: string;
            processes: components["schemas"]["ApprovalChoiceView"][];
        };
        RecordCheckView: {
            calculated: {
                [key: string]: unknown;
            };
            cleared: string[];
        };
        RecordFileView: {
            contentType: string;
            /** Format: date-time */
            createdAt: string;
            /** Format: uuid */
            createdBy: string;
            /** Format: uuid */
            id: string;
            name: string;
            sha256: string;
            /** Format: int64 */
            size?: number;
        };
        RecordPageActionView: {
            action: string;
            label: string;
        };
        RecordPageAssignmentView: {
            key: string;
            /** Format: uuid */
            profileId?: string;
            profileName?: string;
            recordPage: string;
            recordType?: string;
            /** Format: int64 */
            version: number;
        };
        RecordPageBlockView: {
            /** Format: uuid */
            assetId?: string;
            columns?: string[];
            customPageApiName?: string;
            field?: string;
            kind: string;
            label: string;
            /** Format: int32 */
            limit?: number;
            object?: string;
            tabApiName?: string;
        };
        /** @description The structure of a record page: which layout its details show, its components in order, and its actions. */
        RecordPageDefinition: {
            actions?: components["schemas"]["PageAction"][];
            components: components["schemas"]["PageComponent"][];
            layout: components["schemas"]["ApiNameShape"];
        };
        RecordPageDescriptorView: {
            above: components["schemas"]["RecordPageBlockView"][];
            actions: components["schemas"]["RecordPageActionView"][];
            apiName?: string;
            below: components["schemas"]["RecordPageBlockView"][];
            header: components["schemas"]["RecordPageHeaderView"];
            layout?: string;
            sidebar: components["schemas"]["RecordPageBlockView"][];
            tabs: components["schemas"]["RecordPageTabView"][];
        };
        RecordPageHeaderView: {
            highlightFields: string[];
            titleField?: string;
        };
        RecordPageResolutionView: {
            recordPage?: string;
            rule: string;
        };
        RecordPageTabView: {
            blocks: components["schemas"]["RecordPageBlockView"][];
            label: string;
        };
        RecordPageView: {
            apiName: string;
            definition: components["schemas"]["RecordPageDefinition"];
            description: string;
            label: string;
            objectApiName: string;
            /** Format: int64 */
            version: number;
        };
        RecordShareView: {
            accessLevel: string;
            /** Format: uuid */
            granteeId: string;
            granteeLabel?: string;
            granteeType: string;
            /** Format: uuid */
            id: string;
            /** Format: date-time */
            sharedAt: string;
            /** Format: uuid */
            sharedBy: string;
        };
        RecordTypeAssignmentView: {
            defaultForProfile?: boolean;
            key: string;
            /** Format: uuid */
            profileId: string;
            profileName?: string;
            recordType: string;
            /** Format: int64 */
            version: number;
        };
        RecordTypeView: {
            active: boolean;
            allFields: boolean;
            apiName: string;
            availableFields: string[];
            defaultType: boolean;
            description: string;
            label: string;
            layout?: string;
            picklistSubsets: components["schemas"]["PicklistSubset"][];
            /** Format: int64 */
            version: number;
        };
        RecordView: {
            /** Format: date-time */
            createdAt: string;
            /** Format: date-time */
            deletedAt?: string;
            fields: {
                [key: string]: unknown;
            };
            id: string;
            objectApiName: string;
            ownerId: string;
            recordType?: string;
            /** Format: date-time */
            updatedAt: string;
            /** Format: int64 */
            version: number;
        };
        RelationshipView: {
            childObject: string;
            field: string;
            fieldLabel: string;
            fieldType: string;
            listLabel: string;
            onDelete: string;
            otherObject: string;
            parentObject: string;
            reparentable: boolean;
            required: boolean;
            type: string;
            viaObject?: string;
        };
        ReleaseItemView: {
            action: string;
            itemApiName?: string;
            kind: string;
            objectApiName: string;
        };
        ReleaseView: {
            changeSetName?: string;
            /** Format: date-time */
            createdAt: string;
            items: components["schemas"]["ReleaseItemView"][];
            kind: string;
            latest: boolean;
            /** Format: int64 */
            number: number;
            /** Format: int64 */
            rolledBackBy?: number;
            /** Format: int64 */
            undoesRelease?: number;
        };
        RequestAccepted: {
            message: string;
        };
        ResetPasswordRequest: {
            newPassword: string;
            token: string;
        };
        ResolveApprovalRequest: {
            allowSelfApproval?: boolean;
            fallback?: components["schemas"]["ApprovalApproverSpec"];
            /** Format: uuid */
            recordId: string;
            steps: components["schemas"]["ApprovalStepSpec"][];
        };
        ResolvedStep: {
            label: string;
            left: {
                [key: string]: number;
            };
            name: string;
            nobody: boolean;
            people: components["schemas"]["ApprovalPersonView"][];
            skipped: boolean;
            usesFallback: boolean;
        };
        RoleView: {
            description: string;
            /** Format: uuid */
            id: string;
            /** Format: int32 */
            members?: number;
            name: string;
            /** Format: uuid */
            parentId?: string;
        };
        SaveAccessPolicyRequest: {
            abilities: string[];
            description?: string;
            name: string;
            requiredLicenceType?: string;
        };
        SaveDataAccessRequest: {
            fields: components["schemas"]["PermissionEntry"][];
            objects: components["schemas"]["PermissionEntry"][];
        };
        SaveGroupRequest: {
            description?: string;
            name: string;
        };
        SaveObjectSharingRequest: {
            defaultAccess: string;
            grantViaHierarchy: boolean;
            /** Format: int64 */
            version?: number;
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
        SaveProfileRequest: {
            abilities: string[];
            description?: string;
            licenceType: string;
            name: string;
        };
        SaveRoleRequest: {
            description?: string;
            name: string;
            /** Format: uuid */
            parentId?: string;
        };
        SaveSharingRuleRequest: {
            accessLevel: string;
            active?: boolean;
            criterion?: components["schemas"]["QueryFilterNode"];
            kind?: string;
            name: string;
            object?: string;
            /** Format: uuid */
            sourceId?: string;
            sourceType?: string;
            /** Format: uuid */
            targetId: string;
            targetType: string;
            /** Format: int64 */
            version?: number;
        };
        SearchRecordsRequest: {
            cursor?: string;
            /** Format: int32 */
            limit?: number;
            text: string;
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
        SetEntitlementRequest: {
            enabled?: boolean;
            reason: string;
        };
        SetPoolRequest: {
            /** Format: int32 */
            quantity: number;
            reason: string;
        };
        ShareRecordRequest: {
            accessLevel: string;
            /** Format: uuid */
            granteeId: string;
            granteeType: string;
        };
        ShareTargetView: {
            /** Format: uuid */
            id: string;
            label: string;
        };
        SharingRuleView: {
            accessLevel: string;
            active?: boolean;
            criterion?: components["schemas"]["QueryFilterNode"];
            /** Format: uuid */
            id: string;
            kind: string;
            name: string;
            object: string;
            /** Format: uuid */
            sourceId?: string;
            sourceType?: string;
            /** Format: uuid */
            targetId: string;
            targetType: string;
            /** Format: int64 */
            version?: number;
        };
        SheetCell: {
            /**
             * @description A header cell is always fixed. A body cell that holds a value and has no kind is fixed text; otherwise it is an input cell.
             * @enum {string}
             */
            kind?: "fixed" | "input";
            style?: components["schemas"]["SheetCellStyle"];
            value?: string | boolean | null;
        };
        /** @description A cell: the identifier of its row, a colon, the identifier of its column. */
        SheetCellRef: string;
        /** @description Only a fixed cell may carry a style. The size is one of four names and never a number: the page decides what each name looks like. */
        SheetCellStyle: {
            bold?: boolean;
            /** @enum {string} */
            fontSize?: "SMALL" | "NORMAL" | "LARGE" | "TITLE";
            italic?: boolean;
        };
        SheetColumn: {
            /** @description The value a new row starts with. It must fit the column type. */
            default?: string | boolean | null;
            id: components["schemas"]["SheetId"];
            label: string;
            /** @description The list of a picklist column, kept inside the version so that it is frozen with it. */
            picklist?: {
                values: string[];
            } | null;
            required?: boolean;
            /**
             * @description What a filler may type: text, a number written as digits, a date written year-month-day, one value of a list, or yes or no.
             * @enum {string}
             */
            type: "text" | "number" | "date" | "picklist" | "boolean";
            width?: number;
        };
        /** @description The grid of one version: named columns of five types, header rows (a title over merged columns), optionally named body rows, merged cells, and the cells that hold fixed text or a style. Stored sparsely: a body cell that holds nothing is an input cell and is not stored. */
        SheetGrid: {
            /** @description Sparse: only a cell that holds something or has a style is stored. The key is the row identifier, a colon and the column identifier. */
            cells: {
                [key: string]: components["schemas"]["SheetCell"];
            };
            columns: components["schemas"]["SheetColumn"][];
            /**
             * @description 1: columns only, the filler adds the rows. 2: the sheet names the rows.
             * @enum {integer}
             */
            dimension: 1 | 2;
            /**
             * @description The format of this document. A document of another number is refused.
             * @constant
             */
            formatVersion: 1;
            /** @description Bands above the body under the column labels. Their cells are always fixed text. */
            headerRows?: components["schemas"]["SheetRowDefinition"][];
            merges: components["schemas"]["SheetMerge"][];
            /** @description The named body rows (dimension 2 only). */
            rows: components["schemas"]["SheetRowDefinition"][];
            /**
             * @description Dimension 1 is REPEATABLE. Dimension 2 is FIXED or FIXED_AND_REPEATABLE.
             * @enum {string}
             */
            rowsMode: "FIXED" | "REPEATABLE" | "FIXED_AND_REPEATABLE";
        };
        /** @description The stable identifier of a column, a row or a header row. Generated, never typed, never reused inside one sheet; everything that points at a place (cells, merges, later comments and fill-ins) uses it, never a position. */
        SheetId: string;
        SheetLimitsView: {
            /** Format: int32 */
            maxBytes: number;
            /** Format: int32 */
            maxCellChars: number;
            /** Format: int32 */
            maxColumns: number;
            /** Format: int32 */
            maxHeaderRows: number;
            /** Format: int32 */
            maxLabelChars: number;
            /** Format: int32 */
            maxMerges: number;
            /** Format: int32 */
            maxPicklistValues: number;
            /** Format: int32 */
            maxRows: number;
        };
        /** @description Two cells, top left and bottom right, inside one zone (header rows or body), never overlapping another merge. Only the top left cell keeps content. */
        SheetMerge: {
            from: components["schemas"]["SheetCellRef"];
            to: components["schemas"]["SheetCellRef"];
        };
        SheetRowDefinition: {
            height?: number;
            id: components["schemas"]["SheetId"];
            label?: string;
        };
        SheetVersionSummaryView: {
            /** Format: date-time */
            createdAt: string;
            /** Format: date-time */
            decidedAt?: string;
            /** Format: int32 */
            dimension: number;
            /** Format: int64 */
            gridBytes: number;
            gridHash: string;
            rowsMode: string;
            status: string;
            /** Format: date-time */
            submittedAt?: string;
            /** Format: date-time */
            updatedAt: string;
            /** Format: int64 */
            version: number;
            /** Format: int32 */
            versionNo: number;
        };
        SheetVersionView: {
            grid: components["schemas"]["SheetGrid"];
            version: components["schemas"]["SheetVersionSummaryView"];
        };
        SheetVersionsView: {
            canChange: boolean;
            limits: components["schemas"]["SheetLimitsView"];
            versions: components["schemas"]["SheetVersionSummaryView"][];
        };
        SignInRequest: {
            email: string;
            password: string;
        };
        SignUpRequest: {
            email: string;
        };
        StartImportRequest: {
            delimiter?: string;
            dryRun?: boolean;
            externalIdField?: string;
            mapping?: {
                [key: string]: string;
            };
            operation: string;
        };
        SubmitApprovalRequest: {
            comment?: string;
            process?: string;
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
        /** @description A component inside a tab: anything but another set of tabs. TAB shows a tab of the organization (Sprint 18): its label is optional and replaces the label of the tab, the same as a TAB entry of an application's navigation. */
        TabComponent: {
            columns?: components["schemas"]["FieldNameShape"][];
            field?: components["schemas"]["FieldNameShape"];
            key: string;
            label?: string;
            /** @description Left out: 5. */
            limit?: number;
            object?: components["schemas"]["ObjectNameShape"];
            tab?: components["schemas"]["ApiNameShape"];
            /** @enum {string} */
            type: "DETAILS" | "RELATED_LIST" | "ACTIVITY" | "HISTORY" | "TAB";
            visibleWhen?: components["schemas"]["VisibilityRule"];
        } & (unknown & unknown & unknown);
        /** @description What a tab is beyond its name (Sprint 18, ADR-0128). The only kind today is CUSTOM: the tab shows a custom page. An object needs no tab of its own: the navigation item OBJECT opens it. */
        TabDefinition: {
            customPage?: components["schemas"]["ApiNameShape"];
            iconKey?: string;
            /** @enum {string} */
            kind: "CUSTOM";
        } & unknown;
        TabView: {
            apiName: string;
            definition: components["schemas"]["TabDefinition"];
            description: string;
            label: string;
            /** Format: int64 */
            version: number;
        };
        TenantSummary: {
            displayName: string;
            slug: string;
        };
        TestExpressionRequest: {
            expression: string;
            objectApiName: string;
            purpose: string;
            values?: {
                [key: string]: string;
            };
        };
        TestValuesRequest: {
            values?: {
                [key: string]: string;
            };
        };
        UpdateApplicationRequest: {
            definition: components["schemas"]["ApplicationDefinition"];
            description?: string;
            label: string;
            /** Format: int64 */
            version: number;
        };
        UpdateApprovalProcessRequest: {
            active: boolean;
            allowSelfApproval: boolean;
            description?: string;
            entryCriteria?: string;
            /** Format: int32 */
            escalateAfterHours?: number;
            fallback?: components["schemas"]["ApprovalApproverSpec"];
            label: string;
            outcomes?: components["schemas"]["ApprovalOutcomesSpec"];
            /** Format: int32 */
            remindAfterHours?: number;
            steps: components["schemas"]["ApprovalStepSpec"][];
            /** Format: int64 */
            version: number;
        };
        UpdateCustomPageRequest: {
            definition: components["schemas"]["CustomPageDefinition"];
            description?: string;
            label: string;
            /** Format: int64 */
            version: number;
        };
        UpdateFieldRequest: {
            defaultValue?: string;
            description?: string;
            externalId?: boolean;
            indexed?: boolean;
            label: string;
            required?: boolean;
            searchable?: boolean;
            settings?: components["schemas"]["FieldSettings"];
            unique?: boolean;
            /** Format: int64 */
            version: number;
        };
        UpdateLayoutAssignmentRequest: {
            layout: string;
            /** Format: int64 */
            version: number;
        };
        UpdateLayoutRequest: {
            definition: components["schemas"]["PageLayoutDefinition"];
            description?: string;
            label: string;
            /** Format: int64 */
            version: number;
        };
        UpdateListViewRequest: {
            definition: components["schemas"]["ListViewDefinition"];
            label: string;
            scope: string;
            /** Format: int64 */
            version: number;
        };
        UpdateNotificationPreferencesRequest: {
            choices: components["schemas"]["PreferenceChoiceRequest"][];
        };
        UpdateNotificationTemplateRequest: {
            body: string;
            title: string;
            /** Format: int64 */
            version: number;
        };
        UpdateObjectRequest: {
            description?: string;
            label: string;
            pluralLabel: string;
            /** Format: int64 */
            version: number;
        };
        UpdateRecordPageAssignmentRequest: {
            recordPage: string;
            /** Format: int64 */
            version: number;
        };
        UpdateRecordPageRequest: {
            definition: components["schemas"]["RecordPageDefinition"];
            description?: string;
            label: string;
            /** Format: int64 */
            version: number;
        };
        UpdateRecordRequest: {
            clear?: string[];
            fields?: {
                [key: string]: unknown;
            };
            recordType?: string;
            /** Format: int64 */
            version: number;
        };
        UpdateRecordTypeAssignmentRequest: {
            defaultForProfile: boolean;
            /** Format: int64 */
            version: number;
        };
        UpdateRecordTypeRequest: {
            active?: boolean;
            availableFields?: string[];
            defaultType?: boolean;
            description?: string;
            label: string;
            layout?: string;
            picklistSubsets?: components["schemas"]["PicklistSubset"][];
            /** Format: int64 */
            version: number;
        };
        UpdateTabRequest: {
            definition: components["schemas"]["TabDefinition"];
            description?: string;
            label: string;
            /** Format: int64 */
            version: number;
        };
        UpdateValidationRuleRequest: {
            active: boolean;
            description?: string;
            errorField?: string;
            errorMessage: string;
            expression: string;
            label: string;
            /** Format: int64 */
            version: number;
        };
        UpdateWorkflowRequest: {
            actions: components["schemas"]["WorkflowActionSpec"][];
            active: boolean;
            condition?: string;
            description?: string;
            label: string;
            trigger: components["schemas"]["WorkflowTriggerSpec"];
            /** Format: int64 */
            version: number;
        };
        ValidateRecordRequest: {
            clear?: string[];
            fields?: {
                [key: string]: unknown;
            };
            /** Format: uuid */
            recordId?: string;
            recordType?: string;
        };
        ValidationRuleView: {
            active: boolean;
            apiName: string;
            description: string;
            errorField?: string;
            errorMessage: string;
            expression: string;
            label: string;
            objectApiName: string;
            /** Format: int64 */
            version: number;
        };
        /** @description A condition, or a group of conditions combined with all (AND) or any (OR). */
        VisibilityInner: ({
            ability?: string;
            all?: components["schemas"]["VisibilityLeaf"][];
            any?: components["schemas"]["VisibilityLeaf"][];
            field?: components["schemas"]["FieldNameShape"];
            /** @enum {string} */
            operator?: "EQUALS" | "NOT_EQUALS" | "IS_EMPTY" | "IS_NOT_EMPTY";
            value?: string | number | boolean;
        } & (unknown & unknown)) & (unknown | unknown | unknown | unknown);
        /** @description One condition: a field compared with a value, or the person holding an ability. */
        VisibilityLeaf: ({
            ability?: string;
            field?: components["schemas"]["FieldNameShape"];
            /** @enum {string} */
            operator?: "EQUALS" | "NOT_EQUALS" | "IS_EMPTY" | "IS_NOT_EMPTY";
            value?: string | number | boolean;
        } & (unknown & unknown & unknown)) & (unknown | unknown);
        /** @description When a component or an action is shown. Closed data, never text that is evaluated: a condition, or all/any of conditions, nested once. The expression engine of Sprint 13 may add to this list, never replace it. */
        VisibilityRule: ({
            ability?: string;
            all?: components["schemas"]["VisibilityInner"][];
            any?: components["schemas"]["VisibilityInner"][];
            field?: components["schemas"]["FieldNameShape"];
            /** @enum {string} */
            operator?: "EQUALS" | "NOT_EQUALS" | "IS_EMPTY" | "IS_NOT_EMPTY";
            value?: string | number | boolean;
        } & (unknown & unknown)) & (unknown | unknown | unknown | unknown);
        WorkflowActionSpec: {
            assignments?: components["schemas"]["WorkflowAssignmentSpec"][];
            /** Format: int32 */
            maxAttempts?: number;
            name: string;
            settings?: {
                [key: string]: string;
            };
            type: string;
        };
        WorkflowActivationRequest: {
            active: boolean;
            /** Format: int64 */
            version: number;
        };
        WorkflowAssignmentSpec: {
            field: string;
            fromField?: string;
            value?: string;
        };
        WorkflowBulkReplayRequest: {
            executions: string[];
        };
        WorkflowBulkReplayView: {
            results: components["schemas"]["WorkflowReplayResultView"][];
        };
        WorkflowCatalogueView: {
            actionTypes: components["schemas"]["CatalogueActionType"][];
            limits: components["schemas"]["CatalogueLimits"];
            triggerKinds: components["schemas"]["CatalogueTriggerKind"][];
        };
        WorkflowConditionCheckView: {
            actionProblems: {
                [key: string]: string[];
            };
            outcome: string;
            problem?: components["schemas"]["ExpressionProblemView"];
        };
        WorkflowDeadLetterSummaryView: {
            groups: components["schemas"]["DeadLetterGroup"][];
            /** Format: int64 */
            total: number;
        };
        WorkflowExecutionView: {
            /** Format: int32 */
            attempts: number;
            /** Format: int32 */
            chainDepth: number;
            failureType?: string;
            /** Format: date-time */
            finishedAt?: string;
            /** Format: uuid */
            id: string;
            objectApiName: string;
            /** Format: date-time */
            queuedAt: string;
            reason?: string;
            /** Format: uuid */
            recordId: string;
            /** Format: int64 */
            recordVersion?: number;
            /** Format: int32 */
            replayGeneration: number;
            /** Format: uuid */
            replayOf?: string;
            /** Format: uuid */
            replayedBy?: string;
            /** Format: int32 */
            resumeStep: number;
            /** Format: date-time */
            runAt: string;
            /** Format: date-time */
            startedAt?: string;
            status: string;
            steps?: components["schemas"]["WorkflowStepView"][];
            trigger: string;
            workflow: string;
            /** Format: int32 */
            workflowVersion: number;
        };
        WorkflowReplayRequest: {
            /** Format: int32 */
            fromStep?: number;
        };
        WorkflowReplayResultView: {
            /** Format: uuid */
            execution: string;
            outcome: string;
            reason?: string;
            /** Format: uuid */
            replay?: string;
        };
        WorkflowStepView: {
            /** Format: int32 */
            attempts: number;
            failureType?: string;
            /** Format: date-time */
            finishedAt?: string;
            name: string;
            /** Format: date-time */
            nextAttemptAt?: string;
            /** Format: int32 */
            position: number;
            reason?: string;
            /** Format: date-time */
            startedAt: string;
            status: string;
            type: string;
        };
        WorkflowTriggerSpec: {
            at?: string;
            dayOfWeek?: string;
            /** Format: int32 */
            days?: number;
            field?: string;
            fields?: string[];
            kind: string;
            outcome?: string;
            process?: string;
            selection?: components["schemas"]["QueryFilterNode"];
            time?: string;
            timeZone?: string;
        };
        WorkflowView: {
            actions: components["schemas"]["WorkflowActionSpec"][];
            active: boolean;
            apiName: string;
            condition?: string;
            /** Format: int32 */
            definitionVersion: number;
            description: string;
            label: string;
            objectApiName: string;
            trigger: components["schemas"]["WorkflowTriggerSpec"];
            /** Format: int64 */
            version: number;
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
    listAbilities: {
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
                    "application/json": components["schemas"]["ApiResponseListAbilityInfo"];
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
    listAccessPolicies: {
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
                    "application/json": components["schemas"]["ApiResponseListAccessPolicyView"];
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
    createAccessPolicy: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["SaveAccessPolicyRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ApiResponseAccessPolicyView"];
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
    updateAccessPolicy: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                policyId: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["SaveAccessPolicyRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ApiResponseAccessPolicyView"];
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
    deleteAccessPolicy: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                policyId: string;
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
    getAccessPolicyDataAccess: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                policyId: string;
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
                    "application/json": components["schemas"]["ApiResponseDataAccessView"];
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
    replaceAccessPolicyDataAccess: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                policyId: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["SaveDataAccessRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ApiResponseDataAccessView"];
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
    getMyApplications: {
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
                    "application/json": components["schemas"]["ApiResponseListMyApplicationView"];
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
    getCustomPageScript: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                customPageApiName: string;
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
                    "application/json": components["schemas"]["ApiResponseCustomPageScriptView"];
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
    listApprovalDelegations: {
        parameters: {
            query?: {
                delegator?: string;
                all?: boolean;
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
                    "application/json": components["schemas"]["ApiResponseListApprovalDelegationView"];
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
    createApprovalDelegation: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["CreateApprovalDelegationRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ApiResponseApprovalDelegationView"];
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
    revokeApprovalDelegation: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: string;
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
    listApprovalTasks: {
        parameters: {
            query?: {
                tab?: string;
                object?: string;
                process?: string;
                limit?: number;
                cursor?: string;
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
                    "application/json": components["schemas"]["ApiPageResponseApprovalTaskView"];
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
    getApprovalInboxSummary: {
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
                    "application/json": components["schemas"]["ApiResponseApprovalInboxSummaryView"];
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
    listApprovals: {
        parameters: {
            query?: {
                status?: string;
                object?: string;
                process?: string;
                attention?: boolean;
                record?: string;
                limit?: number;
                cursor?: string;
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
                    "application/json": components["schemas"]["ApiPageResponseApprovalInstanceSummaryView"];
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
    getApproval: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: string;
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
                    "application/json": components["schemas"]["ApiResponseApprovalInstanceView"];
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
    approveApproval: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["ApprovalDecisionRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ApiResponseApprovalInstanceView"];
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
    assignApproval: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["ApprovalReassignRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ApiResponseApprovalInstanceView"];
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
    cancelApproval: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["ApprovalDecisionRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ApiResponseApprovalInstanceView"];
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
    reapplyApprovalOutcome: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["ApprovalReapplyRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ApiResponseApprovalInstanceView"];
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
    reassignApproval: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["ApprovalReassignRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ApiResponseApprovalInstanceView"];
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
    recallApproval: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["ApprovalDecisionRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ApiResponseApprovalInstanceView"];
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
    rejectApproval: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["ApprovalDecisionRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ApiResponseApprovalInstanceView"];
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
    searchApprovalPeople: {
        parameters: {
            query?: {
                text?: string;
                limit?: number;
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
                    "application/json": components["schemas"]["ApiResponseListApprovalPersonView"];
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
    listAuditEvents: {
        parameters: {
            query?: {
                limit?: number;
                cursor?: string;
                from?: string;
                to?: string;
                actor?: string;
                kind?: string;
                target?: string;
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
                    "application/json": components["schemas"]["ApiPageResponseAuditEventView"];
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
    getAutomationHealth: {
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
                    "application/json": components["schemas"]["ApiResponseAutomationHealthView"];
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
    getMyDataAccess: {
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
                    "application/json": components["schemas"]["ApiResponseDataAccessView"];
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
    getDataCatalogue: {
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
                    "application/json": components["schemas"]["ApiResponseDataCatalogue"];
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
    issueEmbeddedToken: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: {
            content: {
                "application/json": components["schemas"]["EmbeddedTokenRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ApiResponseEmbeddedTokenView"];
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
    listGroups: {
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
                    "application/json": components["schemas"]["ApiResponseListGroupView"];
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
    createGroup: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["SaveGroupRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ApiResponseGroupView"];
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
    getGroup: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                groupId: string;
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
                    "application/json": components["schemas"]["ApiResponseGroupView"];
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
    updateGroup: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                groupId: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["SaveGroupRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ApiResponseGroupView"];
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
    deleteGroup: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                groupId: string;
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
    addGroupMember: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                groupId: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["AddGroupMemberRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ApiResponseGroupView"];
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
    removeGroupGroup: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                groupId: string;
                innerGroupId: string;
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
                    "application/json": components["schemas"]["ApiResponseGroupView"];
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
    removeGroupPerson: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                groupId: string;
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
                content: {
                    "application/json": components["schemas"]["ApiResponseGroupView"];
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
    giveGroupPolicy: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                groupId: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["AssignPolicyRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ApiResponseGroupView"];
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
    takeGroupPolicy: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                groupId: string;
                policyId: string;
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
                    "application/json": components["schemas"]["ApiResponseGroupView"];
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
    listLicenceTypes_1: {
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
                    "application/json": components["schemas"]["ApiResponseListLicenceTypeItem"];
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
    getMemberAccess: {
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
                content: {
                    "application/json": components["schemas"]["ApiResponseMemberAccessView"];
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
    getMemberDataAccess: {
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
                content: {
                    "application/json": components["schemas"]["ApiResponseDataAccessView"];
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
    replaceMemberDataAccess: {
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
                "application/json": components["schemas"]["SaveDataAccessRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ApiResponseDataAccessView"];
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
    grantMemberAbility: {
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
                "application/json": components["schemas"]["GrantAbilityRequest"];
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
    revokeMemberAbility: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                membershipId: string;
                ability: string;
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
    giveMemberLicence: {
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
    assignMemberPolicy: {
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
                "application/json": components["schemas"]["AssignPolicyRequest"];
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
    unassignMemberPolicy: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                membershipId: string;
                policyId: string;
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
    setMemberProfile: {
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
                "application/json": components["schemas"]["AssignProfileRequest"];
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
    setMemberRole: {
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
                "application/json": components["schemas"]["AssignRoleRequest"];
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
    listApplicationDefinitions: {
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
                    "application/json": components["schemas"]["ApiResponseListApplicationView"];
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
    createApplication: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["CreateApplicationRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ApiResponseApplicationView"];
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
    getApplicationDefinition: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                applicationApiName: string;
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
                    "application/json": components["schemas"]["ApiResponseApplicationView"];
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
    updateApplication: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                applicationApiName: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["UpdateApplicationRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ApiResponseApplicationView"];
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
    deleteApplication: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                applicationApiName: string;
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
    listApprovalProcesses: {
        parameters: {
            query?: {
                object?: string;
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
                    "application/json": components["schemas"]["ApiResponseListApprovalProcessView"];
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
    getApprovalCatalogue: {
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
                    "application/json": components["schemas"]["ApiResponseApprovalCatalogueView"];
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
    getApprovalChoices: {
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
                    "application/json": components["schemas"]["ApiResponseApprovalChoicesView"];
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
    createApprovalProcess: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                objectApiName: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["CreateApprovalProcessRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ApiResponseApprovalProcessView"];
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
    checkApprovalProcess: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                objectApiName: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["CheckApprovalProcessRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ApiResponseApprovalProcessCheckView"];
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
    resolveApprovalProcess: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                objectApiName: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["ResolveApprovalRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ApiResponseApprovalResolutionView"];
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
    getApprovalProcess: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                objectApiName: string;
                processApiName: string;
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
                    "application/json": components["schemas"]["ApiResponseApprovalProcessView"];
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
    updateApprovalProcess: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                objectApiName: string;
                processApiName: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["UpdateApprovalProcessRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ApiResponseApprovalProcessView"];
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
    deleteApprovalProcess: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                objectApiName: string;
                processApiName: string;
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
    activateApprovalProcess: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                objectApiName: string;
                processApiName: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["ApprovalProcessActivationRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ApiResponseApprovalProcessView"];
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
    listChangeSets: {
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
                    "application/json": components["schemas"]["ApiResponseListChangeSetView"];
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
    createChangeSet: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["CreateChangeSetRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ApiResponseChangeSetView"];
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
    getChangeSet: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: string;
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
                    "application/json": components["schemas"]["ApiResponseChangeSetView"];
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
    discardChangeSet: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: string;
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
    addChange: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["ChangeRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ApiResponseChangeSetView"];
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
    removeChange: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: string;
                changeId: string;
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
                    "application/json": components["schemas"]["ApiResponseChangeSetView"];
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
    previewChangeSet: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: string;
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
                    "application/json": components["schemas"]["ApiResponseChangeSetReportView"];
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
    publishChangeSet: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: string;
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
                    "application/json": components["schemas"]["ApiResponseChangeSetView"];
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
    validateChangeSet: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: string;
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
                    "application/json": components["schemas"]["ApiResponseChangeSetReportView"];
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
    uploadCustomPageScript: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: {
            content: {
                "text/javascript": string;
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ApiResponseCustomPageAssetView"];
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
    listCustomPageDefinitions: {
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
                    "application/json": components["schemas"]["ApiResponseListCustomPageView"];
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
    createCustomPage: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["CreateCustomPageRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ApiResponseCustomPageView"];
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
    getCustomPageDefinition: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                apiName: string;
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
                    "application/json": components["schemas"]["ApiResponseCustomPageView"];
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
    updateCustomPage: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                apiName: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["UpdateCustomPageRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ApiResponseCustomPageView"];
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
    deleteCustomPage: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                apiName: string;
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
    checkExpression: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["CheckExpressionRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ApiResponseExpressionCheckView"];
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
    listExpressionFunctions: {
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
                    "application/json": components["schemas"]["ApiResponseListFunctionView"];
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
    testExpression: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["TestExpressionRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ApiResponseExpressionTestView"];
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
    listFieldTypes: {
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
                    "application/json": components["schemas"]["ApiResponseListFieldTypeView"];
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
    listInvalidStoredFormulas: {
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
                    "application/json": components["schemas"]["ApiResponseListFormulaReportItemView"];
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
    listNotificationTemplates: {
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
                    "application/json": components["schemas"]["ApiResponseNotificationTemplatesView"];
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
    createNotificationTemplate: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["CreateNotificationTemplateRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ApiResponseNotificationTemplateView"];
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
    checkNotificationTemplate: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["CheckNotificationTemplateRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ApiResponseNotificationTemplateCheckView"];
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
    updateNotificationTemplate: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                kind: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["UpdateNotificationTemplateRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ApiResponseNotificationTemplateView"];
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
    resetNotificationTemplate: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                kind: string;
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
    listObjects: {
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
                    "application/json": components["schemas"]["ApiResponseListObjectSummaryView"];
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
    createObject: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["CreateObjectRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ApiResponseObjectView"];
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
    getObject: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                objectApiName: string;
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
                    "application/json": components["schemas"]["ApiResponseObjectView"];
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
    updateObject: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                objectApiName: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["UpdateObjectRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ApiResponseObjectView"];
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
    deleteObject: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                objectApiName: string;
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
    getObjectAuthorship: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                objectApiName: string;
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
                    "application/json": components["schemas"]["ApiResponseListAuthorshipView"];
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
    createField: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                objectApiName: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["CreateFieldRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ApiResponseFieldView"];
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
    updateField: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                objectApiName: string;
                fieldApiName: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["UpdateFieldRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ApiResponseFieldView"];
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
    deleteField: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                objectApiName: string;
                fieldApiName: string;
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
    testFormulaField: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                objectApiName: string;
                fieldApiName: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["TestValuesRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ApiResponseExpressionTestView"];
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
    addFieldValue: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                objectApiName: string;
                fieldApiName: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["AddFieldValueRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ApiResponseAddedFieldValueView"];
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
    listLayoutAssignments: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                objectApiName: string;
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
                    "application/json": components["schemas"]["ApiResponseListLayoutAssignmentView"];
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
    createLayoutAssignment: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                objectApiName: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["CreateLayoutAssignmentRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ApiResponseLayoutAssignmentView"];
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
    getLayoutAssignment: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                objectApiName: string;
                key: string;
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
                    "application/json": components["schemas"]["ApiResponseLayoutAssignmentView"];
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
    updateLayoutAssignment: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                objectApiName: string;
                key: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["UpdateLayoutAssignmentRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ApiResponseLayoutAssignmentView"];
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
    deleteLayoutAssignment: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                objectApiName: string;
                key: string;
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
    resolveLayout: {
        parameters: {
            query?: {
                profileId?: string;
                recordType?: string;
            };
            header?: never;
            path: {
                objectApiName: string;
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
                    "application/json": components["schemas"]["ApiResponseLayoutResolutionView"];
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
    listLayouts: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                objectApiName: string;
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
                    "application/json": components["schemas"]["ApiResponseListLayoutView"];
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
    createLayout: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                objectApiName: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["CreateLayoutRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ApiResponseLayoutView"];
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
    getLayout: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                objectApiName: string;
                layoutApiName: string;
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
                    "application/json": components["schemas"]["ApiResponseLayoutView"];
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
    updateLayout: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                objectApiName: string;
                layoutApiName: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["UpdateLayoutRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ApiResponseLayoutView"];
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
    deleteLayout: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                objectApiName: string;
                layoutApiName: string;
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
    listRecordPageAssignments: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                objectApiName: string;
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
                    "application/json": components["schemas"]["ApiResponseListRecordPageAssignmentView"];
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
    createRecordPageAssignment: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                objectApiName: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["CreateRecordPageAssignmentRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ApiResponseRecordPageAssignmentView"];
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
    getRecordPageAssignment: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                objectApiName: string;
                key: string;
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
                    "application/json": components["schemas"]["ApiResponseRecordPageAssignmentView"];
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
    updateRecordPageAssignment: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                objectApiName: string;
                key: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["UpdateRecordPageAssignmentRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ApiResponseRecordPageAssignmentView"];
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
    deleteRecordPageAssignment: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                objectApiName: string;
                key: string;
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
    resolveRecordPage: {
        parameters: {
            query?: {
                profileId?: string;
                recordType?: string;
            };
            header?: never;
            path: {
                objectApiName: string;
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
                    "application/json": components["schemas"]["ApiResponseRecordPageResolutionView"];
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
    listRecordPages: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                objectApiName: string;
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
                    "application/json": components["schemas"]["ApiResponseListRecordPageView"];
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
    createRecordPage: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                objectApiName: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["CreateRecordPageRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ApiResponseRecordPageView"];
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
    getRecordPage: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                objectApiName: string;
                pageApiName: string;
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
                    "application/json": components["schemas"]["ApiResponseRecordPageView"];
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
    updateRecordPage: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                objectApiName: string;
                pageApiName: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["UpdateRecordPageRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ApiResponseRecordPageView"];
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
    deleteRecordPage: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                objectApiName: string;
                pageApiName: string;
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
    listRecordTypeAssignmentsOfObject: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                objectApiName: string;
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
                    "application/json": components["schemas"]["ApiResponseListRecordTypeAssignmentView"];
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
    listRecordTypes: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                objectApiName: string;
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
                    "application/json": components["schemas"]["ApiResponseListRecordTypeView"];
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
    createRecordType: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                objectApiName: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["CreateRecordTypeRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ApiResponseRecordTypeView"];
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
    getRecordType: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                objectApiName: string;
                recordTypeApiName: string;
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
                    "application/json": components["schemas"]["ApiResponseRecordTypeView"];
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
    updateRecordType: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                objectApiName: string;
                recordTypeApiName: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["UpdateRecordTypeRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ApiResponseRecordTypeView"];
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
    deleteRecordType: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                objectApiName: string;
                recordTypeApiName: string;
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
    listRecordTypeAssignments: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                objectApiName: string;
                recordType: string;
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
                    "application/json": components["schemas"]["ApiResponseListRecordTypeAssignmentView"];
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
    createRecordTypeAssignment: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                objectApiName: string;
                recordType: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["CreateRecordTypeAssignmentRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ApiResponseRecordTypeAssignmentView"];
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
    getRecordTypeAssignment: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                objectApiName: string;
                recordType: string;
                profileId: string;
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
                    "application/json": components["schemas"]["ApiResponseRecordTypeAssignmentView"];
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
    updateRecordTypeAssignment: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                objectApiName: string;
                recordType: string;
                profileId: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["UpdateRecordTypeAssignmentRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ApiResponseRecordTypeAssignmentView"];
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
    deleteRecordTypeAssignment: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                objectApiName: string;
                recordType: string;
                profileId: string;
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
    getObjectRelationships: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                objectApiName: string;
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
                    "application/json": components["schemas"]["ApiResponseObjectRelationshipsView"];
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
    listValidationRules: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                objectApiName: string;
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
                    "application/json": components["schemas"]["ApiResponseListValidationRuleView"];
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
    createValidationRule: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                objectApiName: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["CreateValidationRuleRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ApiResponseValidationRuleView"];
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
    getValidationRule: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                objectApiName: string;
                ruleApiName: string;
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
                    "application/json": components["schemas"]["ApiResponseValidationRuleView"];
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
    updateValidationRule: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                objectApiName: string;
                ruleApiName: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["UpdateValidationRuleRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ApiResponseValidationRuleView"];
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
    deleteValidationRule: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                objectApiName: string;
                ruleApiName: string;
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
    testValidationRule: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                objectApiName: string;
                ruleApiName: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["TestValuesRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ApiResponseExpressionTestView"];
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
    listReleases: {
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
                    "application/json": components["schemas"]["ApiResponseListReleaseView"];
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
    rollbackLatestRelease: {
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
                    "application/json": components["schemas"]["ApiResponseReleaseView"];
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
    checkRollback: {
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
                    "application/json": components["schemas"]["ApiResponseChangeSetReportView"];
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
    listTabDefinitions: {
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
                    "application/json": components["schemas"]["ApiResponseListTabView"];
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
    createTab: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["CreateTabRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ApiResponseTabView"];
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
    getTabDefinition: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                apiName: string;
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
                    "application/json": components["schemas"]["ApiResponseTabView"];
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
    updateTab: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                apiName: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["UpdateTabRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ApiResponseTabView"];
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
    deleteTab: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                apiName: string;
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
    listWorkflows: {
        parameters: {
            query?: {
                object?: string;
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
                    "application/json": components["schemas"]["ApiResponseListWorkflowView"];
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
    getWorkflowCatalogue: {
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
                    "application/json": components["schemas"]["ApiResponseWorkflowCatalogueView"];
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
    createWorkflow: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                objectApiName: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["CreateWorkflowRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ApiResponseWorkflowView"];
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
    checkWorkflowCondition: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                objectApiName: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["CheckWorkflowConditionRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ApiResponseWorkflowConditionCheckView"];
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
    getWorkflow: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                objectApiName: string;
                workflowApiName: string;
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
                    "application/json": components["schemas"]["ApiResponseWorkflowView"];
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
    updateWorkflow: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                objectApiName: string;
                workflowApiName: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["UpdateWorkflowRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ApiResponseWorkflowView"];
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
    deleteWorkflow: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                objectApiName: string;
                workflowApiName: string;
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
    activateWorkflow: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                objectApiName: string;
                workflowApiName: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["WorkflowActivationRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ApiResponseWorkflowView"];
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
    getNotificationPreferences: {
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
                    "application/json": components["schemas"]["ApiResponseNotificationPreferencesView"];
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
    updateNotificationPreferences: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["UpdateNotificationPreferencesRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ApiResponseNotificationPreferencesView"];
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
    listNotifications: {
        parameters: {
            query?: {
                unread?: boolean;
                limit?: number;
                cursor?: string;
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
                    "application/json": components["schemas"]["ApiPageResponseNotificationView"];
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
    markAllNotificationsRead: {
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
    getNotificationSummary: {
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
                    "application/json": components["schemas"]["ApiResponseNotificationSummaryView"];
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
    markNotificationRead: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: string;
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
    getObjectForm: {
        parameters: {
            query?: {
                recordType?: string;
                recordId?: string;
                layout?: string;
            };
            header?: never;
            path: {
                objectApiName: string;
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
                    "application/json": components["schemas"]["ApiResponseObjectFormView"];
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
    getFormulaRecalculation: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                objectApiName: string;
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
                    "application/json": components["schemas"]["ApiResponseFormulaRecalculationView"];
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
    startFormulaRecalculation: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                objectApiName: string;
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
                    "application/json": components["schemas"]["ApiResponseFormulaRecalculationView"];
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
    listImports: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                objectApiName: string;
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
                    "application/json": components["schemas"]["ApiResponseListImportJobView"];
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
    startImport: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                objectApiName: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["StartImportRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ApiResponseImportJobView"];
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
    getImport: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                objectApiName: string;
                importId: string;
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
                    "application/json": components["schemas"]["ApiResponseImportJobView"];
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
    cancelImport: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                objectApiName: string;
                importId: string;
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
                    "application/json": components["schemas"]["ApiResponseImportJobView"];
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
    downloadImportErrors: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                objectApiName: string;
                importId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description The report */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "text/csv": string;
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
    sendImportFile: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                objectApiName: string;
                importId: string;
            };
            cookie?: never;
        };
        requestBody?: {
            content: {
                "text/csv": string;
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ApiResponseImportJobView"];
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
    getObjectIndexStatus: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                objectApiName: string;
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
                    "application/json": components["schemas"]["ApiResponseObjectIndexStatusView"];
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
    getLookupLabels: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                objectApiName: string;
                fieldApiName: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["LookupLabelsRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ApiResponseLookupLabelsView"];
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
    getLookupOptions: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                objectApiName: string;
                fieldApiName: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["LookupOptionsRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ApiResponseListLookupOptionView"];
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
    getRecordPage_1: {
        parameters: {
            query?: {
                recordType?: string;
                recordId?: string;
            };
            header?: never;
            path: {
                objectApiName: string;
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
                    "application/json": components["schemas"]["ApiResponseRecordPageDescriptorView"];
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
    listRecords: {
        parameters: {
            query?: {
                limit?: number;
                cursor?: string;
            };
            header?: never;
            path: {
                objectApiName: string;
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
                    "application/json": components["schemas"]["ApiPageResponseRecordView"];
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
    createRecord: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                objectApiName: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["CreateRecordRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ApiResponseRecordView"];
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
    bulkRecords: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                objectApiName: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["BulkRecordsRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ApiResponseBulkRecordsResponse"];
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
    queryRecords: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                objectApiName: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["QueryRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ApiPageResponseQueriedRecordView"];
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
    searchRecords: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                objectApiName: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["SearchRecordsRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ApiPageResponseQueriedRecordView"];
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
    validateRecord: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                objectApiName: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["ValidateRecordRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ApiResponseRecordCheckView"];
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
    getRecord: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                objectApiName: string;
                id: string;
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
                    "application/json": components["schemas"]["ApiResponseRecordView"];
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
    deleteRecord: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                objectApiName: string;
                id: string;
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
    updateRecord: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                objectApiName: string;
                id: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["UpdateRecordRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ApiResponseRecordView"];
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
    getRecordApproval: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                objectApiName: string;
                id: string;
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
                    "application/json": components["schemas"]["ApiResponseRecordApprovalView"];
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
    submitRecordForApproval: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                objectApiName: string;
                id: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["SubmitApprovalRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ApiResponseApprovalInstanceView"];
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
    listRecordFiles: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                objectApiName: string;
                id: string;
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
                    "application/json": components["schemas"]["ApiResponseListRecordFileView"];
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
    uploadRecordFile: {
        parameters: {
            query?: never;
            header: {
                "X-File-Name": string;
            };
            path: {
                objectApiName: string;
                id: string;
            };
            cookie?: never;
        };
        requestBody?: {
            content: {
                "application/octet-stream": string;
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ApiResponseRecordFileView"];
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
    deleteRecordFile: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                objectApiName: string;
                id: string;
                fileId: string;
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
    downloadRecordFile: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                objectApiName: string;
                id: string;
                fileId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description The bytes of the file */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/octet-stream": string;
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
    changeRecordOwner: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                objectApiName: string;
                id: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["ChangeOwnerRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ApiResponseRecordView"];
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
    listRecordShareTargets: {
        parameters: {
            query: {
                kind: string;
                search?: string;
            };
            header?: never;
            path: {
                objectApiName: string;
                id: string;
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
                    "application/json": components["schemas"]["ApiResponseListShareTargetView"];
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
    listRecordShares: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                objectApiName: string;
                id: string;
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
                    "application/json": components["schemas"]["ApiResponseListRecordShareView"];
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
    shareRecord: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                objectApiName: string;
                id: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["ShareRecordRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ApiResponseRecordShareView"];
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
    unshareRecord: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                objectApiName: string;
                id: string;
                granteeId: string;
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
    listDeletedRecords: {
        parameters: {
            query?: {
                limit?: number;
                cursor?: string;
            };
            header?: never;
            path: {
                objectApiName: string;
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
                    "application/json": components["schemas"]["ApiPageResponseRecordView"];
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
    purgeRecord: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                objectApiName: string;
                id: string;
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
    restoreRecord: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                objectApiName: string;
                id: string;
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
                    "application/json": components["schemas"]["ApiResponseRecordView"];
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
    listListViews: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                objectApiName: string;
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
                    "application/json": components["schemas"]["ApiResponseListListViewView"];
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
    createListView: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                objectApiName: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["CreateListViewRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ApiResponseListViewView"];
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
    updateListView: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                objectApiName: string;
                viewId: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["UpdateListViewRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ApiResponseListViewView"];
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
    deleteListView: {
        parameters: {
            query: {
                version: number;
            };
            header?: never;
            path: {
                objectApiName: string;
                viewId: string;
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
    leaveOrganization: {
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
    listPlatformAuditEvents: {
        parameters: {
            query?: {
                limit?: number;
                cursor?: string;
                from?: string;
                to?: string;
                actor?: string;
                kind?: string;
                target?: string;
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
                    "application/json": components["schemas"]["ApiPageResponseAuditEventView"];
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
    getPlatformAutomationHealth: {
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
                    "application/json": components["schemas"]["ApiResponseAutomationHealthView"];
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
                    "application/json": components["schemas"]["ApiResponseListLicenceTypeItem"];
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
                "application/json": components["schemas"]["AddLicenceTypeRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ApiResponseLicenceTypeItem"];
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
    listProfiles: {
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
                    "application/json": components["schemas"]["ApiResponseListProfileView"];
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
    createProfile: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["SaveProfileRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ApiResponseProfileView"];
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
    updateProfile: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                profileId: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["SaveProfileRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ApiResponseProfileView"];
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
    deleteProfile: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                profileId: string;
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
    getProfileDataAccess: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                profileId: string;
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
                    "application/json": components["schemas"]["ApiResponseDataAccessView"];
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
    replaceProfileDataAccess: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                profileId: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["SaveDataAccessRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ApiResponseDataAccessView"];
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
    makeProfileDefault: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                profileId: string;
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
    listRoles: {
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
                    "application/json": components["schemas"]["ApiResponseListRoleView"];
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
    createRole: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["SaveRoleRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ApiResponseRoleView"];
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
    updateRole: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                roleId: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["SaveRoleRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ApiResponseRoleView"];
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
    deleteRole: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                roleId: string;
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
    listObjectSharing: {
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
                    "application/json": components["schemas"]["ApiResponseListObjectSharingView"];
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
    getObjectSharing: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                objectApiName: string;
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
                    "application/json": components["schemas"]["ApiResponseObjectSharingView"];
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
    setObjectSharing: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                objectApiName: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["SaveObjectSharingRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ApiResponseObjectSharingView"];
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
    listSharingRules: {
        parameters: {
            query?: {
                object?: string;
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
                    "application/json": components["schemas"]["ApiResponseListSharingRuleView"];
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
    createSharingRule: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["SaveSharingRuleRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ApiResponseSharingRuleView"];
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
    getSharingRule: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                ruleId: string;
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
                    "application/json": components["schemas"]["ApiResponseSharingRuleView"];
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
    updateSharingRule: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                ruleId: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["SaveSharingRuleRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ApiResponseSharingRuleView"];
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
    deleteSharingRule: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                ruleId: string;
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
    listSheetVersions: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                ownerObject: string;
                ownerId: string;
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
                    "application/json": components["schemas"]["ApiResponseSheetVersionsView"];
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
    createSheetVersion: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                ownerObject: string;
                ownerId: string;
            };
            cookie?: never;
        };
        requestBody?: {
            content: {
                "application/json": components["schemas"]["NewSheetVersionRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ApiResponseSheetVersionSummaryView"];
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
    getSheetVersion: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                ownerObject: string;
                ownerId: string;
                versionNo: number;
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
                    "application/json": components["schemas"]["ApiResponseSheetVersionView"];
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
    saveSheetGrid: {
        parameters: {
            query: {
                version: number;
            };
            header?: never;
            path: {
                ownerObject: string;
                ownerId: string;
                versionNo: number;
            };
            cookie?: never;
        };
        requestBody?: {
            content: {
                "application/json": components["schemas"]["SheetGrid"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ApiResponseSheetVersionSummaryView"];
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
    listWorkflowExecutions: {
        parameters: {
            query?: {
                limit?: number;
                cursor?: string;
                workflow?: string;
                object?: string;
                status?: string;
                trigger?: string;
                dead?: boolean;
                record?: string;
                from?: string;
                to?: string;
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
                    "application/json": components["schemas"]["ApiPageResponseWorkflowExecutionView"];
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
    getWorkflowDeadLetterSummary: {
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
                    "application/json": components["schemas"]["ApiResponseWorkflowDeadLetterSummaryView"];
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
    replayWorkflowExecutions: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["WorkflowBulkReplayRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ApiResponseWorkflowBulkReplayView"];
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
    getWorkflowExecution: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                executionId: string;
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
                    "application/json": components["schemas"]["ApiResponseWorkflowExecutionView"];
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
    cancelWorkflowExecution: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                executionId: string;
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
                    "application/json": components["schemas"]["ApiResponseWorkflowExecutionView"];
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
    replayWorkflowExecution: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                executionId: string;
            };
            cookie?: never;
        };
        requestBody?: {
            content: {
                "application/json": components["schemas"]["WorkflowReplayRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ApiResponseWorkflowReplayResultView"];
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
    listWorkflowParkedEvents: {
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
                    "application/json": components["schemas"]["ApiResponseListParkedEventView"];
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
    redriveWorkflowParkedEvent: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                eventId: string;
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
}
