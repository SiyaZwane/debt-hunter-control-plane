# Policy signing and publication (CP-FR-09)

A validated policy bundle becomes a signed, immutable, retrievable artefact, and a catalogue entry says
which version is in force. This page describes what the code does; `README.md` lists the decisions to
confirm and the gaps.

## Publishing

`PolicyPublisher.publish(PublishRequest)` runs, in this order, and stops at the first failure:

1. **Validate** with `PolicyBundleValidator` (errors refuse; warnings are returned in `Published`).
2. **Refuse an existing version** if the catalogue already has it.
3. **Sign** the bundle's `sha256:` digest through `KeyVaultSigner`, then **verify** the signature with
   the verifier before trusting it. A signer that returns something that does not verify cannot publish.
4. **Push** bundle and signature to the registry tagged with the version. The registry computes the
   digest itself; it must equal ours.
5. **Catalogue**: register (version, digest), ratify (who, when), make effective (from date). Making a
   version effective sets the superseded-by pointer on the version it replaces.

The catalogue is written last. Failure stages are `VALIDATION`, `VERSION_CONFLICT`, `SIGNING`,
`REGISTRY` and `CATALOGUE`; the last means the artefact is already in the registry and the publish is
safe to retry, because pushing identical content under the same tag is a no-op.

## Consuming

`PolicyBundleFetcher` needs a registry client, a `SignatureVerifier` and a repository name, nothing
else. `fetch(reference)` takes a tag or a digest (prefer a digest) and returns a `VerifiedBundle`, or
throws `UntrustedBundleException` if the content does not match its digest, the registry served a
different artefact than asked for, or the signature does not verify. `fetchTo(reference, path)` writes
the file for the data plane's `--policy` only after verification.

## Registry contract

`OrasRegistryClient` has two methods, `push` and `pull`. Content is addressed by digest; version tags
are immutable (moving one throws `TagConflictException`); an unknown reference is an empty `pull`; every
other failure is a `RegistryException`. There is deliberately no delete. `InMemoryRegistryClient`
honours the full contract.

## Retention

Superseded bundles stay in the registry. `SupersededRetention.mustRetain(majorWhenSuperseded,
currentMajor)` is true for two major tool cycles after supersession; a registry-side job should consult
it before removing anything.

## How the acceptance criteria were read

| AC | Test | Meaning used |
|---|---|---|
| CP-AC-44 | `CpAc44_SignedBundleAvailableByDigestTest` | The digest in the catalogue fetches the signed bundle, and a public key alone proves it genuine |
| CP-AC-45 | `CpAc45_CatalogueEntryCreatedTest` | A durable (PostgreSQL) entry with digest, ratification and effective date, plus the superseded-by chain |
| CP-AC-46 | `CpAc46_SupersededBundleRetainedTest` | A superseded bundle is still fetchable by digest and tag, and still verifies |
| CP-AC-47 | `CpAc47_OfflinePipelinePullsPolicyFromRegistryTest` | With no control plane, a pipeline pulls and verifies a bundle and the real CLI gates against it; a tampered bundle never reaches the CLI |
