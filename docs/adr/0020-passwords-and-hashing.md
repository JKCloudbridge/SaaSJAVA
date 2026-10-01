# ADR-0020: Passwords: policy, Argon2id hashing and its measured parameters

- **Status:** Accepted
- **Date:** 2026-10-01
- **Sprint:** S3
- **Stories:** S3-SEC-01, S3-SEC-02, S3-SEC-03, S3-SEC-04
- **Related:** [ADR-0019](0019-authentication-implementation.md), [ADR-0021](0021-brute-force-protection-and-rate-limits.md)

## Context

Local credentials mean the platform stores something that proves a person knows a password. If that table leaks, the cost of
turning the stored values back into passwords is the only protection left, so the hash must be slow and memory hungry, must carry its
own settings so they can be raised later, and must not become a way to exhaust the server.

## Decision

1. **Argon2id**, a random salt per hash, stored as one text that carries the algorithm and the settings
   (`$argon2id$v=19$m=...,t=...,p=...$salt$hash`). The password is first brought to Unicode compatibility form (NFKC) so the same
   password typed on another keyboard hashes the same.
2. **Measured parameters.** `Argon2Calibration` (a manual tool in the test sources) measured one hash and one verification, median of
   25, on a development laptop (20 logical processors, Java 25.0.4):

   | Memory | Passes | Lanes | One hash | One check |
   |--------|--------|-------|----------|-----------|
   | 19 MiB | 2 | 1 | 70 ms | 70 ms |
   | 19 MiB | 3 | 1 | 95 ms | 91 ms |
   | 32 MiB | 2 | 1 | 118 ms | 117 ms |
   | **32 MiB** | **3** | **1** | **172 ms** | **186 ms** |
   | 46 MiB | 1 | 1 | 93 ms | 90 ms |
   | 46 MiB | 2 | 1 | 178 ms | 168 ms |
   | 64 MiB | 1 | 1 | 138 ms | 140 ms |
   | 64 MiB | 2 | 1 | 253 ms | 248 ms |
   | 64 MiB | 3 | 1 | 406 ms | 392 ms |
   | 19 MiB | 2 | 2 | 77 ms | 75 ms |

   The target is **100 to 300 ms** for one check on a modern server core: long enough to make guessing expensive, short enough that
   a sign-in feels instant. The default is **32 MiB, 3 passes, 1 lane** (about 175 ms here). The library's published minimum (19 MiB,
   2 passes) is faster than the target on this machine and is kept as a floor, not chosen. Production hardware differs: **repeat the
   measurement on the deployment's real hardware** before the first production use (Sprint 33) and set `platform.identity.password.*`
   accordingly; existing hashes are upgraded at the next successful sign-in, so a change needs no migration.
3. **Upgrade at sign-in (S3-SEC-02).** After a correct password, a stored hash whose algorithm or settings differ from the current
   ones (including the older bcrypt form, which is still verified, and a password longer than 72 bytes against such a hash is an
   ordinary mismatch, never an error) is replaced by a fresh one and the change audited. A wrong password never changes anything.
4. **Hashing is bounded.** At most four hashes run at once on an instance (32 MiB each: 128 MiB at most) and a request waits two
   seconds for a slot before the answer is "unavailable, try again" (503 with `Retry-After`): a burst of sign-in attempts cannot use up
   the memory of the instance.
5. **Policy (S3-SEC-03).** At least **12** and at most **128** characters (counted as characters, not bytes); **no rules about upper
   case, digits or symbols** (they make passwords more predictable, not stronger); not on the bundled list of the 10,000 most common
   passwords (case-insensitive; also refused with digits or punctuation appended, and a listed password repeated); must not contain the
   user's own address (a local part of four characters or more); a new password must differ from the current one. The messages say
   what to change and never repeat the password. A new password longer than allowed is refused before any hashing; at sign-in a
   password longer than any valid one is checked against the dummy hash (the same work as every other path, ADR-0021) and an
   enormous one (over 1,024 characters) is refused by the request's validation.
6. **Known limit of the list.** The 10,000-entry list is dominated by short passwords, and the minimum length is 12, so for people who
   choose long passwords the check adds little. This is recorded as a difference from the written story (S3-SEC-03 asks for
   known-breached passwords): a larger list, or a range query against a breach service, replaces the bundled list through the
   `BreachedPasswords` interface without touching the policy. Not done in Sprint 3.
7. **No password material anywhere (S3-SEC-04).** A password lives in a character array, cleared after use; the text form of every
   type that holds one is a fixed redaction; error messages and audit records never carry one; request logging never logs bodies. A
   test runs a whole sign-in life cycle with the application and the security framework at debug level and searches the log, every
   response and the audit table for the passwords, tokens, cookie values, the hash and the address used.
8. **Changing a password** needs the current one, is rate limited (it would otherwise be a way to guess the current password with a
   stolen session), and ends every session and token of the user, including the one that made the change.

## Consequences

- A sign-in costs about 175 ms of CPU on the instance, the same for every outcome (ADR-0021).
- Raising the parameters later is a configuration change.
- The policy numbers are configuration; the defaults are the ones above.

## Alternatives considered

- **bcrypt or scrypt**: bcrypt limits the password to 72 bytes and is not memory hard; scrypt is acceptable, but Argon2id is the
  current recommendation and the spike proved it on this stack.
- **A pepper kept outside the database**: needs a secret to be managed per environment; revisit with the secrets store (Sprint 27).
- **Composition rules**: rejected (see decision 5).

## References

- `platform-app/src/main/java/app/platform/identity/internal/PasswordHasher.java`, `PasswordPolicy.java`,
  `BundledCommonPasswords.java`; resource `identity/common-passwords.txt`; `platform-app/src/test/java/app/platform/identity/internal/Argon2Calibration.java`
- Tests: `PasswordHasherTest`, `PasswordPolicyTest`, `HashUpgradeIT`, `UsersIT`, `LogsAreCleanIT`
