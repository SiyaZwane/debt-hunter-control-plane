# Architecture

## The constraint

> No component of the control plane may expose a synchronous interface that a pipeline or build job
> waits on.

A pipeline publishes a run bundle, receives `202`, and moves on. A pipeline that cannot reach the
control plane completes normally with a warning in its summary. The control plane is additive: the
data plane (`debt-hunter scan`) never depends on it.

Every design decision below follows from that. The constraint is a release gate
([how](#the-release-gate)), not just a rule.

## Zones

```
                       ┌───────────────────────────── control-plane-app (the only Spring Boot module) ─────────────────────────────┐
 pipeline ──202──► A  ingest edge ──► durable queue ──► ingest worker ──► B  system of record (PostgreSQL)
 (never waits)        control-plane-api   (in B)          control-plane-ingest   control-plane-store
                                                                                │      │       │
                                          ┌─────────────────────────────────────┘      │       └───────────────┐
                                          ▼                                            ▼                       ▼
                                  C  analytics read models                    G  serve (read APIs)      F  egress: work items, digests
                                     control-plane-analytics                     control-plane-serve        control-plane-egress
                                     (async projector, may lag)                  (reads B and C)            (reads B's transition log,
                                                                                                             writes only to the outbox)
                                  D  governance (policy, rollout)     E  AI (explain, remediate)
                                     control-plane-governance            control-plane-ai — not reached from any pipeline path
                       └─────────────────────────────────────────────────────────────────────────────────────────────────────────────┘
```

| Zone | Module | Always on? | Can be stopped without affecting ingest? |
|---|---|---|---|
| A. Ingest | `control-plane-api`, `control-plane-ingest` | yes | — it is the thing nothing may block |
| B. System of record | `control-plane-store` | yes | — |
| C. Analytics | `control-plane-analytics` | projector is switchable | yes: read models go stale and say so |
| D. Governance | `control-plane-governance` | built, not wired into the app | yes |
| E. AI | `control-plane-ai` | built; only explanation is wired, from templates | yes |
| F. Egress | `control-plane-egress` | switchable (`zones.egress`, `zones.digests`) | yes: work queues and is delivered later |
| G. Serve | `control-plane-serve` | switchable (`zones.serve`) | yes: reads answer 503 |

How zones are switched and stopped: [zones](zones.md). How the failure isolation is proved:
CP-AC-32 (projector), CP-AC-92 (serve), CP-AC-93 (egress), CP-AC-95 (stale read models).

## Rules that keep the zones apart

All are enforced at build time, not by convention.

- **Spring Boot only in `control-plane-app`.** The other modules are framework-free, so business logic
  can be tested without a context (Maven enforcer).
- **`control-plane-ai` only in serve and the app** (Maven enforcer). Nothing on the pipeline path can
  reach a model.
- **Only `IngestionController` writes to the intake queue** (ArchUnit). Nothing else can accept a bundle.
- **Ingest cannot call the other zones.** `control-plane-ingest` depends only on the store and the shared
  model, so egress, serve and analytics are not on its classpath. The projector never touches the live write
  path, and egress reads the transition log and writes only to its outbox (ArchUnit).
- **Contributor identity never reaches the system of record**: the store accepts only pseudonymised types
  (ArchUnit), and only two services may touch the pseudonym directory ([data governance](DATA-GOVERNANCE.md)).
- **Every serve service method takes a `Caller` first** and checks it before reading (ArchUnit), so a
  controller added later cannot widen access.

## The path of a run bundle

1. `POST /api/v1/runs`: authenticate the workload, rate-limit, parse, check the repository is onboarded
   and granted to this workload, validate the schema, write the bundle to the durable queue **encrypted**,
   answer `202`. Nothing after this point is waited on.
2. A worker takes it from the queue: de-duplicate, normalise across schema versions, pseudonymise
   (if the key store is unavailable the bundle is **held**, never processed without pseudonymisation).
3. Write the run, findings (pseudonyms only), evidence and the finding lifecycle transitions to the system
   of record in an append-only log.
4. Everything downstream reads from there, on its own schedule: the projector, the work-item feeder, the
   digest builder, and the read APIs.

## The release gate

`ControlPlaneOfflineConformanceTest` (CP-AC-05, CP-AC-94) starts the real application, stops it, runs the
real data-plane CLI as a separate process pointed at the dead endpoint, and asserts the pipeline's result:
exit code 0 or 1, all four reports written, a real policy verdict, and only a warning in the summary.

The build runs it in its own execution (`offline-conformance-gate`, in `control-plane-app/pom.xml`) with
every setting that could switch it off fixed, so `-DskipITs`, `-DskipTests`, `-Dit.test=` and
`-Dfailsafe.groups=` do not skip it, and the build fails if it did not run. Tests in `src/test` check that
the test exists, is not disabled, and that the gate is configured that way
(`CpAc05_OfflineConformanceIsAGateTest`). The one way past the gate is `-Dmaven.test.skip=true`, which
compiles no tests at all; do not release from such a build.

The test lives in `control-plane-app`, not the test kit, because it starts the application and the test
kit sits below it.

## Where things are not as the original plan said

- The ingest queue is a **PostgreSQL work queue**, not Kafka (see the README for why).
- There is no Redis.
- Governance publication, remediation and the model client are built and tested but **not wired** into the
  application.
