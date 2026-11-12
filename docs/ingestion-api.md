# Ingestion API

How a pipeline hands a run bundle to the control plane, and what it gets back. This is the one
interface the data plane (`debt-hunter scan`) talks to, and the design rule behind it is absolute:
**nothing here is something a pipeline waits on.** The reply is an acknowledgement, never a verdict,
and a control plane that is slow, down, or gone must never change what a pipeline delivers.

## `POST /api/v1/runs`

| | |
|---|---|
| Content type | `application/json` |
| Body | A run bundle: the `debt-hunter.json` a scan writes |
| Authentication | `Authorization: Bearer <JWT>` — what `debt-hunter scan --publish-api-key` sends |

### Authentication

Every request must carry a bearer JWT. The token is verified against the issuer's published keys
(`jwk-set-uri`) for its signature, expiry, **issuer**, and **audience**. The audience check matters:
an Entra tenant signs tokens for every application in it with the same keys, so a signature alone
would also accept a token minted for some other app. The caller's workload identity is the token's
`sub` claim.

There is no unauthenticated path. A missing, malformed, expired, wrong-issuer, or wrong-audience
token is a `401`, before anything else is considered.

### What happens, in order

Checks run cheapest and least revealing first, and a request stops at the first one it fails.

| # | Check | Failure |
|---|---|---|
| 1 | Rate limit — per workload identity | `429` `rate_limited`, with `Retry-After`. The body is never read |
| 2 | Body is a single JSON object (duplicate keys rejected) | `400` `invalid_json` |
| 3 | The bundle declares a repository (`run.repository`) | `400` `schema_validation_failed` |
| 4 | The caller's identity is scoped to that repository | `403` `workload_scope_violation`, and a security alert is raised |
| 5 | Schema version is supported, and the bundle conforms to it | `400` `unsupported_schema_version` or `schema_validation_failed` |
| 6 | The queue accepts the bundle | `503` `intake_unavailable`, with `Retry-After` |

Scope (4) is deliberately checked **before** schema validation (5), so a caller that is not allowed
to write for a repository learns nothing about how its bundle validates.

Duplicate keys are rejected at (2) because a bundle that says `"repository"` twice is ambiguous: the
scope check could read one value while a later stage reads the other.

### Responses

**`202 Accepted`** — the bundle is on the intake queue.

```json
{ "correlationId": "3f2b8c1e-…" }
```

That is the entire body: an id for tracing the bundle, no verdict, no advice.

**Errors** are machine-readable. `error` is a stable code to switch on; `message` is for humans and
may change.

```json
{
  "error": "schema_validation_failed",
  "message": "The bundle failed schema validation",
  "details": [{ "path": "$.run", "message": "$.run: required property 'historyDepth' not found" }]
}
```

| Status | `error` | Meaning |
|---|---|---|
| `400` | `invalid_json` | Body is not a JSON object, or repeats a key |
| `400` | `schema_validation_failed` | Does not conform to its declared schema. `details` lists up to 50 problems |
| `400` | `unsupported_schema_version` | Declared version is outside the accepted window. `supportedMajors` lists what is accepted |
| `401` | — | No valid token |
| `403` | `workload_scope_violation` | Caller may not publish for that repository |
| `429` | `rate_limited` | Over the per-identity limit; `Retry-After` is in seconds |
| `503` | `intake_unavailable` | Queue could not take the bundle in time; `Retry-After: 30` |
| `500` | `internal_error` | Unexpected failure; the body never leaks internal detail |

The queue wait is bounded (`intake.max-wait`, default 3 s), so a dead broker is a prompt `503`, not
a hung request.

## Schema versions

The control plane accepts the **current** schema major version and the one before it (N-1).

| Version | Schema | Status |
|---|---|---|
| `1.x` | `run-bundle-v1.schema.json` | Current. Derived from the data plane's `debt-hunter.json` |
| `0.x` | `run-bundle-v0.schema.json` | N-1. **Provisional** — see below |

The v1 schema is a **tolerant reader**: required fields are enforced, unknown fields are allowed.
The data plane's own schema forbids unknown fields, which is right for a producer checking its own
output but wrong at an ingestion boundary — it would reject every bundle the day the data plane adds
a field. Any `1.x` minor is accepted.

When the data plane ships major N+1, bump `debthunter.controlplane.ingest.schema.current-major`;
N+1 and N are then accepted and N-1 falls out of the window.

> **The v0 schema is a stand-in.** The data plane has only ever emitted `1.x`, so no real v0
> exists. v0 is the v1 shape with the fields v1 added left optional, so the current-and-N-1 window
> is real and testable. It will be aligned with the normaliser's field mapping when that is built.

## Repository identity

A bundle's `run.repository` is treated as an opaque identifier, and it is what the scope check is
keyed on. **Today the data plane puts the repository's local filesystem path there** (for example
`/home/runner/work/widgets/widgets`), which is not an identity a registry can meaningfully scope a
workload to. The data plane needs a stable identifier — for instance a `--repository-id` option
defaulting from CI environment variables such as `GITHUB_REPOSITORY` — before this works outside
tests. That is a change to the data plane, not the control plane.

## Throttling (CP-BC-04)

The prompt names the boundary condition "burst throttling" without specifying it. The implemented
interpretation is a **per-workload-identity sliding-window limit** (default 600 requests per minute):
one pipeline's burst gets `429` and a `Retry-After`, other pipelines are unaffected, and the limit
recovers once the window passes. The limiter takes an injectable clock, so it is tested without
sleeping.

## The intake queue

Accepted bundles go onto a **durable queue in PostgreSQL** (the `work_queue` table), ordered per
repository. A bundle is durable the moment the `202` is sent, because the enqueue is a committed
database write. Delivery is at-least-once, so duplicates are normal:

- The receiver de-duplicates on `run.id`, and marks a run processed only **after** the next stage
  succeeds, so a transient fault delays a bundle but never loses one.
- A worker claims bundles under a lease. If it dies, the lease expires and the bundle goes to another
  worker. A repository's bundles are handled oldest first, and one repository's stuck bundle does not
  hold up the others.
- **A dependency being down is a hold, not a failure.** If the pseudonym key store or the database is
  unavailable, the bundle is put back without counting the attempt, for as long as that lasts, and is
  never set aside on account of it (CP-BC-06: ingest must hold, not lose).
- **A bundle that fails for its own reasons** is retried with a growing delay, and after
  `intake.max-attempts` it is set aside as dead and logged at error level, so one bundle that can never
  succeed cannot hold up its repository for ever. A bundle that cannot be decrypted or decoded is set
  aside at once, and so is one the pipeline quarantines as never processable (its metadata is in the
  quarantine record; the bundle itself waits here, encrypted).
- **Bundles are encrypted in the table.** A bundle still holds real contributor identities, so it is
  sealed with AES-256-GCM (key: `intake.encryption-key`) before it is stored, bound to its repository.
  When a bundle is finished its payload is **erased**; a set-aside bundle keeps its encrypted payload
  for `intake.dead-retention` so someone can investigate, then is deleted. Only the exception's type
  is recorded against a failed item, never its message.

The edge does its blocking work off the request threads and bounds it: the registry lookup (the scope
check) and the queue write each run on a bounded pool with a timeout of `intake.max-wait`. If either
cannot finish, the pipeline gets a prompt **`503`** with `Retry-After`: `registry_unavailable` when
the registry could not be read, `intake_unavailable` when the queue could not take the bundle. A
timed-out write may still commit afterwards; if the pipeline retries, the receiver de-duplicates.

The application works the queue in the same process, and runs each bundle through the
[normaliser](normalisation.md), the [pseudonymiser](pseudonymisation.md), and the
[lifecycle](lifecycle.md) into the [system of record](system-of-record.md). See
[running the control plane](running.md) for how it is assembled.

## Configuration

| Property | Environment | Default | |
|---|---|---|---|
| `debthunter.controlplane.security.jwk-set-uri` | `CONTROL_PLANE_JWK_SET_URI` | — | **Required** |
| `debthunter.controlplane.security.issuer` | `CONTROL_PLANE_JWT_ISSUER` | — | **Required** |
| `debthunter.controlplane.security.audience` | `CONTROL_PLANE_JWT_AUDIENCE` | — | **Required** |
| `debthunter.controlplane.intake.encryption-key` | `CONTROL_PLANE_INTAKE_KEY` | — | **Required**, base64, at least 32 bytes |
| `debthunter.controlplane.intake.max-wait` | — | `PT3S` | Bound on the registry lookup and the queue write (ISO-8601) |
| `debthunter.controlplane.intake.max-attempts` | — | `10` | Tries before a failing bundle is set aside |
| `debthunter.controlplane.intake.lease` | — | `PT5M` | How long a worker may hold a bundle |
| `debthunter.controlplane.intake.initial-backoff` / `.max-backoff` | — | `PT1S` / `PT30S` | Retry delays; the max is also the hold delay |
| `debthunter.controlplane.intake.dead-retention` | — | `P7D` | How long a set-aside bundle is kept, encrypted |
| `debthunter.controlplane.rate-limit.max-requests` | — | `600` | Per identity, per window |
| `debthunter.controlplane.rate-limit.window` | — | `PT1M` | |
| `debthunter.controlplane.ingest.schema.current-major` | — | `1` | Newest accepted schema major |
| `debthunter.controlplane.ingest.max-bundle-bytes` | — | `10485760` | Largest accepted bundle |

The three security settings and the intake encryption key are mandatory: the application refuses to start without them rather than
run unauthenticated. Which workload may publish for which repository is no longer configuration: it
is decided by the repository registry (a repository must be onboarded and the workload granted it).
The full configuration, including the database and pseudonymisation key, is in
[running the control plane](running.md#configuration).
