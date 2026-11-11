# Contributor pseudonymisation

Who touched a finding is personal data. The rule is absolute: **contributor identity never reaches
the system of record.** It is replaced by a stable keyed pseudonym before anything is stored, and
there is exactly one permissioned, audited path back.

```
normalised bundle ──► PseudonymisingSink ──► PseudonymisedRunSink ──► PseudonymisedFindingStore
 (raw identity)         │ RunBundlePseudonymiser                       (pseudonyms only)
                        │   └ IdentityPseudonymiser ──► PseudonymDirectory ◄── ReIdentificationService
                        │                              (pseudonym → identity)     (authority + audit)
                        └ key unavailable ──► throws: the bundle is held and retried
```

## The pseudonym

`hex(HMAC-SHA256(key, normalised identity))`, 64 lowercase hex characters.

- **Deterministic.** The same person is the same pseudonym in every run and after every restart, so
  trends can follow a contributor without knowing who they are.
- **Keyed.** Without the deployment's secret a pseudonym cannot be recomputed from a guessed
  identity, and two deployments cannot link their contributors. (The spec's `sha256(key + identity)`
  is a keyed hash built the weak way; HMAC is the right construction and what the commit message
  specifies.)
- **Normalised first** (Unicode NFKC, trimmed, lower-cased) so `Alice@Example.com ` and
  `alice@example.com` are one person, not two.
- The key must be at least 32 bytes, is loaded on first use and then held, and prints as
  `PseudonymKey[redacted]`.

`Pseudonym` is a type, not a string. The finding store accepts only `PseudonymisedFinding`, whose
contributors are `Pseudonym`s, and `Pseudonym.issue` may be called only by `IdentityPseudonymiser`.
`ArchitecturalPseudonymisationConstraintTest` (ArchUnit) fails the build if any other class calls it,
or if the store stops being insulated from un-pseudonymised types.

## Evidence is default-deny

Pseudonymising a field called `authors` is the easy part. An engine can put an owner, an editor or an
email address under any key, and no code can recognise one by looking. So `RunBundlePseudonymiser`
keeps only evidence keys on an explicit allow-list (`changeFrequency`, `complexityDelta`,
`calculation`, `engine`, `import`, `rule`, `originalCategory`); everything else is dropped and
**counted** in `PseudonymisationReport`, so a reader can tell "no data" from "withheld".

The cost is real: **a new engine's evidence is withheld until its keys are reviewed and added.**
That is the safe direction to fail.

Blank or null identities are dropped and counted rather than pseudonymised, because pseudonymising
`""` would put every unknown contributor into one shared bucket.

## When the key store is unavailable (CP-BC-06)

`KeyStoreUnavailableException` propagates out of the pseudonymising gate. The bundle is **held**:
not processed, not lost, not set aside. The queue listener retries with backoff and it goes through
once the key returns. There is no fallback path, and the gate checks for the key even for a bundle
that names nobody, so there is no "nothing to hide" shortcut to get wrong. A failed load is not
cached, so recovery needs no restart.

## Re-identification (CP-AC-13)

`ReIdentificationService` is the only reader of the `PseudonymDirectory`. A caller needs the
`REIDENTIFICATION` authority **and** a stated justification. Every attempt, refused ones included, is
written to the audit log (caller, pseudonym, justification, time, outcome) **before** anything is
disclosed, and if the audit write fails nothing is disclosed. The audit trail never contains the
identity it revealed, and caller-supplied text is stripped of control characters so it cannot forge a
second log line. `LoggingReIdentificationAuditLog` writes to the dedicated logger
`com.debthunter.controlplane.audit.reidentification`.

## Not done yet

- **`KeyVaultPseudonymKeyStore` is tested against a fake, not Azure.** It reads a base64 secret
  through a one-method port, `KeyVaultSecretReader`. The binding to the Azure SDK, and a real-vault
  test tagged `platform`, need credentials and are not written.
- **No production `PseudonymDirectory`.** The in-memory one is for tests. A real one must encrypt at
  rest, be readable only by `ReIdentificationService`, and be excluded from every export and backup
  (CP-AC-90).
- **CP-AC-11 (no dashboard response contains identity) is deferred** to the serve layer
  (CP-FR-15), which does not exist yet. The structural guarantee underneath it, that the store holds
  no identity to leak, is what CP-AC-09 proves.
- **CP-AC-12 is a guard, not yet a proof.** The API today is the ingestion edge alone, so the test
  asserts no key-store or directory route exists. It should grow into a sweep of every route the
  query API registers.
- **Free text is not scanned.** `message` and `path` are carried as-is; they are engine-generated,
  but nothing here would catch a path like `/home/jdoe/...`.
- The pseudonym directory is **in memory in the running application** (lost on restart), and the key
  comes from configuration rather than Key Vault. See [running](running.md#known-limits-of-the-wiring).
