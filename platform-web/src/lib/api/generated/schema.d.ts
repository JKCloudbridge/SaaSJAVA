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
            /** Format: int32 */
            members?: number;
            name: string;
            requiredLicenceType?: string;
        };
        AddCatalogueItemRequest: {
            key: string;
            name: string;
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
        ApiPageResponseAuditEventView: {
            data: components["schemas"]["AuditEventView"][];
            pagination: components["schemas"]["Pagination"];
        };
        ApiPageResponsePlatformOrganizationSummary: {
            data: components["schemas"]["PlatformOrganizationSummary"][];
            pagination: components["schemas"]["Pagination"];
        };
        ApiResponseAccessPolicyView: {
            data: components["schemas"]["AccessPolicyView"];
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
        ApiResponseDataAccessView: {
            data: components["schemas"]["DataAccessView"];
        };
        ApiResponseDataCatalogue: {
            data: components["schemas"]["DataCatalogue"];
        };
        ApiResponseFieldView: {
            data: components["schemas"]["FieldView"];
        };
        ApiResponseGroupView: {
            data: components["schemas"]["GroupView"];
        };
        ApiResponseInvitationAccepted: {
            data: components["schemas"]["InvitationAccepted"];
        };
        ApiResponseInvitationPreview: {
            data: components["schemas"]["InvitationPreview"];
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
        ApiResponseListCatalogueItem: {
            data: components["schemas"]["CatalogueItem"][];
        };
        ApiResponseListChangeSetView: {
            data: components["schemas"]["ChangeSetView"][];
        };
        ApiResponseListFieldTypeView: {
            data: components["schemas"]["FieldTypeView"][];
        };
        ApiResponseListGroupView: {
            data: components["schemas"]["GroupView"][];
        };
        ApiResponseListInvitationView: {
            data: components["schemas"]["InvitationView"][];
        };
        ApiResponseListLicencePoolView: {
            data: components["schemas"]["LicencePoolView"][];
        };
        ApiResponseListLicenceTypeItem: {
            data: components["schemas"]["LicenceTypeItem"][];
        };
        ApiResponseListMemberView: {
            data: components["schemas"]["MemberView"][];
        };
        ApiResponseListObjectSummaryView: {
            data: components["schemas"]["ObjectSummaryView"][];
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
        ApiResponseListProfileView: {
            data: components["schemas"]["ProfileView"][];
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
        ApiResponseListSupportAccessView: {
            data: components["schemas"]["SupportAccessView"][];
        };
        ApiResponseMemberAccessView: {
            data: components["schemas"]["MemberAccessView"];
        };
        ApiResponseObjectRelationshipsView: {
            data: components["schemas"]["ObjectRelationshipsView"];
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
        ApiResponseRecordTypeView: {
            data: components["schemas"]["RecordTypeView"];
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
        CatalogueItem: {
            key: string;
            name: string;
        };
        ChangePasswordRequest: {
            currentPassword: string;
            newPassword: string;
        };
        ChangeRequest: {
            createField?: components["schemas"]["CreateFieldRequest"];
            createObject?: components["schemas"]["CreateObjectRequest"];
            createRecordType?: components["schemas"]["CreateRecordTypeRequest"];
            itemApiName?: string;
            kind: string;
            objectApiName: string;
            updateField?: components["schemas"]["UpdateFieldRequest"];
            updateObject?: components["schemas"]["UpdateObjectRequest"];
            updateRecordType?: components["schemas"]["UpdateRecordTypeRequest"];
        };
        ChangeSetReportView: {
            items: components["schemas"]["ReleaseItemView"][];
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
        CompleteSignUpRequest: {
            displayName: string;
            password: string;
            token: string;
        };
        CompleteSwitchRequest: {
            token: string;
        };
        CreateChangeSetRequest: {
            description?: string;
            name: string;
        };
        CreateFieldRequest: {
            defaultValue?: string;
            description?: string;
            label: string;
            name: string;
            required?: boolean;
            settings?: components["schemas"]["FieldSettings"];
            type: string;
            unique?: boolean;
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
        CreateRecordTypeRequest: {
            active?: boolean;
            availableFields?: string[];
            defaultType?: boolean;
            description?: string;
            label: string;
            name: string;
            picklistSubsets?: components["schemas"]["PicklistSubset"][];
        };
        CurrentUser: {
            abilities: string[];
            displayName: string;
            email: string;
            id: string;
            platformRoles: string[];
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
        FieldSettings: {
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
        FieldView: {
            apiName: string;
            defaultValue?: string;
            description: string;
            editable: boolean;
            kind: string;
            label: string;
            required: boolean;
            retired: boolean;
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
        ObjectRelationshipsView: {
            children: components["schemas"]["RelationshipView"][];
            manyToMany: components["schemas"]["RelationshipView"][];
            parents: components["schemas"]["RelationshipView"][];
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
        Pagination: {
            hasMore: boolean;
            /** Format: int32 */
            limit: number;
            nextCursor?: string;
        };
        PermissionEntry: {
            actions: string[];
            key: string;
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
        ReasonRequest: {
            reason: string;
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
        UpdateFieldRequest: {
            defaultValue?: string;
            description?: string;
            label: string;
            required?: boolean;
            settings?: components["schemas"]["FieldSettings"];
            unique?: boolean;
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
        UpdateRecordTypeRequest: {
            active?: boolean;
            availableFields?: string[];
            defaultType?: boolean;
            description?: string;
            label: string;
            picklistSubsets?: components["schemas"]["PicklistSubset"][];
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
