# Work items (CP-FR-13)

When a finding becomes something a team should act on, the control plane makes one work item for it in
the team's tracker (Jira or Azure Boards), keeps that item in line with the finding, and never makes a
second. `README.md` lists the decisions to confirm and the gaps.

## The path

```
transition log ──► WorkItemFeeder ──► work_queue (kind work-item-sync) ──► WorkItemEgressWorker
 (written first,    checkpointed,       ordered per finding,                    │ TransactionalOutbox.drain
  read-only here)   read-only          dedup = event id                         ▼
                                                              WorkItemSyncHandler ──► WorkItemOrchestrator
                                                                                          │ reads the finding's state NOW
                                                                  work_item_link ◄────────┤ route, stage, severity
                                                                                          ▼
                                                                              WorkItemTracker (Jira / Azure Boards)
```

Nothing a pipeline waits on is involved. Ingest writes the transition log as it always did; the feeder
only reads it. If egress is stopped, or a tracker is down, jobs queue and are delivered later.

- **The transition log is the outbox source.** It is made durable before anything depends on it, and the
  feeder reads it forward from a saved position (`egress_checkpoint`). If a job cannot be queued the
  position is not moved, so the event is offered again; a job carries its event id as an idempotency key,
  so offering it twice does not queue it twice.
- **The orchestrator reconciles; it does not replay.** A job says "look at this finding". The orchestrator
  reads the finding's *latest* recorded state and does the one thing that brings the tracker into line, so
  a repeated job, or a job about an old event after a newer one, does nothing wrong. It reads the log, not
  the finding-state table, because the log is written first.
- **One job at a time per finding.** The queue kind is ordered on `repository|slice|fingerprint`.

## What happens to a finding

| The finding is… | The item… |
|---|---|
| seen for the first time (`NEW`) | nothing yet |
| `OPEN` or `REGRESSED`, no item | is created, if the repository is at stage 3 or later and the severity is at least the threshold (default `HIGH`) |
| `OPEN` or `REGRESSED`, item exists | is reopened if it is not open |
| `RESOLVED` | is closed |
| `SUPPRESSED` | is put on hold, with the suppression's reason and end date in a comment |
| `OPEN` again after a suppression ends | is reopened |

Only *creating* an item is gated by stage and severity. An item that exists keeps being kept in line, even
if its repository is later moved back a stage or the finding's severity changes.

## One item per fingerprint

Three things, in order, before the tracker is asked to create anything:

1. `work_item_link` has at most one row per finding. The row is **reserved** first.
2. The right to write for that finding is **claimed** (a lease on the row). A second worker, or one that
   stalled past its lease, finds the claim taken and waits (the message is held), so two workers cannot both
   create or both move the same item.
3. The tracker is **searched for the finding's key** (`dh-` plus a hash of repository, slice and
   fingerprint, kept as a Jira label or an Azure Boards tag). An item created by an attempt that died
   before recording it is found and adopted, not created again.

## Failure handling (`TransactionalOutbox`)

| What happened | What the message does |
|---|---|
| Tracker unreachable, timing out, 5xx, 429, or refusing our credentials | **held**: put back, no attempt counted, never set aside because of this |
| No routing entry for the repository, or no adapter for its tracker | **held**, with a warning in the log |
| Another worker holds the finding; the system of record is down | **held** |
| The tracker answers and refuses (no such project, no such transition) | **retried** with exponential backoff, then **set aside** |
| The message cannot be read, or the finding is not in the run that reported it | **set aside at once** |
| Set aside for any reason | an **alert** is raised (`OperationsAlertPublisher`) and the message is kept, never discarded |

A wrong credential holds work rather than throwing it away: it is not the item's fault and fixing the
credential should be enough to release it.

## What goes in an item

The rule, severity, category, file and line, the finding's message, and a link to the run if one is
configured. **Never** a contributor identity (the finding carries pseudonyms only and they are not used)
and never source code. Items are not assigned to anyone. Everything that came from the repository is
untrusted text: it is cut to a length, stripped of control characters, has mentions neutralised, and is
rendered safely for the tracker (HTML-escaped for Azure Boards; markup characters removed for Jira).

## The tracker adapters

`WorkItemTracker` is three operations: find an item by key, create one, move one to a state (open, on
hold, closed) with a comment. It cannot delete an item, assign it, or edit what people wrote. Both
adapters use the JDK HTTP client, follow no redirects, put the credential only in a header, and refuse a
project name or item id that could change the address.

Workflows differ between projects, so the three states are *names* given to each adapter
(`StateNames`): Jira defaults to `To Do` / `On Hold` / `Done`, Azure Boards to `Active` / `On Hold` /
`Closed`. They must match the project's workflow.

Both adapters have only been run against local imitations of the services.

## Not wired into the application

Nothing here is a bean yet: no tracker credentials or addresses, no worker lifecycle, no configuration
properties. The zone joins the application with the Zone F "stop and the delivery queues" test (CP-AC-93).
The migration (`V10__work_item_egress.sql`) is already applied with the others.
