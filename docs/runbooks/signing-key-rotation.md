# Runbook: signing key rotation

Sprint 33 (decisions in [ADR-0290](../adr/0290-secrets-keys-and-the-object-store-role-in-a-deployment.md)). Rehearsed by
`./rotate-signing-key.ps1` under load; no token that should work is refused.

## When to use this

On a schedule (once a year, or at the end of a person's access to the keys), or at once if the private key may have leaked.

## Why three releases

The configuration lists keys. The **first signs; every key in the list verifies.** If a release put the new key first and removed the
old one in one step, the instances not yet released could not verify the tokens signed by the new key, and the old tokens would be
refused at once: people would be signed out without reason. So:

1. **Add the new key second** (it verifies, the old one still signs). Release. Nothing changes for anyone, and every instance can now
   verify the new key before anything is signed with it.
2. **Move the new key first** (it signs, the old one still verifies). Release. Tokens signed before keep working.
3. **Remove the old key**, after the longest life of an ID token signed by it has passed (the ID-token life in the settings).
   Release. An ID token signed by the retired key no longer verifies, as it must.

The keys sign **ID tokens only**. Access tokens are opaque and stored as hashes, so a rotation never signs anyone out and never
changes what an access token can do. If the old key leaked, do steps 1 and 2 at once and step 3 right after step 2: anything that
holds an ID token signed by it must fetch a new one by signing in again.

## Steps in a deployment *(cloud)*

1. Make a new P-256 key pair on a machine you trust (`openssl ecparam -name prime256v1 -genkey -noout` then convert the private key
   to PKCS#8 and write the public key as X.509 PEM; the commands are in the rehearsal's `init.ps1`). Never in the repository.
2. Put the two PEM texts into the **unused slot** of the secret store (`signing-b-private`, `signing-b-public` when `a` is active).
3. Set in the values file: `signing_retiring_slot = "b"` and `signing_retiring_key_id = "<new identifier>"`, keeping the active slot
   `a`; run the plan, read it, apply. (This is release 1: the second key verifies.)
4. Swap: `signing_active_slot = "b"`, `signing_active_key_id = "<new identifier>"`, `signing_retiring_slot = "a"`,
   `signing_retiring_key_id = "<old identifier>"`. Plan, read, apply. (Release 2.)
5. After the wait: `signing_retiring_slot = ""`. Plan, read, apply. (Release 3.) Later, empty the old slot in the secret store.

Never reuse an identifier for a different key.

## How I know it worked

After each release: `GET /api/v1/oauth2/jwks` on the platform host lists the keys you expect and no private part; a fresh sign-in
works; the identifier in the header of a new ID token (`kid`) is the one that signs. After release 3 only the new key is listed, and
an access token from before the rotation still works (it is not signed by the keys). The load of the rehearsal shows zero failed
requests through all three releases.
