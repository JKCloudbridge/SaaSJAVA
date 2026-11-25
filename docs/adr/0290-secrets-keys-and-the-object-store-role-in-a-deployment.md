# ADR-0290: Secrets, keys, and the object-store role in a deployment (S33)

- **Status:** Accepted
- **Date:** 2026-11-24
- **Sprint:** S33
- **Related:** [ADR-0019](0019-authentication-implementation.md), [ADR-0120](0120-files-attached-to-records.md), [ADR-0212](0212-the-secrets-store-envelope-encryption-and-the-keys.md), [ADR-0287](0287-images-the-build-stamp-and-the-tag-rule.md), [ADR-0291](0291-environments-the-matrix-outbound-addresses-and-what-staging-may-do.md)

## Decision

1. **Sources.** Every secret of an environment is an entry of that environment's secret store, named in the infrastructure files
   (names and permissions only) and given its value by the owner. The container service reads the entries **at start** and hands
   them to the container as environment variables; the task definition holds the name of the entry, never the value. A task that
   names an entry with no value does not start, so a forgotten value is a failed start and not a default. The one value the
   files produce is the cache password, generated and written straight into the store.
2. **Who may read what.** The application's execution role reads the entries its own task names; the migration job's role reads the
   database owner's password (a field of the secret the database service keeps) and nothing else. The application therefore never
   holds the owner's password. The application's own role has the files bucket and the metric records, nothing more.
3. **The signing key pair** reaches the application as files (`PLATFORM_KEYFILE__...` written by the image's entry point, removed
   from the environment). **Rotation is three releases, never one:** (1) add the new key second (it verifies, the old one still
   signs); (2) move it first (it signs, the old one verifies); (3) remove the old one after the longest token life. One release
   would sign with a key the instances not yet released cannot verify. A list given from the environment replaces the one in the
   configuration file as a whole, so with two keys both are given in the indexed form (`PLATFORM_IDENTITY_SIGNING_KEYS_<n>_...`).
   The keys sign ID tokens only (published through the key set); access tokens are opaque and stored as hashes (ADR-0019), so a
   rotation never affects them. `rotate-signing-key.ps1` proves it under load: sign-ins work through all three releases, the key
   set lists exactly the keys expected and no private part, and an access token issued before the rotation keeps working.
4. **The key-encryption key of the secrets store** is rotated as `runbooks/secrets.md` already says (new active, old previous, wait
   until no row needs the old one, remove it); `rotate-kek.ps1` proves it under load.
5. **The object store: a role, not a key.** `platform.storage.credentials` is `keys` (default; an access key and its secret) or
   `role` (neither is given; the platform the application runs on attaches short-lived credentials to the process and the library
   finds them). The two never mix: a key with `role`, or a bucket with `keys` and no key, stops the start with a message that says
   which. `local`, the tests and a self-hosted store keep `keys`. A deployment uses `role`, so no object-store key exists to leak.
   Because a role makes the metadata addresses of the cloud valuable, the hostile-address corpus includes the metadata address, the
   container credentials endpoint and the cloud's IPv6 metadata address, and a deployment's private ranges are refused by the
   address rules already (every resolved address must be public).
6. **A secrets audit.** `SecretShapedTextTest` scans the deployment files, scripts, workflow, documents and configuration for
   anything shaped like a secret (key blocks, access key identifiers, signed tokens, long values after a secret-like name) and
   fails the build; its own tests plant a fake of each shape and see it flagged. The state of the infrastructure tool holds the
   cache password: it is kept in a private, encrypted, versioned bucket that only the owner reads.
7. **Never in a log:** nothing here changes the rule; the entry point prints nothing about key files, the schema check prints
   neither an address nor a password, the migration job prints classes and states only.

## Consequences

- The first administrator is made by M002, never by a default account; the multi-factor gate for platform roles stays Sprint 34's.
- A lost signing key or key-encryption key is an incident with its own runbook sections, not something the files can repair.
