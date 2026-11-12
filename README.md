# Debt Hunter Control Plane

The optional, asynchronous management and analytics control plane for
[Debt Hunter](https://github.com/SiyaZwane/debt-hunter): it ingests technical-debt findings from
CI/CD pipelines, maintains governance state, drives trend analytics, manages policy lifecycle, and
surfaces portfolio dashboards.

> **Status: ingestion pipeline and system of record built and wired into a running application (CP-M0, CP-FR-01 – 05).** A pipeline can publish a run
> bundle and receive `202`; the bundle is authenticated, scope-checked, schema-validated, queued,
> normalised across schema versions, stripped of contributor identity, de-duplicated, and applied to
> each finding's lifecycle, with durable storage on PostgreSQL. See the [Roadmap](#roadmap), the
> [ingestion API](docs/ingestion-api.md), [normalisation](docs/normalisation.md),
> [pseudonymisation](docs/pseudonymisation.md), [lifecycle](docs/lifecycle.md), and the
> [system of record](docs/system-of-record.md), [running the control plane](docs/running.md), the [administration API](docs/admin-api.md), [trend analytics](docs/analytics.md), and [coverage tracking](docs/coverage.md).

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
| `control-plane-testkit` | — | Test support: bundle fixtures, a JWT issuer with a local JWKS server, a Testcontainers PostgreSQL. Test scope only | — |
| `control-plane-store` | B | System of record: registry, runs, findings, transition log, suppressions, baselines, policy catalogue, evidence | `domain` |
| `control-plane-ingest` | A | Normaliser, pseudonymiser, idempotency, finding-lifecycle state machine | `domain`, `store` |
| `control-plane-analytics` | C | Metric projection, trends, drift, coverage and adoption | `domain`, `store` |
| `control-plane-governance` | D | Policy authoring, simulation, signing and publication, rollout stages | `domain`, data-plane `policy`, `store` |
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

**Docker.** The integration tests start a real PostgreSQL with Testcontainers, so a Docker daemon
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
  PostgreSQL (and Redis when it arrives); interfaces with in-memory fakes for services that need real cloud
  credentials (Key Vault, Entra, Azure OpenAI, an OCI registry), with any real-cloud tests tagged
  `platform` and excluded by default.

## Roadmap

| Step | Scope | State |
|---|---|---|
| CP-M0 | Module structure, build, quality gates, Spring Boot entry point | Done |
| CP-FR-01 | Ingestion API: validate, scope-check, enqueue, return `202` | Done |
| CP-FR-02 | Schema normalisation across the current and N-1 versions | Done |
| CP-FR-03 | Contributor identity pseudonymised at ingest, audited re-identification | Done, with gaps ([details](docs/pseudonymisation.md#not-done-yet)) |
| CP-FR-04 | Idempotency guard and finding lifecycle state machine | Done, with gaps ([details](docs/lifecycle.md#not-done--assumptions)) |
| CP-FR-05 | System of record: PostgreSQL stores, registry, suppressions, baselines, policy catalogue, evidence | Done |
| App wiring | Database, registry, pipeline, queue listener and housekeeping assembled in `control-plane-app` | Done ([details](docs/running.md)) |
| Admin API | Onboard repositories and grant pipelines over HTTP, role-protected and audited | Done ([details](docs/admin-api.md)) |
| CP-FR-06 | Materialised read models, trend analytics, architecture drift, remediation effectiveness | Done ([details](docs/analytics.md)) |
| CP-FR-07 | Coverage and adoption tracking: silent, never-onboarded, degraded, drifted | Done ([details](docs/coverage.md)) |
| CP-FR-08 | Policy authoring, validation and read-only impact simulation | Done ([details](docs/policy-simulation.md)) |
| CP-FR-09 | Policy signing and publication | Done ([details](docs/policy-publication.md)) |
| CP-FR-10 | Rollout stage management | Done ([details](docs/rollout-stages.md)) |
| CP-FR-11 | AI explanation with the prompt guard (rule templates now, model later) | Done ([details](docs/ai-explanation.md)) |
| CP-FR-12 | AI remediation proposals | Planned |
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
- ~~A quarantined bundle spends its `run.id`~~ — fixed in CP-FR-04.
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

## Decisions and known gaps from CP-FR-04

- **Pull-request runs move no lifecycle state.** CP-BC-09 is not specified in the prompt; this is the
  conservative reading. See [lifecycle](docs/lifecycle.md#pull-request-runs-cp-bc-09).
- **A late run is recorded but not replayed**, so history can be thinner than in-order arrival would
  give, while current state stays correct.
- **No commit-graph source exists**, so today every commit relation is `UNKNOWN` and runs apply in
  arrival order. The data plane's bundle carries neither parents nor commit date.
- **The bundle has no explicit scope field**; "scope-limited" is inferred from `baseCommit` /
  `pullRequest`.
- **Stores are in-memory** until CP-FR-05.
- `CpBc11` lives in `control-plane-ingest`, not `-store`.
- The suppression-expiry arrow in the spec's diagram points to `RESOLVED`; CP-AC-20 says `OPEN`, so the
  criterion was followed.

## Decisions and known gaps from CP-FR-05

- **PostgreSQL, plain JDBC, Flyway** rather than Spring Data JPA on Azure SQL, to keep Spring out of
  the store module. Moving to SQL Server is a port.
- **The pseudonym directory is still in-memory** and deliberately has no table in this schema.
- **The suppression warning falls due at the earlier of `max − 7` days and `expiry − 7` days**, so a
  short suppression is not warned about after it has lapsed. The prompt's formula alone would miss it.
- **The transition log's append-only rule is enforced by a database trigger**, not only by the code.
- The lifecycle now reads the real suppression register; `SuppressionLookup` moved to the store module.
- The ArchUnit rule about the store was restated as "the store cannot depend on ingest", since the
  JDBC store legitimately uses `javax.sql` and Jackson.
- `ratifiedBy` on policy bundles is not pseudonymised (an approver is not a contributor).

## Decisions and known gaps from the app wiring

- **The app needs a database, a pseudonymisation key and token config, and refuses to start without
  any of them.** See [running](docs/running.md).
- **Repository scope is no longer configuration**: the registry decides, and an administrator manages
  it through the [administration API](docs/admin-api.md).
- **The pseudonym key comes from configuration**, not Key Vault, and **the directory is in memory**.
- **There is no commit-graph source**, so out-of-order protection is inactive in a real deployment.
- **Quarantine keeps metadata only**: the bundle carries raw identity and stays on the intake topic.
- **Suppression warnings are only logged**; there is no notification channel yet.
- **The app's integration tests now need Docker** (PostgreSQL).
- **No Dockerfile or deployment manifest yet.**

## Decisions and known gaps from the administration API

- **Administration is gated by a role in the token** (`ControlPlane.Admin`, configurable), checked by
  the security chain. A pipeline's token gets 403 on every admin route.
- **Changes are audited before they are applied, and refused if the audit cannot be written.** The
  audit trail is a log line, not a durable table.
- **Input is rejected, not cleaned up** (including padded whitespace).
- **One role does everything**: onboarding and granting are not separated, and there is no second
  approver.
- **Only the registry has admin routes**; policy, suppressions and baselines do not yet.
- **Repository identifiers may contain slashes in the URL**, but a leading slash (today's local-path
  identifiers) is awkward.

## Decisions and known gaps from CP-FR-06

- **The projector only reads the live stores**, enforced by an ArchUnit rule, and it is isolated in
  the running app: a failing, hung or stopped projector leaves ingest untouched (CP-AC-32).
- **Batches commit atomically with their checkpoint**, and the projector refuses to continue if a
  checkpoint did not advance, rather than double-counting.
- **"Architecture drift duration" is an interpretation**; the prompt does not define it.
- **Policy pressure needed the run's policy outcome**, now stored on the run (migration V3).
- **No HTTP API yet**: the engines are library code until the serve layer (CP-FR-15/16).
- **Pull-request, late and superseded runs are not projected.**
- Trends inherit the missing commit-graph source: commit dates fall back to run timestamps.

## Decisions and known gaps from CP-FR-07

- **Only `REPORTING` is healthy.** A repository nobody onboarded, or one that has gone quiet, is never
  counted as clean.
- **Precedence is `SILENT` > `DEGRADED_REPORTING` > `VERSION_DRIFT` > `REPORTING`**, an interpretation:
  the prompt says "one of five states" without ordering them. The reasons list names every condition.
- **A failed estate listing is reported as unavailable, never as an empty estate.**
- **"Consistently degraded" needs a full window of runs** (default 3), so a brand-new repository is
  not flagged until it has that many.
- **The GitHub and Azure DevOps providers have only been tested against fakes** of their APIs.
- **No HTTP API and no state history yet**; only the latest report is kept.
- The run store now records when each run was **received** and its **history depth** (migration V5).

## Decisions and known gaps from CP-FR-08

- **The data plane's own parser and evaluator are reused**, not reimplemented, so a simulated verdict
  is the verdict a pipeline would give. `control-plane-governance` now depends on the data plane's
  `policy` artifact.
- **Schema errors stop a simulation; "impossible threshold" does not.** It is a warning, because
  quantifying it is what a simulation is for. It is only raised with at least 3 observed repositories.
- **The parser reports one schema problem at a time**, the first it finds, rather than all of them.
- **A repository that cannot be simulated is never counted as passing.** It is named with a reason and
  counted in the share of the estate that could not be simulated.
- **Only complete, applied runs are replayed**; degraded, pull-request, superseded and late runs are
  not, because they understate a repository's debt. Only the main-branch rules are replayed.
- **The content of a policy already in force is a port** (`PolicyBundleSource`): the catalogue keeps a
  version and digest, not the text. Without it the delta is `UNKNOWN`. CP-FR-09 supplies the real one.
- **Retention is not modelled.** An empty finding list could be a clean run or purged evidence; the
  store cannot tell them apart yet, so only a failed read is reported as unavailable evidence.
- No HTTP API yet, and the `StoreBackedRetainedEvidence` adapter is not wired into the app.

## Decisions and known gaps from CP-FR-09

- **Order is the safety property.** Validate, sign, verify the signature just produced, push, check
  the registry's digest equals ours, and only then write the catalogue. A failure before the catalogue
  step leaves the catalogue untouched; a catalogue failure leaves an unreferenced artefact that a retry
  adopts (identical pushes are idempotent). `PublishOutcome.Failed` says which stage stopped and
  whether the artefact is already in the registry.
- **Real ORAS and Key Vault are not bound.** `OrasRegistryClient` and `KeyVaultSigner` are interfaces;
  the only implementations are `InMemoryRegistryClient` and `LocalSigner` (real ECDSA P-256, so
  public-key verification behaves as it will in production). Nothing here has talked to a registry or
  to Key Vault. Binding them needs credentials and a registry to test against.
- **Signature scheme is mine.** The spec says "signs with Key Vault"; it does not say what a signature
  covers or how it is stored. I sign the `sha256:` digest string and publish the signature beside the
  bundle. Real OCI practice (cosign / Notation, signature as a referrer artefact) may be preferable.
- **The consuming side is separate.** `PolicyBundleFetcher` takes a registry, a verifier and a repository
  name, and no catalogue or control-plane service, so a pipeline can pull its policy with the control
  plane off. It refuses content that does not match its digest, a different artefact than was asked for,
  or a signature that does not verify, and writes no file in those cases. `SignatureVerifier` is split
  from `KeyVaultSigner` so a consumer never holds signing ability.
- **Retention is a rule, not a job.** The registry client has no delete (tested), and
  `SupersededRetention.mustRetain` encodes "two major tool version cycles". Nothing calls it yet,
  because nothing removes anything; a registry-side retention job should.
- **Closes a CP-FR-08 gap.** `RegistryPolicyBundleSource` reads a published version's content through
  the catalogue digest and the verifying fetcher, so the simulator's delta can be real once wired.
- **Publication is serialised per process only.** Across instances, registry tag immutability is what
  stops a double publish of one version. The catalogue's `register` is still find-then-save.
- **Not wired into the app.** No publish endpoint, no configuration, no bean. `control-plane-app` has
  a compile dependency on governance and uses it in the CP-AC-47 test only.
- **CP-AC-44 to 47 are defined only by test names in the spec,** so their meaning was interpreted
  (see `docs/policy-publication.md`).

## Decisions and known gaps from CP-FR-10

- **There are now two notions of "stage", and that is the main thing to decide.** The registry's
  `rolloutStage` is free text set at onboarding (`"enforce"`, `"warn"` in tests). The new managed
  stage (`RolloutStage` 1 to 4, with evidence, owner, history) is a separate record. Coverage reports
  show the managed one; the CP-FR-08 simulator still groups by the registry's text. I did not touch the
  registry field. I recommend retiring it and reading the stage from the managed record, which needs
  your call on migrating existing values.
- **Stage meanings are minimal on purpose.** The spec fixes only two behaviours: stage 1 observes, and
  work items start at stage 3 ("stage 3+", and CP-AC-69 says stage 2 creates none). I made it four
  stages; the number four, and what stage 4 means, are my choice. Everything else about a stage is the
  organisation's, supplied as `StagePolicy` (exit criteria per stage, maximum hold per stage). There
  is no default policy and no configuration binding yet.
- **Forward is one stage at a time and needs evidence.** Every exit criterion for the stage being left
  must be present with non-blank evidence; the event records approver, date and the criteria relied on.
  Nothing checks that the evidence is *true* (a URL is accepted as a URL). Moving back needs only a
  reason, and starts a new stay.
- **Escalation is once per stay, at-least-once delivery.** A sweep (`escalateOverdue`) marks the record
  after the sink accepts it, so a crash between the two repeats the escalation rather than losing it.
  An approved, unexpired exemption excuses the hold; when it expires the repository is due again.
  Nothing calls the sweep on a schedule yet, and the only sink is the interface: no notification is
  wired (CP-FR-14 digests are the natural consumer).
- **The "exception record" is called an exemption** in code, because a class named `...Exception` trips
  SpotBugs and confuses readers. It is the same thing.
- **`isWorkItemCreationEnabled` throws if the store is unreadable** rather than answering. A caller must
  treat that as "do not create now", never as permission. A repository not in the rollout is `false`.
- **Enrolment is not automatic.** Onboarding a repository through the admin API does not enrol it in the
  rollout, so a freshly onboarded repository has no stage (shown as none) and no work items. There is
  no admin or HTTP surface for stage changes at all yet.
- **CP-AC-49 is tested at the gate.** "Creates work item" belongs to the egress step (CP-FR-13), which
  does not exist yet; the test proves the gate opens at stage 3 from PostgreSQL, not that an item is
  created. CP-AC-52 is tested through the coverage report, because the serve layer (CP-FR-15) has no
  adoption endpoint yet.
- **Coverage reads all stages in one query per reconciliation** and, if that fails, the report says so
  in its warnings and shows no stages instead of failing.

## Decisions and known gaps from CP-FR-11

- **No model is wired. Explanations come from rule templates.** This follows the decision to avoid
  pay-per-use for now. `RuleBasedExplainer` fills a template from the finding's own message, severity
  and retained evidence. The model path (`ModelExplainer` behind `PromptGuard`) is fully built and
  tested against a fake model, but nothing in the running application dispatches to a model. Turning
  one on means adding it to the explainer chain.
- **Template text is labelled `modelGenerated: false`.** The spec says all generated text carries
  `true`; claiming model authorship for a template would be false. Only model output carries `true`,
  and which it is cannot disagree with the recorded source.
- **A model that is down falls through to the template** (so a finding always has an explanation), where
  the spec says to omit it. If nothing at all can explain, the answer is empty with no placeholder.
- **Source-bearing text never crosses repositories.** An explanation written from a prompt that
  contained source is not cached, and a request waiting on another repository's in-flight call is not
  handed its source-informed answer. The cache key also includes the model and prompt version
  (a small addition to the spec's fingerprint-and-rule key), so a new model does not reuse old text.
- **The guard is a boundary by construction.** Only `PromptGuard` can make a `GuardedPrompt`, only it
  calls `ModelClient`, and the build checks both (ArchUnit). The prompt is built from an allow-list of
  fields, so contributors, fingerprints and scores have no path into it. Source is fetched only after a
  definite opt-in; an unreadable opt-in means restrictive. The dispatch is logged before it is made and
  not made if it cannot be logged.
- **`control-plane-ai` is kept off the pipeline path by the build.** A Maven enforcer rule bans it from
  every module except `control-plane-serve` and `control-plane-app`.
- **Deferred to when a model is turned on:** the Redis cache (an in-memory cache exists, so CP-AC-55
  is tested in memory, not against Redis), the real model client, the durable dispatch-log table, the
  store-backed opt-in (the opt-in lookup and source provider are ports with test fakes only), and a
  call budget. CP-AC-53 to 55 are tagged integration but use in-process collaborators.
- **Templates are only as good as the catalogue.** The data plane's rule ids are `codemaat.*`,
  `architecture.<rule>` and `sonar.<rule>`; the last two are described by family. The control plane
  retains only a few evidence keys (`changeFrequency`, `complexityDelta`, `calculation`, `engine`,
  `import`, `rule`, `originalCategory`), so templates lean on the finding's own message.
- **`FindingDetailService` (serve) is a small slice of CP-FR-15** pulled forward so CP-AC-57 has
  something real to test. It has no HTTP endpoint and no caller authorisation: the layer above must
  decide who may see a repository.
- **Not wired into the app:** no beans, no endpoint.
- **I removed two unused dependencies from `control-plane-api`** (`control-plane-serve` and
  `control-plane-ai`), because the new enforcer rule showed they would have put the model code on the
  ingestion API's classpath. Nothing imported them. When CP-FR-15 adds read endpoints, decide whether
  they live in `api` (then relax the rule for it) or a separate module.

## Decisions and known gaps from replacing Kafka with a PostgreSQL queue

- **Kafka is gone.** Intake now uses a durable queue in PostgreSQL (`work_queue`, migration V9), behind
  the same `RunIntakeQueuePublisher` and `RunBundleReceiver` ports. The queue is generic (ordering per
  key, leases, retry with backoff, dead-lettering, de-duplication, delayed release) so CP-FR-12, 13 and
  14 can use it too. The spec's technology table says Event Hubs or Kafka; this is a deliberate
  departure, in the same spirit as choosing PostgreSQL over Azure SQL.
- **Bundles are encrypted at rest.** A bundle still holds real contributor identities, so it is sealed
  with AES-256-GCM before it is stored, bound to its repository, and erased when the item is finished.
  A bundle that is set aside (unreadable, failed too often, or quarantined by the pipeline) keeps its
  encrypted payload for `intake.dead-retention` (7 days) and is then deleted. The Kafka topic held these
  payloads in the clear, so this is a net improvement. The key is `intake.encryption-key`, required to
  start, from configuration until the Key Vault binding exists. There is no key rotation yet: the
  payload format has a version byte for it.
- **A dependency being down is a hold, not a failure.** The first version of the new worker would have
  set bundles aside when the key store was down; CP-BC-06's test caught it. Key store and database
  failures now put the bundle back without counting the attempt, indefinitely. Only a bundle's own
  failures count towards `intake.max-attempts`.
- **Retry is bounded, which Kafka's was not.** A bundle that keeps failing for its own reasons is set
  aside after `max-attempts` and its repository carries on. That changes ordering for that repository:
  later bundles can apply before the abandoned one. The lifecycle already copes with late runs.
- **A PostgreSQL outage returns 503, not 500.** The scope check reads the registry in the database; it
  was an unhandled 500. It is now a prompt `503 registry_unavailable` with `Retry-After`. Ingestion
  already depended on PostgreSQL before this change (for the scope check), so replacing Kafka does not
  make an outage worse; it removes a second system that also had to be up.
- **Blocking database calls are off the request threads and bounded.** The scope check and the queue
  write run on Reactor's bounded-elastic pool with a timeout (`intake.max-wait`), and the connection
  pool and socket have their own timeouts. A timed-out write can still commit afterwards; a retry is
  de-duplicated by the receiver.
- **Not changed:** the admin API also makes blocking database calls on request threads. It is not on the
  pipeline's path, so I left it, but it should get the same treatment.
- **Not done:** a metric or alert on set-aside bundles (they are logged at error level), an index review
  under real volume, a polling alternative such as `LISTEN/NOTIFY`, and a Key Vault key.
