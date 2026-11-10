# Debt Hunter Control Plane

The optional, asynchronous management and analytics control plane for
[Debt Hunter](https://github.com/SiyaZwane/debt-hunter): it ingests technical-debt findings from
CI/CD pipelines, maintains governance state, drives trend analytics, manages policy lifecycle, and
surfaces portfolio dashboards.

> **Status: scaffolding (CP-M0).** The module structure, build, quality gates, and Spring Boot
> entry point exist and build green. No control-plane behaviour is implemented yet — see
> [Roadmap](#roadmap).

## The one constraint that overrides everything else

> No component of the control plane may expose a synchronous interface that a pipeline or build job
> waits on. A pipeline that publishes a run bundle receives HTTP `202` and moves on. A pipeline that
> cannot reach the control plane completes normally and logs a warning.

Debt Hunter's `scan` is the *data plane*: deterministic, offline-capable, and gated purely on its
own policy evaluation. This repository is the *control plane* beside it. The control plane is
strictly additive — everything it provides is optional, and the data plane never depends on it.
This will be enforced by a dedicated offline conformance test that acts as a release gate.

## Relationship to the data plane

This is a separate repository from `debt-hunter`. The two share the canonical model (`Finding`,
`ScanResult`, `Severity`, `Fingerprinter`, ...), which this project consumes as the
`com.debthunter:domain` artifact rather than copying it, so the two cannot silently drift apart.

## Modules

Nine Maven modules, grouped into the zones of the control plane. Dependencies point inward toward
`control-plane-store` and `domain`; nothing depends on `control-plane-api` or `control-plane-app`.

| Module | Zone | Responsibility | Depends on |
|---|---|---|---|
| `control-plane-store` | B | System of record: registry, runs, findings, transition log, suppressions, baselines, policy catalogue, evidence | `domain` |
| `control-plane-ingest` | A | Normaliser, pseudonymiser, idempotency, finding-lifecycle state machine | `domain`, `store` |
| `control-plane-analytics` | C | Metric projection, trends, drift, coverage and adoption | `domain`, `store` |
| `control-plane-governance` | D | Policy authoring, simulation, signing and publication, rollout stages | `domain`, `store` |
| `control-plane-ai` | E | Prompt guard, explanation service, remediation proposals, provenance | `domain`, `store` |
| `control-plane-egress` | F | Work-item orchestrator, transactional outbox, notifications | `domain`, `store` |
| `control-plane-serve` | G | Read models, dashboards, query API, evidence retrieval | `domain`, `store`, `analytics` |
| `control-plane-api` | — | REST edge: ingestion, query, squad view, portfolio | all of the above |
| `control-plane-app` | — | Spring Boot application assembly | `api` |

## Building

Requires Java 21. Maven is provided by the wrapper.

**One-time setup:** this project builds against debt-hunter's `domain` module, which is not yet
published to a registry, so install it into your local Maven repository first:

```bash
git clone https://github.com/SiyaZwane/debt-hunter.git
cd debt-hunter
./mvnw -pl domain install -DskipTests -Djacoco.skip=true
```

(`-Djacoco.skip=true` is needed because `-DskipTests` leaves JaCoCo's 80% coverage gate with no
data to check.) Then, in this repository:

```bash
./mvnw clean verify
```

This runs compilation, tests, Spotless (Google Java Format), SpotBugs, the enforcer rules, and
JaCoCo's 80% line-coverage gate across every module.

## Rules

- **Spring Boot only in `control-plane-app`.** All business logic lives in non-framework modules.
  This is enforced at build time (Spring Boot artifacts on any other module's compile or runtime
  classpath fail the build), the same way debt-hunter enforces domain purity. Test-scope Boot test
  slices are allowed elsewhere.
- **Every persistence operation is pseudonymised first.** Contributor identity must never be
  written to any store without passing through the identity pseudonymiser.
- **One commit per control-plane functional requirement**, titled `feat(CP-FR-XX): <short title>`,
  with a body listing every `CP-AC` satisfied.
- **Tests are named by acceptance criterion**, e.g. `cpAc01_validBundleReturns202()` and
  `cpBc01_wrongScopeReturns403()`.
- **No `@Disabled` tests.** Every test passes or the step is not complete.
- **No `System.exit()` in library code.**
- **Test the real thing** wherever a real implementation is fast and reliable: Testcontainers for
  Kafka, PostgreSQL, and Redis; interfaces with in-memory fakes for services that need real cloud
  credentials (Key Vault, Entra, Azure OpenAI, an OCI registry), with any real-cloud tests tagged
  `platform` and excluded by default.

## Roadmap

| Step | Scope | State |
|---|---|---|
| CP-M0 | Module structure, build, quality gates, Spring Boot entry point | Done |
| CP-FR-01 | Ingestion API: validate, scope-check, enqueue, return `202` | Next |
| CP-FR-02 – 04 | Schema normalisation, pseudonymisation, idempotency and finding lifecycle | Planned |
| CP-FR-05 – 07 | System of record, analytics, coverage tracking | Planned |
| CP-FR-08 – 10 | Policy authoring, signing and publication, rollout stages | Planned |
| CP-FR-11 – 12 | AI explanation and remediation, with the prompt guard | Planned |
| CP-FR-13 – 16 | Work items, notifications, serve layer | Planned |
| Cross-cutting | Workload-identity scoping, retention tiering, failure isolation | Planned |
| Release gate | Offline conformance test — the whole control plane down, pipeline still delivers | Planned |

The offline conformance test is a release gate, but a minimal version should land immediately
after CP-FR-01 and run as a regression guard through every later step, rather than waiting until
the end.
