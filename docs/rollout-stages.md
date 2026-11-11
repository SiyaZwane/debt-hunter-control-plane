# Rollout stage management (CP-FR-10)

Where each repository is in adopting the tool, how it moves, and what happens when it stays too long.
`README.md` lists the decisions to confirm and the gaps.

## Model

`RolloutStage` is `STAGE_1` to `STAGE_4`, strictly ordered. Stage 1 only observes; work items are created
from stage 3 (`createsWorkItems()`).

A `StageRecord` holds the current stage, when it began, the named owner, the evidence that was relied on
to enter it, an optional approved exemption from the maximum hold, and a marker that this stay has been
escalated. Every change also writes a `StageEvent` (enrolled, advanced, regressed, exemption granted) in
an append-only log protected by a database trigger. A record change and its event are written in one
transaction, and a change is refused if the repository is no longer at the stage it assumed, so two
approvers cannot both advance one repository.

## StageManager

| Method | Does |
|---|---|
| `enrol` | Puts a repository at stage 1 with a named owner |
| `advance` | One stage forward; needs every exit criterion of the stage being left, each with evidence |
| `regress` | To an earlier stage; needs a reason; starts a new stay |
| `grantExemption` | Approves staying past the maximum hold until a date |
| `isWorkItemCreationEnabled` | True only if enrolled and at stage 3 or later; throws if the store is unreadable |
| `escalateOverdue(now)` | Sweeps for stays past their maximum hold, delivers to a `StageEscalationSink`, marks them |

`StagePolicy` supplies the exit criteria and maximum hold per stage. Its builder refuses a policy that
does not say what leaving each non-final stage requires.

Refusals (`StageOutcome.Refused`) carry a reason and write nothing: not enrolled, already enrolled,
missing criteria (naming them), no next stage, not an earlier stage, dated before the current stage, or
a lost race.

## Escalation

A stay is escalated once. After the sink accepts it the record is marked; a failed delivery leaves it
unmarked and it is retried next sweep while other repositories are still processed. Moving to any other
stage starts a new stay and clears the marker and any exemption.

## Reports

`CoverageTracker` (analytics) takes a read-only `StageLookup` and every `RepositoryCoverage` carries
`rolloutStage` (null if not in the rollout). The stage is stored with the report (migration V8), so a
stored report still shows it.

## How the acceptance criteria were read

| AC | Test | Meaning used |
|---|---|---|
| CP-AC-48 | `CpAc48_Stage1NoWorkItemTest` | A stage-1 (or unenrolled) repository has work-item creation disabled, and a refused advance does not open it |
| CP-AC-49 | `CpAc49_Stage3GatesAndCreatesWorkItemTest` | The gate is closed at stages 1 and 2 and open at stage 3, per repository, from PostgreSQL. Creating the item is CP-FR-13 |
| CP-AC-50 | `CpAc50_TransitionEventRecordedTest` | A transition is stored with approver, date and criteria, survives a new instance, and a race has one winner |
| CP-AC-51 | `CpAc51_MaxHoldEscalationTest` | Escalated once to the owner past the maximum hold, across restarts; an exemption holds it back |
| CP-AC-52 | `CpAc52_AdoptionReportIncludesStageTest` (serve) | The coverage report shows each repository's stage, including when read back from PostgreSQL |
