# Running the control plane

`control-plane-app` is the only module that knows about Spring Boot. It supplies the infrastructure
the framework-free modules are written against, and decides which implementation of each port to
plug in.

## What is wired

```
HTTP POST /api/v1/runs ──► security ──► rate limit ──► parse ──► scope (registry) ──► schema ──► Kafka ──► 202
                                                                      │
                                                                      ▼ (Kafka listener, same process)
   receiver (dedupe) ──► normalise ──► pseudonymise ──► findings │ evidence │ lifecycle ──► PostgreSQL
                              └─ can never be normalised ──► quarantine (metadata only)
```

- **The registry decides who may write.** A workload may publish for a repository only if the
  repository has been onboarded and the workload granted it. There is no configuration for this any
  more; `RepositoryRegistryService` is the `RepositoryScopeLookup` the edge asks.
- **The queue is consumed in the same process.** `IntakeListenerLifecycle` starts after everything
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

The schema is migrated (Flyway) before anything else uses the database.

## Configuration

| Property | Environment | Default |
|---|---|---|
| `security.jwk-set-uri` / `.issuer` / `.audience` | `CONTROL_PLANE_JWK_SET_URI` / `_JWT_ISSUER` / `_JWT_AUDIENCE` | **required** |
| `database.url` / `.username` / `.password` | `CONTROL_PLANE_DB_URL` / `_DB_USER` / `_DB_PASSWORD` | **url required** |
| `database.max-pool-size` | | `10` |
| `pseudonym.key` | `CONTROL_PLANE_PSEUDONYM_KEY` | **required**, base64, ≥ 32 bytes |
| `intake.bootstrap-servers` | `CONTROL_PLANE_KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` |
| `intake.topic` | | `debt-hunter.run-intake` |
| `intake.consumer-group` | | `debt-hunter-control-plane` |
| `intake.listener-enabled` | | `true` |
| `intake.max-wait` | | `PT3S` |
| `rate-limit.max-requests` / `.window` | | `600` / `PT1M` |
| `retention.evidence-hot-ttl` | | `P30D` |
| `retention.sweep-interval` | | `PT1H` |

All keys are under `debthunter.controlplane.`.

## Onboarding a repository

Nothing can be published until a repository is onboarded and a workload is granted it. There is **no
admin API yet**, so today this is done through `RepositoryRegistryService` (or SQL against
`onboarded_repository` and `workload_scope`). Until the query/admin API exists this is the main
operational gap.

## Known limits of the wiring

- **The pseudonym key comes from configuration**, the interim source. The Key Vault adapter is
  written against a port (`KeyVaultSecretReader`) but the Azure SDK binding is not.
- **The pseudonym directory is in memory**, so it is lost on restart and re-identification works only
  for contributors seen since. A durable, encrypted, access-restricted directory is needed.
- **There is no commit-graph source.** A bundle carries its commit id but not parents or commit date,
  so every commit is "unknown": runs apply in arrival order and the run timestamp stands in for the
  commit date. Out-of-order protection is therefore inactive in a real deployment until a source (the
  Git host's API, or a mirror) is wired.
- **Quarantine keeps metadata only.** The bundle still carries raw contributor identity, so it is not
  stored; it stays on the intake topic until retention expires. Inspect it there.
- **Suppression warnings are only logged**; there is no notification channel yet.
- **The app now needs Docker to test**: its integration tests start Kafka and PostgreSQL.
- **No Dockerfile or deployment manifest** yet.
