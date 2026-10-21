# ADR-0171: Sign in once on the platform address and enter the organization

- **Status:** Accepted
- **Date:** 2026-10-20
- **Sprint:** UA4 (UI Alignment track, not a numbered sprint), frontend only
- **Related:** ADR-0014 and ADR-0019 (the address names the organization), ADR-0026 to ADR-0029 (membership, the list of a person's organizations, the
  one-time switch), ADR-0030 to ADR-0038 (platform roles, the console), ADR-0167 (the shell and where people land)

## Context

Everything of the intended way in was already built: the platform administrator sets up an organization and invites its first administrator, the
administrator creates the other members, each person chooses a password from a mailed link, and from then on signs in with an e-mail address and a
password. The sign-in page has no organization field, and must not get one: the address of the page tells the server which organization.

What was missing was the step after signing in on the **platform address**. The person landed on a developer-style welcome page. A person with one
organization had no link into it (the switcher shows only with two or more) and had to type its address.

## Decision

1. **Right after signing in on the platform address** (`PlatformLanding`): a person who holds a platform role goes to the console; a person with
   exactly one organization goes in through the one-time handoff of the switcher (`beginSwitch`, ADR-0029) and sees "Opening your organization…";
   a person with several chooses from a list ("Choose your organization", one button per organization); a person with none sees a plain page saying
   they are not a member of an organization yet and that an administrator invites people.
2. **The list and the handoff come only from the API** (`GET /api/v1/organizations`, `POST /api/v1/auth/switch`). The browser composes no
   organization address and decides nothing about membership; a refused handoff is shown in the API's words, the list stays so the person can press
   again, and nothing is retried by itself, so there is no loop.
3. **"Just signed in" is a marker on the return path, not stored state.** Signing in ends in full navigations, so no page state survives it, and the
   prompt's wish (page state, not the address) cannot be met literally. The sign-in *page* (not the form, whose fields and words are untouched) turns
   a return to `/` into `/?signedIn=1`; the landing reads it once, removes it from the address (`history.replaceState`, no new history entry) and
   keeps the reading in a ref so development's double effect does not lose it. A reload, a bookmark or a later deliberate visit to `/` carries no
   marker and enters nothing. A deeper page the person asked for (`continue=`) is returned to untouched. The marker grants nothing: all it can do is
   make the landing open the organization the person just signed in to reach.
4. **A deliberate visit** to the platform address by a signed-in person shows the same list (even with one organization) and, for a platform role,
   a link to the console; it never bounces the person back in.
5. **"Create an organization" moves into the person's menu** (`UserMenu`), shown on the platform address to a signed-in person. The page
   `/organizations/new` and the limit of three (enforced by the API) are unchanged. The developer-style welcome text and the status card are gone
   (the card had no other user and was removed).
6. **The sign-in form itself sits on the landing page** for a signed-out visitor (`Welcome`, embedding `SignInForm` unchanged): one click fewer
   than going to `/sign-in` first (user request, 2026-10-21). `/sign-in` still exists and still works — as a deep link, and because the server's
   failed-OAuth-return redirect is hardcoded to it (`AuthFlow.SIGN_IN_PAGE`, a backend constant, left alone). `HomeActions` (the old links-only
   component) had nothing left to show once the form itself moved here and was removed.
7. **The chooser is a state of the landing (`/`)**, not a new address: the person has no organization context yet and the strip belongs to one.
8. **The invitation page** now ends, for a new account, with a link to the platform's sign-in, so the person lands in the organization through this
   flow instead of signing in a second time on the organization's address. That address still works for anybody who has the old link.
9. **No memory of a last organization** and no cookie that remembers a choice.

## How this can fail, and what was done about it

- *A loop between the two addresses:* the handoff is requested once (a ref guards the effect), the marker is removed before the request, and a failure
  shows the words and stops. The destination's landing is an organization landing, which never goes back to the platform address.
- *A refused handoff* (membership ended, organization suspended or still opening): the API's answer on the platform address, or on the destination
  host, is shown in its own words; this ADR adds no rule of its own.
- *The proof expires while the person reads:* the proof lives 60 seconds and is requested at the moment of entering, not when the list is drawn; a
  stale one is refused by the destination in the API's words and the person signs in again.
- *A session that ends between the sign-in and the landing:* the marker is read and removed, the visitor sees the short welcome and nothing is entered.

## Consequences

- No API, migration or ability changed. New: `PlatformLanding`, `useOrganizations`, `signedInMarker`. Changed: `Landing`, `HomeActions`, `UserMenu`,
  the sign-in page, `InvitationAcceptance`. `PlatformStatusCard` was removed.
- The server-side redirect after sign-in carries a query string already (the server accepts any path on this site), so no backend change was needed.
