# Policy authoring and impact simulation

Before an organisation is held to a new policy, it should know what that policy would have done. This
module checks a candidate bundle and replays it against the evidence the control plane has already
retained, so the author can see which repositories it would newly fail, and why, **before** anything
is published (publication is CP-FR-09).

Everything lives in `control-plane-governance`.

## Validation: `PolicyBundleValidator`

A candidate is judged in three layers, cheapest first.

1. **Schema.** The data plane's own `PolicyBundleParser` is used, not a copy, so a bundle accepted here
   is a bundle a pipeline accepts. Input is capped at 256 KiB and `validate` never throws: whatever
   text it is given, it answers. The parser reports the **first** structural problem it finds, and
   that one is reported as it stands.
2. **Static semantics.** Things that make a well-formed bundle incoherent.
3. **Observed distribution.** Whether the estate as it stands could ever meet a threshold.

Issues are **errors** or **warnings**. Errors stop a simulation; warnings are reported and the
simulation still runs.

| Code | Level | Meaning |
|---|---|---|
| `EMPTY`, `TOO_LARGE`, `SCHEMA` | error | The text is unusable or does not parse |
| `DUPLICATE_RULE_ID` | error | The same rule id twice in one rule set (`main` and `pullRequest` are separate sets) |
| `BLANK_EXCLUDED_PATH` | error | An exclusion that names nothing |
| `NO_RULES` | warning | No rules and no history requirement: the policy blocks nothing |
| `SHADOWED_RULE` | warning | Another rule already fails whenever this one would |
| `EXCLUDED_PATH_NEVER_MATCHES` | warning | A trailing `/`, a leading `/` or `./`, or `..`: the evaluator's matcher never matches it |
| `ALL_CATEGORIES_EXCLUDED` | warning | No finding can ever count |
| `IMPOSSIBLE_THRESHOLD` | warning | Every observed repository would fail this rule |
| `DISTRIBUTION_UNAVAILABLE` | warning | The estate could not be observed, so thresholds were not checked against it |

**An impossible threshold is a warning, not an error, on purpose.** "This would fail every repository
you have" is exactly what a simulation exists to quantify; refusing to simulate it would hide the
answer. It is only raised when at least 3 repositories were observed, so a rule that fails two out of
two is not called impossible. The count is taken **after** the candidate's own exclusions and the
repositories' suppressions, by asking the real evaluator with a zero-tolerance probe rule, so it
cannot disagree with what a pipeline would do.

## Simulation: `PolicyImpactSimulator`

`simulate(candidateYaml, SimulationRequest)` returns either `Rejected` (the candidate had errors;
**nothing was read**) or `Completed` with an `ImpactReport`.

### What is replayed

For every onboarded repository, the **complete, applied** runs inside the lookback window (default
**90 days**, at most 730), newest first, up to `maxRunsPerRepository` (default 30, at most 500). The
verdict is about the **latest** of them. Older ones feed the window statistics: how many runs the
candidate would have blocked, against how many the current policy would have.

Excluded, because they have fewer findings than the repository really has and a pass computed from
them proves nothing: degraded runs, scope-limited runs (pull requests), and superseded or late runs.
The main-branch rule set is what is replayed; pull-request rules are not.

Judging uses the data plane's own `PolicyEvaluator`, over the findings exactly as stored (including
whether each was new), with suppressions as they stood at the run's commit date. A candidate's
`minimumHistoryDepth` is applied the way the data plane applies it: a shallower run fails.

### What comes out

Per repository, one of:

- **`Simulated`**: pass or fail under the candidate, every violated rule, the **delta** from the
  current policy (`NEWLY_FAILING`, `NEWLY_PASSING`, `UNCHANGED_PASSING`, `UNCHANGED_FAILING`,
  `UNKNOWN`), and the **newly blocking findings** (those the candidate counts and the current policy
  did not).
- **`NotSimulated`** with a reason: `NO_RUNS_IN_WINDOW`, `NO_COMPLETE_RUN_IN_WINDOW`,
  `EVIDENCE_UNAVAILABLE`, `HISTORY_DEPTH_UNKNOWN`.

Per estate, an `EstateSummary`: overall, **by criticality tier**, **by rollout stage**, and by both.
Every slice carries how many repositories it holds, how many were simulated, and the **share that
could not be**.

### Graceful degradation

A repository that cannot be simulated is **never counted as passing**. It is named, given a reason,
and counted in `notSimulatedShare`. When that share is above zero, the pass and fail counts describe
only the repositories that could be simulated, and the report says so (`isComplete()` is `false`).

- If the **current** policy's content is not available, the candidate is still judged but the delta is
  `UNKNOWN` and no finding is called "newly" blocking, because there is nothing to compare against.
- If a repository's evidence cannot be read, it is left out and the rest are simulated.
- If the **registry** cannot be read, the whole simulation fails. An unreadable estate is not an empty
  one, and a report over zero repositories would look like good news.

## It changes nothing

Proved three ways:

1. **Shape.** The simulator takes a `RetainedEvidence` port that has four methods, all reads. It has no
   reference to any store with a write method, and an architecture test holds the one adapter that
   does (`StoreBackedRetainedEvidence`) to read calls.
2. **Database.** CP-AC-43 fingerprints every table before and after several simulations, including
   ones that reject, and the fingerprint is shown to change when something is really written.
3. **Permissions.** The simulator also runs over PostgreSQL sessions set read-only, so a write would
   be an error, not a silent no-op.

## Not done here

- No HTTP API for authoring or simulating. The serve layer (CP-FR-15/16) and the admin routes come
  later.
- The policy catalogue stores a bundle's version and digest but **not its text**, so where the content
  of a bundle already in force comes from is a port (`PolicyBundleSource`) with an in-memory
  implementation. CP-FR-09 (signing and publication) is where the content is stored.
- Whether a bundle version is already published is not checked; that belongs to publication.
