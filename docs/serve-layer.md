# The serve layer (CP-FR-15, CP-FR-16)

Read APIs over the system of record: a portfolio for leaders, a view for each squad, a programmatic
query API, and retrieval of a run's evidence. They are JSON over HTTP; there is no UI. `README.md` lists
the decisions to confirm and the gaps.

## Who may see what

A token carries `roles` and `squads` claims (a squad is a repository owner):

| Role | May | Never |
|---|---|---|
| `ControlPlane.Leader` | the portfolio: one row per team, no repository names | any finding, repository or evidence |
| `ControlPlane.Squad` | the squad view and the query API, for the squads in the token | another squad's repositories, whether or not they exist |
| `ControlPlane.Evidence` | a run's evidence, for runs of the squads in the token | a run of another squad (it looks like a run that does not exist) |

Access is checked **in the services**, not only the controllers. Every public method of a service takes a
`Caller` and checks it before reading anything (an ArchUnit rule enforces that the `Caller` parameter is
there). A controller added later cannot widen access.

## Endpoints

| Path | Service | Notes |
|---|---|---|
| `GET /api/v1/portfolio` | `PortfolioDashboardService` | `generatedAt`, `readModelsAsOf`, `stale`, `coverageAsOf`, `teams[]` |
| `GET /api/v1/squads/{squad}` | `SquadViewService` | repositories with tier, stage, coverage, open findings by severity |
| `GET /api/v1/squads/{squad}/findings` | `SquadViewService` | filtered, paged; each finding carries `detail` and `evidence` links |
| `GET /api/v1/squads/{squad}/finding?repository=&run=&id=` | `SquadViewService` | a finding with its explanation and who or what wrote it |
| `GET /api/v1/findings` | `QueryService` | the query API over all the caller's squads |
| `GET /api/v1/evidence/runs/{runId}?purpose=` | `EvidenceRetrievalService` | needs a stated purpose; every call is recorded |

Query filters: `repository`, `severity`, `category`, `state`, `from`, `to` (the commit date of the latest
run that reported the finding; ISO-8601), `limit` (1 to 200, default 50) and `cursor`. Anything that does
not parse is `400 invalid_request`. Errors are `{ "error": "<code>", "message": "..." }` with codes
`forbidden` (403), `not_found` (404), `invalid_request` (400), `unavailable` (503), `internal_error` (500).

## Freshness

- **Query API and squad view** read the system of record directly (a finding's current state joined to the
  latest run that reported it), so they are exact. Every response carries `asOf` (when the newest run it
  reflects was received) and `generatedAt`.
- **The portfolio** reads the analytics read models, which can lag. It carries `readModelsAsOf` and `stale`
  (true if older than the threshold, or if they never ran). Stale numbers are labelled, not hidden.

Paging is by **key cursor** (repository, slice, fingerprint), not offset, so findings that appear or
resolve between requests cannot make a page repeat or skip one.

## Evidence retrieval

1. The caller needs the evidence role and a purpose of 10 to 500 characters.
2. The run is looked up and the decision made: granted, denied (another squad's), or not found.
3. **The request is recorded first** (`evidence_access_log`: time, caller, run, repository, purpose,
   outcome). If it cannot be recorded, nothing is released (503).
4. Only then is the evidence assembled: run metadata, the findings, the retained evidence, the policy bundle
   and the outcome.

Denied and not-found look the same to the caller (404); the record keeps the difference.

Evidence comes from whichever tier holds it. Detail ages out of the hot tier into daily summaries, so an old
run returns `tier: WARM` with the summaries, not detail that no longer exists. `NONE` means nothing is
retained.

**Policy.** Each run now records the policy bundle version it was assessed against (`run.policy_bundle_version`,
migration V12), and the digest is looked up in the catalogue, which keeps superseded versions. So a run is
explained by the policy that applied to it, not the one in force now. Runs received before this was recorded
return `policy.known: false` rather than a guess. The outcome is the status only (`PASSED`, `FAILED`,
`WOULD_FAIL`); the reasons behind a failure are not retained.

## No identity

None of the response types has a field for a contributor, even as a pseudonym, and the finding rows are read
without the contributors column. Tests check this by reflection and over the JSON each endpoint returns.

## Not wired into the application

No beans, no configuration, no worker. `ServeApiConfiguration` wires the controllers over services the
application would supply, and the integration tests assemble that in a real Spring context with the real
security chain. Nothing sets the `squads` claim: it has to come from the identity provider.
