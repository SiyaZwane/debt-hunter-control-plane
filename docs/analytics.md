# Trend analytics and drift

How the control plane answers "is this repository getting better or worse, and how fast?". Everything
here is **derived** from the system of record, built **asynchronously**, and **never consulted by
ingest**.

```
 system of record (live)                          analytics (derived, rebuildable)
 ───────────────────────                          ───────────────────────────────
 transition_log ──┐                               rm_rule_daily    created/resolved/regressed per rule per day
 run            ──┼──►  MetricProjector  ──►      rm_run_snapshot  findings by severity, per commit
 finding        ──┘     (reads only)               rm_hotspot       change frequency per file per commit
                                                  rm_open_finding / rm_resolution   time to resolution
                                                          │
                              TrendEngine · ArchitectureDriftTracker · RemediationEffectivenessCalculator
                                                          │
                                              every result: { value, stale, lastUpdated }
```

## The projector never touches the live write path

`MetricProjector` follows the transition log and the run store forward from a checkpoint, reads each
run's findings, and writes only to the read models. This is enforced, not just intended:

- an ArchUnit test fails the build if any analytics class calls `append`, `save`, `putAll` or
  `saveRun` on the live stores, or depends on `ingest` or `api`;
- ingest does not depend on analytics (Maven would not build it);
- in the running application it is scheduled on its own daemon thread, absorbs its own failures, and
  shares no lock with ingest.

**CP-AC-32** is proven against the whole application with the real durable queue and PostgreSQL: with the
projector failing on every pass, hanging forever, or stopped, publishing still ingests, and the only
effect is that query results report themselves stale.

## Exactly once, and refusing to double-count

Each step reads a batch, derives its effect, and commits that effect **together with the new
checkpoint in one transaction**. A crash leaves a batch fully applied or not at all, and a restarted
projector resumes from the checkpoint. A test forces a database error part-way through a batch and
checks that not even the checkpoint moved.

After every commit the projector verifies the checkpoint really advanced. If a read model silently
lost it, the batch would be replayed and every count in it doubled, so it fails loudly instead.

## What is computed

| Metric | From | Where |
|---|---|---|
| Finding count by severity over time | one snapshot per commit | `TrendEngine.severityOverTime` |
| New-finding, resolution and regression **rate** | per-rule daily counts | `TrendEngine.findingRates` |
| **Hotspot movement** | change frequency per file per commit | `TrendEngine.hotspotMovement` |
| **Complexity trajectory** | summed complexity change per commit, running total | `TrendEngine.complexityTrajectory` |
| **Architecture drift**: rate, how long it has been accumulating, age of open violations | snapshots, open findings | `ArchitectureDriftTracker` |
| **Median time to resolution**, overall and per severity | resolution records | `RemediationEffectivenessCalculator` |
| **Regression rate per rule** | per-rule daily counts | `RemediationEffectivenessCalculator` |
| **Policy pressure**: share of runs where policy failed or would have | run policy outcome | `RemediationEffectivenessCalculator` |

Windows are half-open, `[from, to)`. Daily counts are whole UTC days. Time is the **commit date** (the
run's timestamp when no commit date is known), never the wall clock, so replaying history gives the
same answers.

A finding resolved and then regressed is measured per episode: the clock for a resolution starts at
the first observation or at the last reappearance, whichever is later.

## Staleness (CP-AC-79)

Read models are eventually consistent, and say so. Every result is a `Trended<T>`:
`{ value, stale, lastUpdated }`. `stale` is true when the read models were last brought up to date
longer ago than `analytics.stale-after` (default 15 minutes) **or never**. A pass that finds nothing
new still refreshes the timestamp, so an idle but healthy projector is not mistaken for a dead one.
A caller that shows these numbers to a person should say when they are stale.

## Configuration

| Property (under `debthunter.controlplane.analytics.`) | Default | |
|---|---|---|
| `projector-enabled` | `true` | Off: read models go stale, ingest is unaffected |
| `projection-interval` | `PT30S` | How often the projector catches up |
| `stale-after` | `PT15M` | Age at which results are flagged stale |
| `batch-size` | `500` | Runs or transitions per transaction |

## Interpretations and decisions

- **"Architecture drift duration" (CP-AC-33)** is not defined in the prompt. It is implemented as the
  length of the current uninterrupted run of non-decreasing violation counts that ends in a net
  increase, plus the age of the violations still open.
- **"Policy pressure"** is the share of runs whose policy outcome was `FAILED` or `WOULD_FAIL`. That
  needed the run's policy outcome, which was not stored; it now is (migration V3).
- **Pull-request, late and superseded runs are not projected**: they are not points in the main
  history, so they do not distort trends. A re-run of a commit replaces that commit's snapshot.
- **Regression rate** is regressions divided by resolutions (undefined, not zero, when nothing was
  resolved).
- Analytics owns its schema (`V4`). Flyway merges migrations from every module on the classpath, so
  version numbers must be coordinated across modules.

## Not done

- **No HTTP API for any of this.** The engines are library code; the query API and dashboards are the
  serve layer (CP-FR-15/16). Nothing yet shows these numbers to anyone.
- **Read models are not partitioned or pruned.** They grow with history.
- **A finding resolved before the projector first ran, with no recorded start, is counted but has no
  duration.** A projector started on an existing database processes from the beginning, so this only
  arises from gaps in the log.
- **The commit graph still has no source**, so commit dates fall back to run timestamps and ordering
  is arrival order. Trends inherit that.
- **No rebuild command**: the read models can be rebuilt by clearing them and the checkpoints, but
  there is no tool for it.
- `CpAc29`, `CpAc30`, `CpAc31`, `CpAc33` and `ReadModelStalenessTest` are in `control-plane-analytics`
  as the prompt places them; `CpAc32` is in `control-plane-app`.
