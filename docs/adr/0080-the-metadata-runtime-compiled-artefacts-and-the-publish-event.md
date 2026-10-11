# ADR-0080: The metadata runtime, compiled artefacts and the publish event

- **Status:** Accepted
- **Date:** 2026-10-11
- **Sprint:** S13
- **Related:** [ADR-0016](0016-transactional-outbox-and-idempotent-consumers.md), [ADR-0053](0053-the-security-cache-and-its-version.md), [ADR-0061](0061-the-metadata-version-and-the-catalogue-cache.md), [ADR-0074](0074-definitions-the-version-counter-and-the-cache.md), [ADR-0076](0076-the-expression-engine-and-its-language.md)

## Context

The delivery plan says: `MetadataResolver`, tenant plus version cache keys, publish event, invalidation, warm, multi-instance safe; exit criterion "metadata reads hit
cache". The code that will run on every request (the write pipeline of Sprint 15) must not parse an expression on every save.

## Decision

1. **A runtime read contract above `Metadata`.** `MetadataResolver` hands out, for an object, the **compiled validation rules** (active ones, by API name) and the
   **compiled formulas** (those that pass the language, in the order they must be calculated: what a formula uses comes before it; formulas in a circle are left
   out). A `CompiledRule` judges a map of values at a moment (`rejects`, or a failure with the place); a `CompiledFormula` calculates a value (or a failure). Both
   read only their arguments: no database, no file, no clock. The compiled expression types stay inside the metadata module.
2. **Kept with the snapshot, so it has its lifetime.** The artefacts of an object are made on the first question under a metadata version and kept in the snapshot of
   the organization ([ADR-0061](0061-the-metadata-version-and-the-catalogue-cache.md)). The snapshot is replaced when the version moves on, and with it everything compiled from the old
   one: nothing can be stale and nothing needs invalidating. The key is, as ever, the organization and the version read from the database in the question's own
   transaction; a transaction that has changed definitions builds for itself and never fills the cache. Counters `platform.metadata.artefacts{result=built|served}`
   next to the existing `platform.metadata.cache{result=hit|miss|bypass}`.
3. **The publish event.** Every release writes `metadata.published` (the release number and the version, numbers only) through the transactional outbox
   in the transaction that publishes: it exists if and only if the release committed. A refused publication and a rolled-back change write none.
4. **The warmer.** The handler `MetadataWarmer` (idempotent; consumer `metadata.warm-cache`) builds the catalogue of the organization and the compiled
   artefacts of the objects that have rules or formulas, so the first request after a publication does not pay for it; it counts `platform.metadata.warm{result=warmed|current}`
   (`current`: the version was already cached; handling the same event twice changes nothing).
5. **What the event is not, and a difference from the prompt.** The outbox hands an event to **one** instance, not to every instance (each handler's changes and its
   idempotency marker commit together; [ADR-0016](0016-transactional-outbox-and-idempotent-consumers.md)). The wording "every instance has an idempotent handler that warms" cannot be met through the outbox:
   the instance that claims the event warms; the others warm on their next question, which is one version read and one build. Correctness never depends on it: the answer
   is the one of the version in the database whether an event is lost, delayed, handled twice or never handled, and whether the cache is on or off. This is proved on three
   real instances (`MetadataWarmingIT`: every event handled exactly once in all, every instance right on its next question) and by `MetadataRuntimeIT` (the warm-up makes the
   first question a hit; the handler is idempotent). A broadcast to every instance would need another mechanism (a channel in the cache server, a poll of the version); neither is
   justified until a measurement (Sprint 14) shows a need.
6. **No Redis tier** (decided with the product owner's recommendation). The version in the database already makes every instance correct.
7. **The cost, and its bound.** A compiled artefact is the text of the expression and a tree of at most 500 parts (a typical rule: about 20 parts, a few kilobytes), made only
   for the objects asked about, and it lives only as long as the snapshot of its organization, which the cache bounds (`platform.metadata.cache.max-organizations`, least
   recently used first). The most an organization can make this module keep is bounded by the limits of its definitions (500 fields, 50 rules per object, 4000 characters and
   500 parts per expression). There is no separate bound for compiled artefacts; the number of organizations kept is the one that matters, and is the same bound as for the catalogue.

## Consequences

- Sprint 15 asks `MetadataResolver` on every save, inside the transaction of the save.
- "Metadata reads hit cache" is a test: a run of reads costs one build (`MetadataRuntimeIT`), next to `MetadataCacheIT` (three instances, cache on and off).
