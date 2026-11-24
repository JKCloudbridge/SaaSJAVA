# Runbook: replacing the key-encryption key (KEK)

Sprint 33; the procedure is that of [secrets.md](secrets.md), "Replacing the KEK", which this page puts in the order of a deployment
([ADR-0290](../adr/0290-secrets-keys-and-the-object-store-role-in-a-deployment.md)). Rehearsed by `./rotate-kek.ps1` under load.

## When to use this

On a schedule, when a person who knew the key leaves, or at once if it may have leaked.

## Steps *(cloud)*

1. Make a new key: 32 random bytes in Base64 (`openssl rand -base64 32`), on a machine you trust. Never in the repository.
2. Copy the current active value into the `kek-previous` entry of the secret store and write the new value into `kek-active`.
3. In the values file set `kek_active_id` to the new identifier and `kek_previous_id` to the old one. Plan, read, apply: the
   application starts with the new key active and the old one previous. Everything still opens; new and replaced credentials use the
   new key.
4. Each stored version is wrapped again under the new key the first time it is used (a connection test, a connection in use), or when
   its credential is replaced. Check which keys still hold versions (the query in secrets.md, as the database owner) until none
   names the old one. Have the administrators test the connections that use those credentials, or replace them.
5. Only then set `kek_previous_id` to empty, apply again, and empty the `kek-previous` entry. A row that still needed the removed key
   cannot be opened (see "A key is lost" in secrets.md).

Never reuse an identifier for a different key. A restored database needs the key that wrapped its rows: keep the old key's backup
copy as long as old backups exist.

## How I know it worked

The application is ready on every task after each release; the query shows only the new identifier; a credential can be written
and appears in the list by name, never by value; the rehearsal's `./rotate-kek.ps1` prints `RESULT PASS`.
