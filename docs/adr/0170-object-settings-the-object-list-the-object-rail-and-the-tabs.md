# ADR-0170: Object settings: the object list, the object's rail and the tabs

- **Status:** Accepted
- **Date:** 2026-10-20
- **Sprint:** UA3 (UI Alignment track, not a numbered sprint)
- **Related:** ADR-0058 to ADR-0062 (objects and fields), ADR-0063 (relationships), ADR-0064 (record types), ADR-0069 to ADR-0071 (layouts),
  ADR-0098 (record type access), ADR-0115 to ADR-0118 (sharing), ADR-0131 (record pages), ADR-0132 (builders and state), ADR-0167 (the shell
  and the rail), ADR-0168 (list and detail pattern), ADR-0169 (who made a definition)

## Context

The page of one object was a single component of 380 lines that drew a heading with a description paragraph, a row of section links and, below
them, a stack of panels with a table and an always-open form each. UA1 and UA2 gave the other Home screens a rail, a title band, a list and a
page per row. The user decided that Object settings follows the same pattern on every tab, with the object's own rail, and that the extra
descriptions go. Every function, address and test of S10 to S21 stays.

## Decision

1. **The list of objects** (`/setup/objects`, `ObjectsPanel`) is a `ListPage` titled "Object settings": label, API name, a Standard or Custom
   tag, a search box and **New**, which opens the create form (name, label, plural label, description) as a page. A row opens the object. The list
   has no rail.
2. **One object, one frame** (`ObjectFrame`). It reads the object, the field types and the list of objects once, hands them to the tab as
   `ObjectTabProps` (with `reload`, a `refreshToken` bumped after each read and the authorship) and draws the tab the address names
   (`?section=`). The description paragraph, the repeated name and the "defined by the platform" paragraph are gone. A fact that was in them
   (the object's type, the module that manages its records) moved to the Details tab.
3. **The object's rail is the shared `SideRail`**, named after the object, with the order Fields, Relationships, Record types, Validation rules,
   a group **Pages** (Page layouts, Record pages, Custom buttons), Sharing, Details. `objectRail` in `lib/shell/areas.ts` is the one place that
   lists them. Custom buttons is drawn as a name with "Soon" and **no link at all** (`unavailable`). An entry is current by the query
   (`isCurrent` and `RailLink.matches` take the query as a second argument), and the two builders keep their tab current.
4. **The page tells the shell the object's name** (`usePublishOpenObject` / `useOpenObject`, a tiny external store): the object page and the two
   builders publish it, the rail reads it, and until it is known the rail carries the API name. The shell asks the API for nothing of its own.
5. **Every tab is a list, a page behind each row and a form behind New**, with the breadcrumb "Object settings / Account / Fields" and the title
   "Account — Fields". The open row lives in the address next to the tab, under a name that says what it is: `?section=fields&field=<name>`,
   `recordType=`, `rule=`, `?new=1` for a form. `useListRoute` takes `openParam` and `keep` for this, so two tabs never read each other's row and
   the tab survives opening and closing a row. Addresses of S10 to S21 are unchanged; the new parameters are additions.

   | Tab | List | A row opens | New opens |
   |---|---|---|---|
   | Fields | label, API name, type | the field's page: details, flags, the settings of its type, who made it; Edit; Remove | `FieldForm` |
   | Relationships | name, related object, through, kind, behaviour, direction | (read only) | none |
   | Record types | record type, layout, status | the type's page with the profiles that use it (ADR-0098) as a section | the form |
   | Validation rules | rule, API name, status | the rule's page; switch on or off; Edit with the test box | the form |
   | Page layouts | name, API name, sections, created by, modified by; Preview; Remove | the builder (its own address) | the builder, `/new` |
   | Record pages | record page, assigned to, status; Preview; Remove | the builder (its own address) | the builder, `/new` |
   | Sharing | rule, records, shared with, access, type; the default access above | the rule's page | the form |
   | Details | (one page) labels, type, who made it; Edit; Remove the object | | |

   Layout assignments and record page assignments are behind a button of their list (`?view=assignments`), as a page with the way back.
6. **The server still decides.** A button is drawn when the API's flags allow it (`editable`, `extensible`) and, for the ability, when the API's
   list of the member's abilities does not rule it out (`useOffers`); the API checks again and its words are shown, with the place that is wrong.
   The browser lists no field type and no rule.
7. **The change-set selector and every change-set branch stay.** Every create, change and remove still goes into the chosen change set when one
   is chosen (`addToSet`), on every tab; sharing stays live and says so.
8. **A removal asks once.** The page of a field, a record type, a validation rule or a sharing rule asks "Yes, remove" before it removes (the list
   rows of layouts and record pages keep the quiet button, as ADR-0168). The API still refuses a removal something depends on and names it.
9. **Words.** The product keeps "API name" (the permission key, ADR-0058) and does not adopt the mockup's "Integration name"; they mean the same.

## Consequences

- `ObjectDetailPanel` and `objectSections.ts` are gone; `FieldsPanel`, `ObjectDetailsPanel`, `ObjectFrame` and `authorship` are new; the other
  panels were rewritten as lists with a page and a form. `RecordTypeAccessPanel` shows one record type's profiles instead of a grid of all.
- Frontend only apart from ADR-0169's read endpoint. No migration, ability or module edge changed.
- Not run by the assistant: the production build and a browser (see `Sprint UA3 manual steps.md`).
