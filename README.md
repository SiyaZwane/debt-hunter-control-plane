# Debt Hunter Control Plane

The optional, asynchronous management and analytics control plane for
[Debt Hunter](https://github.com/SiyaZwane/debt-hunter): it ingests technical-debt findings from
CI/CD pipelines, maintains governance state, drives trend analytics, manages policy lifecycle, and
surfaces portfolio dashboards.

> **Status: ingestion pipeline built through pseudonymisation (CP-M0, CP-FR-01 – 03).** A pipeline
> can publish a run bundle and receive `202`; the bundle is authenticated, scope-checked,
> schema-validated, queued, normalised across schema versions, and stripped of contributor identity
> before it can reach a store. See the [Roadmap](#roadmap), the
> [ingestion API reference](docs/ingestion-api.md), [run normalisation](docs/normalisation.md), and
> [pseudonymisation](docs/pseudonymisation.md).

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
| `control-plane-testkit` | — | Test support: bundle fixtures, a JWT issuer with a local JWKS server, a Kafka test broker. Test scope only | — |
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

**One-time setup.** This project builds against debt-hunter, which is not yet published to a
registry, so install the pieces it needs into your local Maven repository first:

```bash
git clone https://github.com/SiyaZwane/debt-hunter.git
cd debt-hunter
./mvnw -pl cli -am install -DskipTests -Djacoco.skip=true
```

That installs `domain` (the shared model, a compile dependency) and `cli` (the runnable data-plane
jar, which the integration tests run as a separate process, exactly as a pipeline would).
`-Djacoco.skip=true` is needed because `-DskipTests` leaves JaCoCo's 80% coverage gate with no data
to check.

**Docker.** The integration tests start a real Kafka broker with Testcontainers, so a Docker daemon
must be running. With Colima, point Testcontainers at it:

```bash
export DOCKER_HOST="unix://$HOME/.colima/default/docker.sock"
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
```

Then, in this repository:

```bash
./mvnw clean verify
```

This runs compilation, unit and integration tests, Spotless (Google Java Format), SpotBugs, the
enforcer rules, and JaCoCo's 80% line-coverage gate across every module.

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
| CP-FR-01 | Ingestion API: validate, scope-check, enqueue, return `202` | Done |
| CP-FR-02 | Schema normalisation across the current and N-1 versions | Done |
| CP-FR-03 | Contributor identity pseudonymised at ingest, audited re-identification | Done, with gaps ([details](docs/pseudonymisation.md#not-done-yet)) |
| CP-FR-04 | Idempotency and finding lifecycle | Next |
| CP-FR-05 – 07 | System of record, analytics, coverage tracking | Planned |
| CP-FR-08 – 10 | Policy authoring, signing and publication, rollout stages | Planned |
| CP-FR-11 – 12 | AI explanation and remediation, with the prompt guard | Planned |
| CP-FR-13 – 16 | Work items, notifications, serve layer | Planned |
| Cross-cutting | Workload-identity scoping, retention tiering, failure isolation | Planned |
| Release gate | Offline conformance test — the whole control plane down, pipeline still delivers | Planned |

CP-AC-05 — the real data-plane CLI completing a scan, with a warning and nothing worse, while the
control plane is stopped — landed with CP-FR-01 and runs as a regression guard through every later
step, rather than waiting for the final release-gate test.

## Decisions and known gaps from CP-FR-01

- **The data plane reports a local path as `run.repository`.** The scope check is keyed on it, so it
  cannot work outside tests until the data plane emits a real repository identifier. That is a change
  to `debt-hunter`, not to this repository. See
  [Repository identity](docs/ingestion-api.md#repository-identity).
- **The data plane's production `scan` currently wires no analysis engines**, so real reports have an
  empty `engines` list and no findings. Also a `debt-hunter` change.
- **The v0 (N-1) schema is provisional.** No real v0 ever existed. See
  [Schema versions](docs/ingestion-api.md#schema-versions).
- **CP-BC-04 ("burst throttling") is an interpretation.** The prompt names it without specifying it.
- **`SecurityAlertPublisher` lives in `control-plane-egress`, not `-api`** as the prompt places it:
  the prompt has `api` depending on `egress`, so an `egress` implementation of an `api`-owned
  interface would be a dependency cycle.
- **The queue listener is built and tested but not started by the application** until there is a
  downstream stage (CP-FR-02) to hand bundles to.

## Decisions and known gaps from CP-FR-02

- **The N-1 bridge is provisional**, as is the v0 schema it reads.
- **`confidence` defaults to `1.0`** for a bundle that does not state it. See
  [Defaults](docs/normalisation.md#defaults).
- **A quarantined bundle spends its `run.id`**: a corrected resubmission under the same id would be
  discarded as a duplicate until CP-FR-04's idempotency guard. See
  [the known gap](docs/normalisation.md#known-gap-a-quarantined-run-id-is-spent).
- **The queue listener is still not started by the application.** Normalised bundles carry raw
  contributor identity, so nothing may consume the queue until the pseudonymiser (CP-FR-03) and a
  store exist to receive them.

## Decisions and known gaps from CP-FR-03

- **CP-BC-05 and CP-BC-07 are named, not specified, in the prompt.** Interpreted as: identities
  that differ only cosmetically (case, whitespace, Unicode form) are one person (05); blank or absent
  identities are dropped and counted rather than pseudonymised into a shared bucket (07).
- **Evidence is default-deny**, so a new engine's evidence is withheld until its keys are reviewed.
- **Key Vault is tested against a fake**; the Azure SDK binding is not written.
- **CP-AC-11 is deferred** to the serve layer and **CP-AC-12 is a surface guard** until the query API
  exists.
- **`CpAc09` lives in `control-plane-ingest`, not `-store`** as the prompt places it: it drives the
  whole pipeline, and `store` cannot depend on `ingest`.
- See [pseudonymisation](docs/pseudonymisation.md#not-done-yet) for the rest.
