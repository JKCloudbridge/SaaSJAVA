# ADR-0059: Standard metadata lives in definition files, and nobody can write it while the system runs

- **Status:** Accepted
- **Date:** 2026-10-05
- **Sprint:** S10
- **Related:** [ADR-0058](0058-object-and-field-definitions.md); architecture note 3 section 30 ("tenants cannot modify protected platform metadata");
  [../metadata-guide.md](../metadata-guide.md), [../standard-objects.md](../standard-objects.md)

## Context

The platform defines some objects and fields for everybody (Account, Contact, User ...). They must be protected: an organization must not be able to
change them, whatever bug or attack. They must also be easy to add to and change over the years, by people who are not database experts.

## Decision

1. **One YAML file per standard object** (`platform-app/src/main/resources/metadata/standard/Account.yml`) and one file for the system fields. They are
   read once when the application starts, checked completely and kept in memory (`StandardMetadata`). There is no table and no write path: the protection is
   structural, not a rule that has to be remembered. Adding an object or a field is editing a file; the guide `docs/metadata-guide.md` has the recipes.
2. **The loader is strict.** An unknown key, a type that does not exist, a setting the type does not take, a lookup to an object that does not exist, a
   duplicate name, a file whose name does not match its object, a field that repeats a system field: the application does not start, and the message names
   the file and the place. The checks are the same code (`FieldRules`) that checks what an organization sends, so a standard field and a custom one cannot
   differ in what they may be.
3. **Released definitions are never deleted or renamed.** `src/test/resources/metadata/standard-baseline.txt` lists everything released with its type, the
   target of a reference, the values of a picklist and the shape of an auto-number. A test fails when the files lose or change something in the list (a
   picklist may gain values), and also when the files have something the list does not know yet, so a new definition is a visible line in the same change.
   A field that must go away is marked `retired: true`: it keeps existing (records may hold values), is not offered for new permissions, and is shown as
   retired. Changing the baseline is a deliberate step with a command, and the reviewer sees the difference.
4. **The documentation cannot drift.** `docs/standard-objects.md` is generated from the files; a test fails when the two differ and prints the command
   that refreshes it.
5. **Who owns the records.** An object may say `managedBy: identity | security | licensing`. The records of such an object live in the owning module's
   tables (users, profiles, access policies ...), and the generic record store of Sprint 14 must never write them directly: it would bypass rules such as
   "the last member who can manage access stays" and the licence rules. Until then the field only informs. Objects that are managed cannot be extended with
   custom fields (`extensible: false`) until Sprint 14 decides how the two are stored together.
6. **What ships.** System fields; Account, Contact, Opportunity, Case; User, Profile, Role, LicenceType; AccessPolicy and AccessPolicyAssignment (the
   junction between users and access policies, master-detail on both sides). "Modules" has no definition yet and is left out until the product owner says what it is; the record
   type object arrives with record types in Sprint 11.
7. **A deployment serves one set.** All instances of one release hold the same files. During a rolling deployment an old and a new instance may briefly
   differ by a released definition; every change is additive (see point 3), so the old instance simply does not know the new thing yet.

## Consequences

- A change to a standard definition ships with a release and needs no migration, except that a baseline line is added.
- An organization can read the standard definitions and add its own fields where allowed; it can change nothing else, and an attempt is refused in words
  and written to the audit trail (`metadata.change.refused`, reason `protected_definition`).
