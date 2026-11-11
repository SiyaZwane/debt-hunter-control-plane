# Coverage and adoption tracking

**Silence is a finding.** A repository that has stopped reporting looks exactly like a clean one, and
a repository nobody onboarded has no findings simply because nobody is looking. Coverage tracking
exists so neither is ever mistaken for "healthy".

It reconciles the **repository registry** against the **source-control estate** and gives every
repository in either exactly one state.

## The five states

| State | Meaning |
|---|---|
| `REPORTING` | Onboarded, a run received within the cadence window, usable data, current tool version |
| `SILENT` | Onboarded, but no run received within the cadence window, or ever |
| `NEVER_ONBOARDED` | In the estate, absent from the registry: nobody is watching it |
| `DEGRADED_REPORTING` | Reporting, but its latest runs are consistently degraded or analysed with shallow history |
| `VERSION_DRIFT` | Reporting, but the tool version is not the current major version |

When several conditions apply the most serious wins: **`SILENT` > `DEGRADED_REPORTING` >
`VERSION_DRIFT` > `REPORTING`**. A silent repository's other conditions are judged on stale data, so
`SILENT` stands alone. The `reasons` list on each repository still names everything that applies.

Only `REPORTING` counts as healthy. `CoverageReport.healthy()` never includes a repository that was
never onboarded, and `healthyShare()` is the share of **all** known repositories, not of the onboarded
ones.

## Definitions

- **Silent**: the latest run was received more than `cadence` ago (default 7 days; exactly `cadence`
  ago is still reporting). "Received" is when the control plane got the bundle, not the commit date;
  a pipeline that only runs on pull requests is still reporting.
- **Consistently degraded**: each of the latest `degraded-window` runs (default 3) was degraded **or**
  analysed with `SHALLOW` history. With fewer runs than the window it cannot be called consistent, so
  a brand-new repository is not flagged until it has that many. `PARTIAL` history is not shallow.
- **Version drift**: the latest run's tool version has a different **major** from
  `current-tool-major`. A version that cannot be parsed counts as drift, because it cannot be judged
  current.
- Repositories are matched **case-insensitively**, as GitHub and Azure DevOps names are.

## Reading the estate

`SourceControlEstateProvider` has two real implementations, both over HTTP with bounded timeouts:

| | GitHub | Azure DevOps |
|---|---|---|
| Call | `GET /orgs/{org}/repos?per_page=100&type=all`, following `Link: rel="next"` | `GET /{org}/_apis/git/repositories?api-version=7.1` |
| Auth | `Authorization: Bearer <token>` | Basic, personal access token as the password of an empty user |
| Identifier | `owner/name` | `project/name` |
| Left out | archived, disabled | disabled |

Safeguards, each covered by a test against a real local HTTP server:

- **A pagination link to a different host is refused**, so a hostile or misconfigured response cannot
  make the provider send the token elsewhere.
- **Redirects are never followed**, for the same reason.
- Error messages name the situation ("rejected the credentials (HTTP 401)") but **never contain the
  token or the response body**.
- A response that is not a repository list is `unavailable`, never "empty".

### When the estate cannot be consulted

If the listing fails, the report says so (`estateAvailable: false`) and covers only the onboarded
repositories. It does **not** treat a failed listing as an empty estate: that would silently claim no
repository was ever missed. The same applies when no estate is configured, and a listing that comes
back empty is accepted but carries a warning, since that is more often bad credentials than a truly
empty organisation.

A repository that is onboarded but not found in the estate (renamed, archived, deleted) keeps its
run-based state, is marked `inEstate: false`, and is named in the warnings.

## Schedule and storage

Reconciliation runs on its own thread, nightly by default, and a failure is logged and retried on the
next schedule. The latest report is stored (replaced atomically), so asking for it never calls the
source-control host. `CoverageTracker.latest()` returns it with a `stale` flag (default: older than 36
hours, or never reconciled). A reconciliation that finds uncovered repositories logs at `WARN`.

## Configuration

Under `debthunter.controlplane.analytics.coverage.`:

| Property | Default | |
|---|---|---|
| `enabled` | `true` | |
| `reconcile-interval` | `P1D` | |
| `initial-delay` | `PT1M` | first run after start-up |
| `cadence` | `P7D` | silence threshold |
| `current-tool-major` | `0` | the data plane is `0.x` today; set `1` when it ships `1.0` |
| `degraded-window` | `3` | |
| `stale-after` | `PT36H` | |
| `estate.provider` | `none` | `none`, `github` or `azure` |
| `estate.organization` | | the organisation to list |
| `estate.token` | | GitHub token or Azure DevOps PAT |
| `estate.api-url` | public API | for GitHub Enterprise or a proxy |
| `estate.timeout` | `PT20S` | |

## Not done

- **No HTTP API for coverage.** The report is library code and a stored read model, like the other
  analytics; the serve layer (CP-FR-15/16) will expose it. Until then the only outputs are the log
  lines and the database.
- **No history.** Only the latest report is kept, so "when did this repository go silent?" cannot be
  answered, and nothing is raised when a state changes.
- **The estate providers are verified only against fakes of the two APIs**, written from their
  documented behaviour. Neither has been run against the real GitHub or Azure DevOps.
- **The estate token comes from configuration**, like the other interim secrets, not Key Vault.
- **GitHub only lists organisations**, not user accounts, and Azure DevOps has no pagination handling
  (the endpoint returns everything).
- **The identifier must match.** A repository is matched by the registry's identifier against the
  estate's `owner/name`. The data plane still reports a local path as `run.repository`, so a registry
  entry has to use the estate's name for this to line up. See the repository-identity gap.
- `Silent` is judged by the wall clock ("received N days ago"), unlike the lifecycle, which uses
  commit dates.
- Runs recorded before this change have no reception time; they fall back to their commit date.
