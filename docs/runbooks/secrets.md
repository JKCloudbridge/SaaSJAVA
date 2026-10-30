# Runbook: credentials and the secrets store

For the people who run a deployment. Sprint 27 (decisions in
[ADR-0212](../adr/0212-the-secrets-store-envelope-encryption-and-the-keys.md) to
[ADR-0215](../adr/0215-what-sprint-27-leaves-out.md)). A credential is a value an organization gives the platform so that a
connection can sign in to an external system. An administrator writes it once; nothing, for anyone, shows it again.

## How it is kept

Every version of a credential is sealed with a data key of its own (AES-256-GCM), and the data key is wrapped by the
**key-encryption key** (KEK) that you supply. The database holds only the sealed bytes, the wrapped data key and the identifier of the
KEK that wrapped it; a database dump or backup alone reveals nothing. The sealed bytes are bound to the credential's
address (organization, name, version): copied elsewhere they do not open.

**The KEK is the only thing that protects the credentials. Treat it like the signing keys: it lives in your secret store, never in
the repository, a database table, an image or a log.**

## Supplying the KEK

A key is 32 random bytes written in Base64 (standard or URL-safe). Make one on a machine you trust:

```
openssl rand -base64 32
```

Give it to the application either as a mounted file (the file holds the Base64 text) or as the value of an environment variable your
secret manager injects:

| Variable | Meaning |
|----------|---------|
| `PLATFORM_SECRETS_KEK_ACTIVE_ID` | The identifier stored with every row this key wraps (letters, digits, dot, dash, underscore; up to 64). Mandatory. Never reuse an identifier for a different key |
| `PLATFORM_SECRETS_KEK_ACTIVE_KEY` | The key itself, Base64. Give this **or** the file |
| `PLATFORM_SECRETS_KEK_ACTIVE_FILE` | A file that holds the key. Give this **or** the key |
| `PLATFORM_SECRETS_KEK_PREVIOUS_<n>_ID`, `..._KEY` or `..._FILE` | Older keys, `n` from 0; they only open what they wrapped |

Every instance of one deployment must be given the **same** keys. **Without a valid active key the application does not start**
(the message names the setting and never repeats a value or a path). That is deliberate: a platform that accepts a credential it
could not open later is worse than one that refuses to start.

Other settings (`platform.secrets.limits.*`): `max-value-chars` (4096), `max-per-organization` (200), `retained-versions` (3; at
least 2 so that a person can go back).

Local development: `infra/local/init-env.ps1` writes `LOCAL_SECRETS_KEY` to the ignored `.env`. Keep it: credentials saved in the local
database can be opened only with it.

## Replacing the KEK (rotation of the key)

1. Make a new key and a new identifier.
2. Deploy with the new key as **active** and the old one under **previous**. New and replaced credentials are wrapped by the new key;
   the old rows still open because the old key is listed.
3. Each row is wrapped again under the new key **the first time it is used** (a connection test, a connection in use). A row is also
   moved by replacing its credential.
4. Before removing the old key, check that no row needs it. As the database owner:

   ```sql
   do $$
   declare t uuid; r record;
   begin
     for t in select id from tenant loop
       perform set_config('app.current_tenant', t::text, true);
       for r in select key_id, count(*) as versions from secret_version group by key_id loop
         raise notice 'organization % key % versions %', t, r.key_id, r.versions;
       end loop;
     end loop;
   end $$;
   ```

   Rows still named with the old identifier have not been used since step 2. Test the connections that use them (or ask the
   administrators to replace the credential), then look again.
5. Only when the old identifier no longer appears, remove it from `previous` and deploy again. A row that still needs a key you
   removed cannot be opened (see "A key is lost").

## Backup and restore

- Back up the database as usual. The backup holds sealed bytes only.
- Back up the KEK **separately**, in your secret manager, with the identifier it was given. A restore needs both: the database
  without the key opens nothing, and the key without the database holds nothing.
- Restoring an older database brings back older versions and counts of use; a credential written after the backup is gone and
  must be written again.
- Never put the KEK in the same backup, image or ticket as the database dump.

## What to do when

- **A key is lost** (no copy exists): every row it wrapped is unreadable. Connections that use those credentials answer
  `credential_store_unavailable` at their next test, and the audit trail shows one `credentials.resolve_failed` an hour for each
  affected credential. The values cannot be recovered by anyone. Each administrator writes the credentials again (a new version is
  sealed under the active key); an unreadable old version is pruned after three writes. Rows wrapped by a key you still hold are not
  affected.
- **A key is leaked** (or may have been): anyone with the key **and** a copy of the database can open the credentials in it. Make
  a new key and deploy it as active (rotation above), then have every credential **replaced at its source** (the external system
  issues a new value) and written again, and treat the old key as burned. Changing the key alone does not help for credentials
  that were already copied out.
- **A credential is leaked:** the administrator replaces it at the external system and writes the new value; the old version is
  no longer current and is pruned after three writes. Every use is counted per hour and the first use of each version is
  audited (`credentials.first_used`), which tells you when a new value began to be used.
- **The application does not start:** read the message. It names `platform.secrets.kek.*` and what is wrong with it (missing
  identifier, both or neither of key and file, not 32 bytes, an identifier used twice).
- **`credential_store_unavailable` for one organization only:** its rows were wrapped by a key this deployment no longer holds
  (a restored database, a removed `previous` key).
- **`credential_store_unavailable` everywhere:** the instance has the wrong active key, or the database cannot be reached.
- **A person asks to see a credential's value:** it cannot be shown to anyone, including the platform's staff. Write a new one.

## What to watch

- Metric `platform.secrets.operations{operation,result}`: `resolve` with `found`, `missing`, `key_unavailable`, `unreadable`;
  `put`, `rollback`, `delete` with `ok` or a reason. A rise of `key_unavailable` or `unreadable` means a key problem. The metric
  carries no name, no organization and no identifier of a person.
- The audit trail of each organization (ability `audit.view`): `credentials.created`, `.rotated`, `.rolled_back`, `.deleted`,
  `.first_used`, `.use_summary` (one record for each earlier hour of use, with counts) and `.resolve_failed`. They carry the
  identifier of the entry and the version, never the value or the name.
- Nothing about a value is ever logged. If a value is found in a log, treat it as a leak (above) and report it as a defect.

## Rules the platform keeps

- Reading a credential is possible only inside the platform, at the moment of use, by the code that sends it. There is no
  endpoint, export, screen or support tool that returns it.
- A change of a credential voids the last test of the connections that use it; a connection that is on keeps running until the
  next test or use shows whether the new value works. Removing a credential is refused while a connection that is on uses it.
- The token signing keys are not kept in this store; they stay with the deployment's files (ADR-0019, ADR-0214).
