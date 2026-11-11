# System of record

Where the control plane keeps what it knows. Everything here is behind a small interface (a port)
with an in-memory implementation for fast unit tests and a PostgreSQL implementation for real use.
Both run the **same contract tests**, so they cannot quietly drift apart.

## Storage

PostgreSQL, through plain JDBC and Flyway migrations (`db/migration`). There is no persistence
framework: the store module stays free of Spring, which is what keeps "Spring Boot only in
`control-plane-app`" true. `Jdbc` is a ~100-line helper for binding, mapping and transactions; every
statement is parameterised.

The production `DataSource` is supplied by the application (dependency inversion); tests use a real
PostgreSQL in a Testcontainer, each test on its own schema.

> **Azure SQL versus PostgreSQL.** The prompt names both. This uses PostgreSQL, which Azure also
> hosts, and which the prompt's own test stack names. The SQL is mostly portable but uses
> `ON CONFLICT`, `RETURNING` and a PL/pgSQL trigger, so moving to SQL Server is a port, not a switch.

## What is stored

| Table | Port | Notes |
|---|---|---|
| `run`, `run_engine` | `RunRepository` | metadata, `degraded`, each engine's status, status (`ACTIVE`, `SUPERSEDED`, `LATE`, `SCOPE_LIMITED`) |
| `finding_state` | `FindingStateRepository` | current state per repository, slice and fingerprint |
| `transition_log` | `TransitionLogRepository` | **append-only, enforced by the database** |
| `finding` | `PseudonymisedFindingStore` | pseudonyms only |
| `onboarded_repository`, `workload_scope` | `RepositoryRegistry` | owner, tier, policy, stage, route; who may write |
| `suppression` | `SuppressionRepository` | one per finding |
| `baseline` | `BaselineRegistryRepository` | **pointer only** |
| `policy_bundle` | `PolicyCatalogueRepository` | version, digest, ratification, effective date, superseded-by |
| `evidence_hot`, `evidence_warm` | `EvidenceRepository` | full detail, then counts |

**Not here, on purpose:** the pseudonym → person directory. It is a separate, encrypted,
access-restricted store, and the migration test asserts the schema has no such table and no column
that could hold an author or email. `PseudonymDirectory` is still in-memory only.

## The guarantees, and how each is held

- **The transition log is append-only (CP-AC-25).** Not just by convention: a trigger makes
  `UPDATE`, `DELETE` and `TRUNCATE` on `transition_log` fail, whatever is connected. `event_id` is
  unique and `ON CONFLICT DO NOTHING`, so a retried append is a no-op. Order is the table sequence, so
  history reads back as appended, across restarts.
- **A run is all-or-nothing (CP-AC-24).** The run and its engine rows are written in one transaction,
  and a re-run supersedes the earlier run in the same transaction. A failure leaves no half-written
  run.
- **The baseline registry is a pointer (CP-AC-28).** `BaselineRecord` has no field that could hold
  content, and its reference must be an OCI **digest** reference (`host/name@sha256:…`) whose digest
  matches. A tag is refused, because a tag can be moved under you.
- **Unknown means not onboarded (CP-AC-27).** `RepositoryRegistryService` implements the same
  `RepositoryScopeLookup` the ingestion edge already uses: a workload may write only if the repository
  is onboarded *and* it has been granted it. A stale grant with no repository behind it grants nothing.
- **The registry entry drives routing (CP-AC-23).** `routeFor(repository)` reads the entry, so editing
  it redirects work items with no other change; offboarding removes the route and every grant.
- **Evidence tiers move atomically.** Expiry is `DELETE … RETURNING` followed by the warm-tier upsert
  in one transaction, so detail is never dropped without being counted and never counted twice.
  Evidence can only be captured from a `PseudonymisedFinding`.

## Suppressions (CP-AC-26)

A suppression needs a reason and may not outlive its policy cap (`maxExpiryDays`). The owner is warned
**once**, when the sweep's commit date reaches `maxExpiryDays − 7` days after creation.

> **Interpretation.** The prompt's formula is for a suppression running its full life. A suppression
> that expires sooner would never be warned (the warning would fall due after it lapsed), so the
> warning falls due at the *earlier* of `created + (max − 7)d` and `expires − 7d`. A suppression that
> has already lapsed as of the date gets no warning.

Everything is judged against a date the caller supplies, never the wall clock. Publishing is
at-least-once: the warning is marked sent only after it is published, so a failed publish is retried
and a crash in between can repeat a warning but never lose one. `SuppressionRegisterService` is also
the `SuppressionLookup` the lifecycle uses, so the lifecycle now reads the real register.

## Policy catalogue

A version is registered unratified, ratified with *who* and *when*, then scheduled to take effect.
Only a ratified version can take effect. Making one effective links the chain: the version before it
points to it as `supersededBy`, and it points to the next if one is already scheduled. `effectiveAt(t)`
answers "which policy applied at that point in history".

`ratifiedBy` is an approver's identifier. That is a governance principal, not a code contributor, so
it is not pseudonymised; if approvers are people who must not be identifiable it should go through the
pseudonymiser, which is a decision for the data-protection owner.

## Not done / assumptions

- **The application now uses all of this** (see [running](running.md)), except the pseudonym
  directory, which is still in memory.
- **No real Azure SQL.** PostgreSQL only.
- **One writer per repository is assumed**, as in CP-FR-04. Atomicity across the log, state and run
  store is by write order and idempotency, not one transaction.
- **Housekeeping calls `EvidenceStorageService.expire` hourly**, and the pipeline now stores evidence.
- **Connection pooling is not in this module**; the application supplies a pooled `DataSource`.
- `CpAc24` and `CpAc25` are in `control-plane-store` as the prompt places them. `CpAc26` is too, using
  the durable register.
