# ADR-0073: Applications, navigation and the member's menu

- **Status:** Accepted
- **Date:** 2026-10-11
- **Sprint:** S12
- **Related:** [ADR-0069](0069-layouts-record-pages-applications-and-where-they-live.md), [ADR-0070](0070-definitions-are-json-checked-by-a-schema.md), architecture note 3 sections 14 and 15

## Decision

1. **What an application has (question 1, accepted):** a name (API name), a label, a description, an **icon key**, the **profiles that see it** and an **ordered list of navigation items**, and nothing
   else now. Items: an object (its list page), a record page, a link inside the organization, a heading. Nested menus, per-item visibility and permission filtering are not part of this sprint.
2. **Which applications a person sees = their profile.** An application lists profiles; its members see it when it lists their profile, or when it lists **none** (then every member sees it, so a new
   application is not invisible). The profile decides what is **offered**, not what is **allowed**: it is not an ability and not a marker (the decision of [ADR-0039](0039-abilities-profiles-access-policies-and-individual-grants.md)
   stands); every page a link opens is still decided by its own permissions. Visibility by access policy can come later as an addition; nothing stored here prevents it.
3. **`GET /api/v1/applications`** (module `application`, any active member, organization host only, platform host answers 404) returns the member's applications with the menu **resolved by the
   server**: the label of an object is its current plural label, a record page its current label, an item that points to something that no longer exists is left out (a second line of defence; the
   graph keeps it from being published). The member is the one the host and the token name; the request names nobody. The definitions with their profiles are `GET /api/v1/metadata/applications`
   for those who may view metadata.
4. **The browser hardcodes none of it** (note 3, section 15). `ApplicationMenu` in the shell draws the answer. What stays hardcoded in `ShellNav` is the **platform's own pages** (Home, Console, Members,
   Audit trail, Setup): they are the same for every organization and are not metadata. An object or record page entry is drawn as a **label, not a link**, because there is no list page or record page to
   open before the data engine (Milestone 4); links are drawn as links. The icon key is carried in `data-icon`; drawing icons is for the application launcher of Sprint 18.
5. **Not done here: showing only what the member may read.** The menu does not ask `Decisions` about each object. Security-aware navigation is Sprint 18 (traceability: "Dynamic navigation,
   security-aware UI"); the sprint 12 menu must not be mistaken for an access check.
6. **Deleting a profile** that an application lists is not blocked and the application is not changed; nobody holds the profile, so nobody is shown it by that entry. If it lists only deleted profiles it
   is shown to nobody until an administrator edits it.

## Consequences

- The cost of a menu is the metadata snapshot (cached by version, [ADR-0074](0074-definitions-the-version-counter-and-the-cache.md)) and one read of the member's profile.
