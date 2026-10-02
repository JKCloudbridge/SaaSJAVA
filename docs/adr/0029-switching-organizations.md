# ADR-0029: Switching organizations: a one-time proof carried to the other host

- **Status:** Accepted
- **Date:** 2026-10-02
- **Sprint:** S5
- **Related:** [ADR-0014](0014-tenancy-model-and-tenant-context.md), [ADR-0019](0019-authentication-implementation.md),
  [ADR-0026](0026-membership-lifecycle-and-the-administrator-marker.md), [ADR-0027](0027-which-organizations-does-a-person-belong-to.md)

## Context

Sessions and cookies belong to a host, and a token is bound to the host it was issued on (Sprint 3). A person in two organizations
is therefore signed in on each host separately, and "switching" means arriving on the other host signed in, without the tenant ever
travelling in a request body and without the person typing a password again.

## Decision

1. **The list** (`GET /api/v1/organizations`, any host): the organizations the caller is an active member of, as the server sees
   them ([ADR-0027](0027-which-organizations-does-a-person-belong-to.md)), each with the host the **server** built from the
   platform domain and the port of the request. The browser composes no organization address.
2. **Step one, on the host the person is signed in on** (`POST /api/v1/auth/switch`, body: the short name from the list): the
   server checks it against the caller's own memberships. An unknown organization and one the caller does not belong to give the
   **same** `404`. If it is theirs, the server stores a **one-time proof** (32 random bytes, only the SHA-256 hash is stored, table
   `organization_handoff`, platform-level because it exists to start a session on another host; its tenant column is named
   `bound_tenant_id`), valid **60 seconds**, bound to the person and to that one organization, and answers `{host, token}`.
   The short name is a *destination*, not the request's tenant: the tenant of this call is still the host's, and a forged tenant
   in a header or body changes nothing (tested).
3. **Step two, on the destination host** (`POST /api/v1/auth/switch/complete`, public, body: the proof): the browser reaches the
   destination at `/switch` with the proof after the `#` (never seen by a server or a log), the page sends it in the body. The
   host decides the organization; the proof must have been made for exactly that organization; it must be unused and unexpired
   (consumed with a conditional update: six simultaneous uses, one winner); the person must still be an active member; then a
   login session **bound to this host** is created and set as the short-lived login cookie, and the page continues with the usual
   authorization-code navigation, which gives this host its own cookies. Any proof that cannot be used (unknown, used, expired,
   for another organization, the person left) gets one answer: `400`, field `token`. A proof presented on the wrong host is
   refused without being used up.
4. **Limits:** 20 requests for a proof a minute per person; the shared 20 token attempts per 10 minutes per source for step two.
5. **What the person sees:** choosing an organization in the header's list takes them to the other organization signed in, with
   a short "Opening the organization…" page in between; the first organization's session is untouched. An existing person who
   accepts an invitation is offered the same step ("Open <organization>").

## Alternatives considered

- **Signing in again on the other host.** Simplest, and kept as the fall-back, but the person has already proven who they are.
- **A session cookie valid for the whole platform domain.** Would make a cookie of one organization readable by every other
  organization's host and breaks the host-bound token rule of Sprint 3.
- **Putting the proof in the query string.** It would appear in access logs and `Referer` headers; the fragment does not.

## Verification

`SwitchOrganizationIT` (10 tests), `SwitchArrival.test.tsx` (including a development-mode double effect),
`OrganizationSwitcher.test.tsx`, `MembershipFlowsLogsAreCleanIT`, `EndpointExposureIT`.
