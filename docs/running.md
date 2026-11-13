# Running the control plane

`control-plane-app` is the only module that knows about Spring Boot. It supplies the infrastructure
the framework-free modules are written against, and decides which implementation of each port to
plug in.

## What is wired

```
HTTP POST /api/v1/runs ──► security ──► rate limit ──► parse ──► scope (registry) ──► schema ──► queue ──► 202
                                                                      │
                                                                      ▼ (queue worker, same process)
   receiver (dedupe) ──► normalise ──► pseudonymise ──► findings │ evidence │ lifecycle ──► PostgreSQL
                              └─ can never be normalised ──► quarantine (metadata only)
```

- **The registry decides who may write.** A workload may publish for a repository only if the
  repository has been onboarded and the workload granted it. There is no configuration for this any
  more; `RepositoryRegistryService` is the `RepositoryScopeLookup` the edge asks.
- **The queue is worked in the same process.** `IntakeWorkerLifecycle` starts after everything
  else is assembled and stops first. Set `debthunter.controlplane.intake.listener-enabled=false` to run
  the ingestion edge alone.
- **Housekeeping runs hourly** (`retention.sweep-interval`): expired evidence detail moves to the warm
  tier, and suppression owners are warned. These use the wall clock (retention is a "now" concern);
  the finding lifecycle is judged by commit date.

## Start-up refuses rather than runs unsafe

| Missing or unusable | Result |
|---|---|
| token verification (`jwk-set-uri`, `issuer`, `audience`) | will not start |
| `database.url`, or the database cannot be reached or migrated | will not start |
| `pseudonym.key` absent, not base64, or under 32 bytes | will not start |
| `pseudonym.directory-key` absent, not base64, under 32 bytes, or equal to the pseudonym or intake key | will not start |

The schema is migrated (Flyway) before anything else uses the database.

- **The read APIs, work-item egress and digests are zones** that can each be switched off without
  affecting ingest ([details](zones.md)). Serve and egress are on by default; digests are off.

## Configuration

| Property | Environment | Default |
|---|---|---|
| `security.jwk-set-uri` / `.issuer` / `.audience` | `CONTROL_PLANE_JWK_SET_URI` / `_JWT_ISSUER` / `_JWT_AUDIENCE` | **required** |
| `security.admin-role` | `CONTROL_PLANE_ADMIN_ROLE` | `ControlPlane.Admin` |
| `database.url` / `.username` / `.password` | `CONTROL_PLANE_DB_URL` / `_DB_USER` / `_DB_PASSWORD` | **url required** |
| `database.max-pool-size` | | `10` |
| `database.connection-timeout` / `.socket-timeout` | | `PT3S` / `PT30S` |
| `pseudonym.key` | `CONTROL_PLANE_PSEUDONYM_KEY` | **required**, base64, ≥ 32 bytes |
| `pseudonym.directory-key` | `CONTROL_PLANE_PSEUDONYM_DIRECTORY_KEY` | **required**, base64, ≥ 32 bytes, different from the other keys |
| `intake.encryption-key` | `CONTROL_PLANE_INTAKE_KEY` | **required**, base64, ≥ 32 bytes |
| `intake.listener-enabled` | | `true` |
| `intake.max-wait` | | `PT3S` |
| `rate-limit.max-requests` / `.window` | | `600` / `PT1M` |
| `retention.evidence-hot-ttl` | | `P30D` |
| `retention.sweep-interval` | | `PT1H` |
| `suppressions.max-expiry-days` | `CONTROL_PLANE_SUPPRESSION_MAX_EXPIRY_DAYS` | `90` (1 to 365) |
| `zones.serve.enabled` | | `true` |
| `zones.egress.enabled` | | `true` |
| `zones.egress.trackers.azure-boards.*` / `.jira.*` | | none: `base-url`, `authorization` (both or neither) |
| `zones.egress.minimum-severity` / `.claim-ttl` / `.run-link-base` | `CONTROL_PLANE_RUN_LINK_BASE` | `HIGH` / `PT2M` / none |
| `zones.digests.enabled` | | `false` |
| `zones.digests.squad-link-base` / `.cadence` / `.send-empty` / `.webhooks.<ref>` | `CONTROL_PLANE_SQUAD_LINK_BASE` | required when enabled / `P1D` / `false` / none |
| `zones.*.delivery.*` | | 10 attempts, `PT5M` lease, `PT1S` to `PT1M` backoff, batch 20, poll `PT1S` |

All keys are under `debthunter.controlplane.`.

## Onboarding a repository

Nothing can be published until a repository is onboarded and a workload is granted it. An
administrator does this over HTTP with the [administration API](admin-api.md); no database access is
needed.

## Known limits of the wiring

- **The pseudonym key comes from configuration**, the interim source. The Key Vault adapter is
  written against a port (`KeyVaultSecretReader`) but the Azure SDK binding is not.
- **The pseudonym directory is in PostgreSQL**, each identity sealed (AES-256-GCM) under
  `pseudonym.directory-key`. Losing or changing that key makes every stored identity unreadable
  (reads then fail loudly, they do not look like unknown pseudonyms), so back it up apart from the database.
- **There is no commit-graph source.** A bundle carries its commit id but not parents or commit date,
  so every commit is "unknown": runs apply in arrival order and the run timestamp stands in for the
  commit date. Out-of-order protection is therefore inactive in a real deployment until a source (the
  Git host's API, or a mirror) is wired.
- **Quarantine keeps metadata only.** The bundle still carries raw contributor identity, so the
  quarantine record does not store it. The bundle itself is set aside on the intake queue, encrypted,
  for `intake.dead-retention`; after that it is deleted.
- **Suppression warnings are only logged**; there is no notification channel yet.
- **The app now needs Docker to test**: its integration tests start PostgreSQL.
- **No Dockerfile or deployment manifest** yet.
