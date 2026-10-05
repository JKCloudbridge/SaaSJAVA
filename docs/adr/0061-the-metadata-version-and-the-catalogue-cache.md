# ADR-0061: The metadata version and the catalogue cache (with the cost note)

- **Status:** Accepted
- **Date:** 2026-10-05
- **Sprint:** S10
- **Related:** [ADR-0053](0053-the-security-cache-and-its-version.md) (the design reused), [ADR-0058](0058-object-and-field-definitions.md); architecture note 3 sections 27 and 28;
  delivery plan S10, S13

## Context

Every permission decision asks "does this object or field exist?" through `ObjectCatalog`. With the real catalogue that is a read of the organization's
object and field tables plus parsing, on every decision. A cache is needed, and it must never serve a catalogue that has since changed.

## Decision

1. **A metadata version per organization** (`metadata_version`, V031): one counter that database triggers raise, in the same transaction, on every insert,
   update or delete of `object_definition` or `field_definition`. No code path can change a definition and forget the counter. A test lists the tables.
2. **The catalogue is kept in memory with the version it was built under and served only while the version is unchanged** (`MetadataCache`, a bounded
   least-recently-used map per instance, default 1000 organizations). A change is therefore visible to the next question after it commits, on every
   instance, with no timer and no message that can get lost; losing an entry, restarting or switching the cache off (`platform.metadata.cache.enabled=false`)
   never makes an answer wrong. This is the design of ADR-0053; Sprint 13 reuses it for the metadata runtime (tenant plus version as the key).
3. **A transaction that changed definitions bypasses the cache** (it must read its own uncommitted changes and must not hand them to anybody else); the
   service says so with `MetadataVersions.wrote()`.
4. **One version read per transaction.** The version read is remembered for the length of the transaction, so a request that asks the catalogue many times
   (a bulk decision) pays for one read and sees one consistent catalogue. Asked outside a transaction, the catalogue opens a short one of its own, because the
   tenant is set in the database only inside a transaction.
5. **Cost note, for whoever tunes this.** Every transaction that asks the catalogue pays one small read (the version). It is worth it because building a
   catalogue takes two statements and parsing. If the version read ever shows up in a profile, the next step is not to drop the guarantee but to share
   the security version read (a request already pays that one) in a combined counter.
6. **Metadata changes also raise the security version** (V031 attaches the security trigger of V028 to both tables): an answer of `Permissions` or
   `Decisions` depends on which objects and fields exist, so cached answers are dropped at once when one is added or removed.
7. **No Redis tier**, for the reason of ADR-0053: the version in the database is what makes an answer correct; Redis would only add a way to be wrong.

## Consequences

- The standard definitions need no version: they are part of the release, read once, and the same for every organization.
- Metrics `platform.metadata.cache` (hit, miss, bypass) show how it behaves.
