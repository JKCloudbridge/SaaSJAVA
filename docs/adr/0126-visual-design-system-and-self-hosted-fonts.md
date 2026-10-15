# ADR-0126: The visual design system and self-hosted fonts

- **Status:** Accepted
- **Date:** 2026-10-15
- **Sprint:** S12A (side sprint, visual design)
- **Related:** the coding standard "literal colours and sizes only in `tokens.css`" (engineering guide), ADR-0011 (the frontend only presents what the API answers)

## Context

Every screen from Sprint 3 to Sprint 17A was built as the smallest slice that proved its backend contract, so most of them rendered close to
unstyled HTML. The approved visual design (an interactive prototype handed over on 2026-10-15) specifies one look for the whole product: a
calm teal accent on cool neutral surfaces, rounded shapes, two typefaces and light and dark themes. It shows no disabled state and no error state.

## Decision

1. **`tokens.css` stays the only place for colours, sizes and type; role names stay, values change.** The design's neutrals map onto the
   existing roles. Roles that were genuinely missing were added: `--color-accent-soft` (selected rows, pills, notes), `--color-accent-hover`,
   `--color-border-strong` (form controls), `--radius-lg`, `--radius-pill`, `--shadow-2` (popovers), `--font-size-xs`, the half steps of the
   spacing scale the design uses (`--space-2-5`, `--space-3-5`, `--space-4-5`, plus `--space-0-5` and `--space-1-5` for hairlines), and two
   sizes (`--size-icon`, `--size-control`). Dark mode redefines every colour role. `tokens.test.ts` checks the roles, the dark pairs and that
   no literal colour appears elsewhere.
2. **The shared component library is the shared stylesheet, not a second set of wrapper components.** The application already built every
   screen from a small vocabulary of global classes (`card`, `button`, `field`, `form`, `table`, `form-message`, `hint`, `stack`,
   `link-button`). That vocabulary is kept and finished (`button-secondary`, `button-danger`, `badge`, `rail`, `split`, `filters`,
   `empty-state`, `field-error`) and the bare elements (inputs, selects, fieldsets, tables, lists, sections) are styled once in
   `src/app/globals.css`. A new screen that uses the existing markup looks right without any extra work. Real table markup is kept for
   accessibility; it is styled to look like the design's list rows.
3. **Disabled and error states are designed here, because the source design has none.** Disabled: reduced strength, flat, `not-allowed`
   pointer. Invalid: a danger-coloured edge (`aria-invalid="true"`) and a short reason (`.field-error`); a refusal from the API keeps its
   banner (`.form-message`) and a success keeps its note (`p[role="status"]`). All use the existing danger and success pairs, in both themes.
4. **Fonts are served from this repository.** The two typeface families of the design are stored as files under `platform-web/src/fonts`
   (latin subset, with their licence text) and loaded with the framework's local font loader in the root layout, which exposes them as CSS
   variables that `tokens.css` references. The browser makes no request to a third party for fonts; a build needs no network for them. A test
   fails if a third-party font address appears in the source.
5. **The brand slot is one constant.** `src/lib/brand.ts` holds the placeholder product name; the header shows it with a monogram. An
   organization's own logo needs storage, an endpoint and permissions, which is backend work: it is deferred (see Consequences).
6. **Setup and console share one layout:** a side rail of the section links (`SetupFrame`, `ConsoleFrame`) next to the content. Only markup
   and styles changed; no route, request or permission decision did. "General" in the Setup rail is a marker with no link.

## Consequences

- A theme change is a change to `tokens.css` only; a new screen needs no styling pass.
- Deferred, not built: tenant branding and general settings (logo upload and whatever else belongs there; the "General" marker shows
  where), Custom Buttons and Dashboards (parked, no backend), and the drag-and-drop layout builder and application tab manager shown in the
  prototype (Sprints 18 to 21). The layout, record-page and application screens that exist today are restyled as they are.
- The font files add about 150 KB to the repository; the licence file stays next to them as the licence requires.

## Alternatives considered

- **Load the fonts from the provider's network at build or run time.** Rejected: a run-time call to a third party, and a build that needs the
  network.
- **A package of font files from the npm registry.** Rejected: a new dependency to justify and keep patched for files that never change.
- **A set of React wrapper components (button, card, field, table).** Rejected for this sprint: the screens already use plain elements with
  shared classes; wrappers would mean editing every screen for no change in what a person sees.
