# Idempotency and the finding lifecycle

After pseudonymisation, `LifecycleSink` decides what a run means for each finding. Everything it
does is designed around two facts: bundles are delivered at least once and out of order, and "this
finding is gone" is a claim that is easy to get wrong.

## Order of operations

```
classify (IdempotencyGuard) ─ DUPLICATE ─► discard
   │
   ├ scope-limited (PR diff) ─► record as SCOPE_LIMITED; move nothing
   ├ commit is an ancestor of the one already applied ─► record as LATE; move nothing
   └ otherwise: resolve transitions ─► append to log ─► update states ─► record run (last)
```

**The write order is the design.** The transition log is written first; if that fails nothing else
changes and the bundle is held and retried (CP-BC-11), so state never moves without a record of why.
Event ids are deterministic (`run|fingerprint|step`) and appending a known id is a no-op, and the run
is recorded last, so a failure at any later step is repaired by running the same bundle again.

## Idempotency (CP-AC-21, CP-AC-22)

Two keys:

1. **`runId`** — seen before means a redelivery: discard. This is checked against the persisted run
   store, so it survives a restart that wiped the receiver's in-memory memory.
2. **Composite key** `sha256(repository, commit, projectSlice, toolVersion)` — a *new* run id with a
   known composite key is a re-run of the same analysis: it supersedes the earlier run, and both are
   retained. The parts are length-prefixed, not concatenated, because plain concatenation lets
   `("ab","c")` collide with `("a","bc")`.

A re-run of the same commit is **not a second observation**: it never moves `NEW` to `OPEN`, because
`OPEN` means "seen again at a later commit". It can still resolve or regress.

The guard is read-only. A run counts as seen only once it has been fully applied, so a half-applied
run is retried, not skipped. This also closes the CP-FR-02 gap: a bundle that is quarantined is not
marked seen, so a corrected bundle under the same run id is processed.

## The state machine (CP-AC-14 to 20)

An explicit table in `FindingState.canMoveTo`, enforced by `FindingTransitionEvent` itself (an illegal
transition cannot be constructed).

| From | To | When |
|---|---|---|
| — | `NEW` | first observed |
| `NEW`, `REGRESSED` | `OPEN` | observed again **at a later commit** |
| `NEW`, `OPEN`, `REGRESSED` | `RESOLVED` | absent from a **complete** run |
| `RESOLVED` | `REGRESSED` | observed again (this is not `NEW`) |
| `NEW`, `OPEN`, `REGRESSED` | `SUPPRESSED` | an active suppression covers it |
| `SUPPRESSED` | `OPEN` | the suppression expired as of the commit date |

`FindingLifecycleResolver` is a pure function of (current states, what the run observed, whether it
was complete, the commit date, the suppressions), so every rule is tested without a store or queue.

**Resolution needs a complete run (CP-AC-17).** A run is complete only if no engine was degraded
and its scope was not limited. A finding is not gone because the engine that produces it failed. A
degraded run still *records* what it saw (a finding it observed is real); it just cannot conclude
anything is absent.

**Suppression expiry uses the commit date, never the wall clock (CP-AC-20).** Judging history by
today's date would un-suppress findings in old commits as time passes.

The spec's diagram draws the suppression-expiry arrow into `RESOLVED`; the acceptance criterion says
it returns to `OPEN`, so the table follows the criterion. An expired suppression on a finding the run
also no longer sees reopens it and then resolves it, in that order.

## Commit order (CP-BC-08)

`CommitTopologyResolver` walks the Git parent graph, never receipt time. A bundle for an *ancestor* of
the commit already applied is **late**: it is recorded (`LATE`) but moves nothing, because applying
the past on top of the present would resurrect findings a later commit already fixed.

The resolver answers `SAME`, `ANCESTOR`, `DESCENDANT`, `DIVERGENT` (parallel branches) or `UNKNOWN`
(the graph has a gap or does not know the commit). `UNKNOWN` is **applied in arrival order with a
warning**, not held: holding on a commit that may never become known would block the partition
forever, and the control plane must never make a pipeline's work wait on it.

### What a late run costs

A late run's own transitions are not replayed. So the *current* state is right (nothing is
resurrected, nothing is wrongly open) but the *history* can be thinner than if bundles had arrived in
order: a finding that existed only in a late older commit never gets a `NEW` event. Rebuilding
history by replay would need corrective events in an append-only log; this was judged not worth it.

## Pull-request runs (CP-BC-09)

A run with a `baseCommit` or `pullRequest` analysed only a diff. It cannot say a finding is gone, and
it is not a point in the main history. It is recorded as `SCOPE_LIMITED` and **moves no lifecycle
state at all**. That is conservative: a finding introduced and fixed within a PR never appears, and
one that merges is picked up by the next full run.

## Gating (CP-AC-19)

`FindingStateRepository.blockingFingerprints` returns findings that are `NEW`, `OPEN` or `REGRESSED`.
`SUPPRESSED` and `RESOLVED` do not block.

## Not done / assumptions

- **The commit graph has no source.** A bundle carries its commit id but not its parents or commit
  date. `CommitGraph` is a port; there is only an in-memory implementation. A real one (Git host API
  or mirror) is needed. Until then every relation is `UNKNOWN` and runs apply in arrival order, with
  the commit date falling back to the run's own timestamp.
- **The bundle has no scope field.** "Scope-limited" is inferred from `baseCommit`/`pullRequest`.
  The data plane should state its analysis mode explicitly.
- **Atomicity across the log, states and run store** is achieved by ordering and idempotency, not a
  transaction. The stores are now durable (PostgreSQL) in the running application.
- **The suppression register is read-only here** (`SuppressionLookup`); creating and approving
  suppressions is CP-FR-05.
- **One bundle at a time per repository** is assumed, which the intake queue provides by keying on
  repository.
- `CpBc11` lives in `control-plane-ingest`, not `-store` as the prompt places it, because it drives the
  whole pipeline.
