# Administration API

How an administrator decides **who the control plane serves**: which repositories are onboarded, where
their work items go, and which pipelines may publish for them. Until a repository is onboarded and a
pipeline granted it, every publish for it is refused.

All routes are under `/api/v1/admin` and need a bearer token.

## Who may call it

The token must carry the administrator role in its `roles` claim (default `ControlPlane.Admin`;
configurable with `security.admin-role` / `CONTROL_PLANE_ADMIN_ROLE`). With Entra, assign it as an
app role to the people or service principals who administer the control plane.

A pipeline's token is valid and authenticated, and still gets **403 on every admin route**. That is
the point: if it did not, any pipeline could grant itself access to any repository. The reverse holds
too: an administrator is not a pipeline, and cannot publish unless granted a repository like anyone
else. The check is made by the security chain, before the controller runs, and a test hits every route
with a pipeline token.

| Caller | Admin routes | `POST /api/v1/runs` |
|---|---|---|
| No token | 401 | 401 |
| Pipeline token | 403 | allowed for granted repositories only |
| Administrator token | allowed | 403 unless granted |

## Routes

### Repositories

| Method and path | Does | Success | Errors |
|---|---|---|---|
| `POST /repositories` | onboard | `201`, `Location`, the entry | `409 already_onboarded`, `400 invalid_request` |
| `GET /repositories` | list all, ordered | `200 {"repositories": [...]}` | |
| `GET /repositories/{id}` | read one | `200` | `404 not_onboarded` |
| `PUT /repositories/{id}` | change an entry | `200` | `404`, `400` |
| `DELETE /repositories/{id}` | offboard (also withdraws **every** grant for it) | `204` | `404` |

`{id}` is the repository identifier and **may contain slashes**: `/repositories/acme/widgets`,
`/repositories/org/team/repo`. On `PUT`, a `repository` field in the body is optional but must match
the URL.

```json
{
  "repository": "acme/widgets",
  "owner": "team-a",
  "tier": "tier-1",
  "policyBundleVersion": "1.0",
  "rolloutStage": "enforce",
  "route": { "tracker": "jira", "target": "WID" }
}
```

### Workload grants

| Method and path | Does | Success | Errors |
|---|---|---|---|
| `PUT /grants?workloadIdentity=…&repository=…` | let a pipeline publish for a repository | `204` (idempotent) | `404` if the repository is not onboarded |
| `DELETE /grants?workloadIdentity=…&repository=…` | withdraw that | `204` (idempotent) | |
| `GET /grants?workloadIdentity=…` | list a pipeline's repositories | `200 {"workloadIdentity", "repositories"}` | |

`workloadIdentity` is the `sub` claim of the pipeline's token.

### Example

```bash
BASE=https://control-plane.example.com/api/v1/admin
AUTH="Authorization: Bearer $ADMIN_TOKEN"

curl -X POST "$BASE/repositories" -H "$AUTH" -H 'Content-Type: application/json' -d '{
  "repository":"acme/widgets","owner":"team-a","tier":"tier-1",
  "policyBundleVersion":"1.0","rolloutStage":"enforce",
  "route":{"tracker":"jira","target":"WID"}}'

curl -X PUT "$BASE/grants?workloadIdentity=svc-pipeline&repository=acme/widgets" -H "$AUTH"
```

## Errors

Machine-readable, like the ingestion API: `{"error": "<stable code>", "message": "<for humans>"}`.

| Status | `error` | Meaning |
|---|---|---|
| `400` | `invalid_request` | Malformed or invalid input; the message names the field and never echoes the value |
| `404` | `not_onboarded` | The repository is not onboarded |
| `409` | `already_onboarded` | Onboarding something already onboarded (it is never silently overwritten) |
| `500` | `internal_error` | Unexpected, including a failure to write the audit trail; no detail leaks |

## Input is validated strictly, not cleaned up

Every text field must be present, at most 200 characters, free of control characters, and **must not
start or end with whitespace**. A padded value is rejected rather than quietly trimmed: silently
altering an identifier is how two people end up administering different things under one name.

## Audit

Every change (onboard, update, offboard, grant, revoke) is recorded with **who** (the token's `sub`),
**what**, **to what**, and **when**, to the logger `com.debthunter.controlplane.audit.admin`, so it can
be routed to its own append-only sink. Request-supplied text has control characters stripped so it
cannot forge a second line.

- The record is written **before** the change is applied, and **if it cannot be written the change is
  not made** (500). An unrecorded change to who may publish is worse than a refused one.
- A rejected attempt (a 409, a 404) is still recorded, because it was an attempt.
- A request that fails validation is not recorded, because nothing was attempted.
- Reads are not audited.

## Not done

- **The audit trail is a log line, not a table.** It is only as durable as wherever the log goes. A
  durable, queryable audit store is the natural next step.
- **No pagination or filtering on the list**, which is fine for hundreds of repositories, not for
  tens of thousands.
- **No bulk onboarding** and no way to import from a source of truth (GitHub org, CMDB).
- **Policy, suppressions, baselines and the rest of the system of record have no admin routes yet**;
  only the registry does.
- **The role comes from the token and nothing else**: there is no second factor, and no separation
  between "may onboard" and "may grant". One role does both.
- Repository identifiers that begin with `/` (the data plane's local-path identifiers today) are
  awkward in a URL path, since the leading slash makes a double slash. The identifier is meant to
  become a real one such as `owner/name`.
