# ADR-0239: The pinned host key and the sign-in of a file connection (S29A)

- **Status:** Accepted
- **Date:** 2026-10-31
- **Sprint:** S29A
- **Related:** [ADR-0213](0213-versions-rotation-the-credential-endpoints-and-the-audit-of-use.md), [ADR-0237](0237-the-file-transport-seam.md), [ADR-0238](0238-the-ssh-library-and-the-socket-the-platform-opens.md)

## Decision

1. **The server's key is pinned, typed by the administrator.** The setting `hostKey` is a new kind of setting,
   `FINGERPRINT`: `SHA256:` and 43 characters of base64, as the usual tools print it. It is **required** (no default), so a
   file connection cannot exist without it. There is **no trust on first sight**: the platform never learns a key. The key
   shown by the server is hashed (SHA-256 of the key blob) and compared with the pin; the library's own known-hosts file is
   never read or written (the repository the session uses answers "yes" to exactly one fingerprint).
2. **It changes only by a new version of the connection.** A connection that is on is frozen (ADR-0210); the key is part of
   the settings and so of the fingerprint of the definition. Changing it means switching the connection off, editing it
   (which voids the test), testing it and switching it on again. That is the key rotation procedure in the runbook.
3. **A mismatch is a stable reason, `host_key_mismatch`.** The credential is **never shown** to a server whose key is not the
   pinned one (the check happens before authentication). The connection is stopped (ADR-0237 item 5); nothing is retried.
4. **The sign-in is a named credential**, resolved at the moment of use and wiped after (ADR-0213): two new ways, both stored as
   one secret. `BASIC` is `user:password` (the first colon ends the user). A new `SSH_KEY` is the user name on the first
   line and the private key in the usual text form on the lines after it. A key protected by a passphrase is not supported
   (the platform would have to keep the passphrase too, one more secret for no gain over a key kept in the store). The
   user name is bounded to `[A-Za-z0-9._@-]{1,64}`; a key is bounded to 16,384 characters and must begin as a key does.
   A credential that is neither is `credential_unusable`; nothing was sent to the server.
5. **`CredentialLogins` is the sibling of `CredentialHeaders`.** `SecretValue.reveal()` may be called by exactly: the secrets
   store, the header builder, the signature check of the receiver and now this class (the ArchUnit rule names it). The
   login is a record that prints as redacted and wipes its bytes when the visit ends; the library copies what it needs for
   the session and releases it with the session (a limit of the library, stated here).
6. **Nothing identifying reaches a record.** A log line, an audit record, a metric tag, an error or an answer never holds a
   credential, a user name, a key, the fingerprint of a user key, a path with a name an organization chose or the address of
   the server. The pinned fingerprint of a *server* is public by nature and is shown with the other settings of the
   connection; it is not logged.

## Consequences

A server whose key is replaced stops its connection until a person pins the new one. That is the point: the alternative is
to sign in to whoever answers.
