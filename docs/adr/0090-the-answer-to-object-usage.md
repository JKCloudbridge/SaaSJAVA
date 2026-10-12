# ADR-0090: The answer to `ObjectUsage`: an object or a field in use is not removed

- **Status:** Accepted
- **Date:** 2026-10-12
- **Sprint:** S14
- **Related:** [ADR-0062](0062-removing-objects-and-fields.md), [ADR-0067](0067-releases-history-and-rollback.md), [ADR-0084](0084-the-record-store.md), [ADR-0086](0086-soft-delete-the-recycle-bin-and-its-retention.md)

## Context

Since Sprint 10 the metadata module asks `ObjectUsage` (`hasRecords`, `hasValues`) before it refuses a destructive change, and since Sprint 11 before it refuses a rollback. Until Sprint 14 no bean answered, so nothing was ever blocked.

## Decision

`RecordObjectUsage` (in the `data` module) answers, from the record store, for the tenant context and in the caller's transaction:

- **`hasRecords(object)`**: the organization has at least one record of the object, **live or in the recycle bin**. A restored record of a removed object would be a record the metadata can no longer explain. An object with only records in the bin
  cannot be removed until they are restored or removed for good, by a person or by the clean-up of the bin (ADR-0086).
- **`hasValues(object, field)`**: at least one record of the object, live or in the recycle bin, has a key of that name. The store never keeps an empty value (a cleared field loses its key, ADR-0087), so "has a key" is "holds a value".

What the metadata module does with the answer:

- **Removing an object** (live or in a change set): refused with `409` ("the object still has records") while any record exists. It was already asked in Sprint 10.
- **Removing a field** (live or in a change set): refused with `409` ("records still hold values in this field") while any record holds a value in it. **New in Sprint 14**: the check existed only for rollbacks; it is now in `MetadataService.deleteField`,
  which is also what a change set applies, so both ways are covered by one rule.
- **Rolling back** a release that would remove an object that has records or a field that holds values: refused with the reason, as since Sprint 11.
- **Retyping a field** is not offered: the type of a field never changes after it is made (ADR-0060), so the only ways to "retype" are removing and adding, and removing is refused while values exist.
- Once the records are removed for good, or the field is emptied in every record, the removal works.

## Consequences and limits

- **Cost.** `hasRecords` is an index probe. `hasValues` asks the JSON of the records of the object (no index serves "has this key"): on an object with millions of records it can take seconds when the answer is "no". It is asked only when someone
  removes a field, which is rare, and it stops at the first record that has the key. A field that is in no record's payload is therefore the slow case; if that becomes a problem a per-field "ever written" marker is the answer.
- **Not done (and said):** narrowing a field while values exist (shortening a text, removing a picklist value that records hold, making a field required when records lack it, changing a lookup's target): these need the type and relationship checks of
  Sprint 15 and the query engine of Sprint 16.
- The dependency check of a change set is made on the metadata after the change (Sprint 13); the usage check is made at the moment of removal, in the same transaction, on the records as they are. These are different questions and both must pass: a test
  removes a field that records use through a change set and one that no record uses in another set, and only the second is published.
