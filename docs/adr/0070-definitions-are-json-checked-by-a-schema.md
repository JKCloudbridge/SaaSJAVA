# ADR-0070: Definitions are JSON, checked against a schema that is published with the API

- **Status:** Accepted
- **Date:** 2026-10-11
- **Sprint:** S12
- **Related:** [ADR-0069](0069-layouts-record-pages-applications-and-where-they-live.md), [ADR-0011](0011-api-conventions.md), architecture note 3 section 25 (the shared metadata contract)

## Context

The exit criterion of Sprint 12 is that layout and record-page metadata are **validated against a JSON schema and round-trip through the API**, with invalid shapes refused by a precise
error. Note 3 (section 25) wants one contract that the Java side enforces and the TypeScript side is generated from. The standing rule is that **no executable text from an organization is
ever run**: the conditional visibility of a component is data written in a closed structure now; the expression engine is Sprint 13.

## Decision

1. **One text is the source of truth**: `platform-api-contract/src/main/resources/schemas/metadata-definitions.json` holds the schemas `PageLayoutDefinition`, `RecordPageDefinition` and
   `ApplicationDefinition` (and the pieces they use) as JSON Schema 2020-12, with references written as `#/components/schemas/<Name>`.
   - The application loads it and checks **every definition on every save and in the check step of a change set** (a change in a set is applied by the same service, so it is the same
     check).
   - The OpenAPI document gets the very same schemas in `components.schemas`, and the `definition` of every request and view points at them, so the generated TypeScript client has the
     same types. Nothing can drift: there is no second description to keep equal.
2. **The library.** A new dependency, a JSON schema validation library (`json-schema-validator`, version in the parent POM, with a reason next to it). Why: it implements the 2020-12 keywords the
   schemas use (`if`/`then`, `oneOf`, `additionalProperties`, `uniqueItems`), it is built on **Jackson 3**, the line the application already uses, and it brings no other runtime library.
   The existing JSON support (Jackson) reads and writes JSON but does not validate against a schema; writing a validator ourselves would be code to keep correct for no benefit.
3. **The error names the place.** A definition that does not fit is refused with `VALIDATION_ERROR` and one entry per problem under its path, for example
   `definition.sections[1].fields[0].behaviour: Must be one of: EDITABLE, READ_ONLY, REQUIRED, HIDDEN.` Texts are our own plain words (not the library's) and never contain what the
   person typed. At most 20 problems are listed.
4. **Every property is declared up front; the type of a component or item only requires or forbids properties.** A property of another type (`columns` on a details component) is refused at
   its own place. (Declaring them inside the branch of the type would make a failing branch report every other property as unknown.)
5. **What a schema cannot say is checked next to it** (`DefinitionRules`): a field appears once in a layout, a component key is unique in a page (also inside tabs), at most one header, an action
   is listed once, and an ability named by a rule exists in the catalogue.
6. **Closed visibility rules (question 4, accepted).** A component or an action may carry `visibleWhen`:
   - a **condition on a field**: `EQUALS`, `NOT_EQUALS` (with a value: text, number or true/false, at most 255 characters), `IS_EMPTY`, `IS_NOT_EMPTY` (no value);
   - **the person holds an ability** (`{"ability": "members.view"}`);
   - **`all` (AND) or `any` (OR)** of such conditions, nested **one more level** (a group may contain conditions and groups of conditions, not groups of groups of groups).
   Nothing else: no free text, no field of another object, no function. The browser reads a rule aloud and stores it; nobody evaluates it before Sprint 13, and Sprint 13 may add to the list.
7. **Components are a closed list**: `HEADER`, `DETAILS`, `RELATED_LIST`, `TABS`, `ACTIVITY`, `HISTORY`; a tab holds anything but tabs. Actions are `EDIT`, `DELETE`, `CLONE`.
   Navigation items are `OBJECT`, `RECORD_PAGE`, `LINK`, `HEADING`; a **link is a path inside the organization** (`/members`), matching `^/[A-Za-z0-9_-]+(/[A-Za-z0-9_-]+)*$`: no host, no
   scheme, no query, no `..`, so an organization can never put an address elsewhere into the menu.

## Consequences

- A change of the shape is a change of that one file, then `scripts/update-openapi` and `npm run api:generate`; the contract test fails when the committed document is stale.
- The stored `jsonb` is what the schema accepted. Reading it back returns the same data (jsonb may reorder the keys of an object; arrays keep their order), which is what "round trip" means.
- `DefinitionSchemasTest` is the proof of the schemas without a database; the integration tests prove the round trip through the API.
