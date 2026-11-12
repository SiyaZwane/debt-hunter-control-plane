# Audit trails and squad suppressions

Part of the cross-cutting work. `README.md` lists the decisions to confirm and the gaps.

## What is recorded

| Trail | What is in it | Written by | Durable? |
|---|---|---|---|
| `ADMIN` | onboarding, changes, offboarding, grants and revocations | the administration API | yes (`audit_trail`) |
| `REIDENTIFICATION` | each request to turn a pseudonym back into a person: who, which pseudonym, why, allowed or denied; **never the identity** | `ReIdentificationService` | yes, but nothing calls it yet |
| `SUPPRESSION` | each suppression granted or withdrawn by a squad, and each refused attempt | `SquadSuppressionService` | yes |
| `EVIDENCE` | each retrieval of a run's evidence: who, which run, the stated purpose, granted / denied / not found | `EvidenceRetrievalService` | yes (`evidence_access_log`) |

Every entry is stripped of control characters and cut to a fixed length when it is made, so a request cannot forge
a second log line or bloat the trail. Recording comes **before** the thing recorded: a change that cannot be
recorded is not made.

## Reading the trails

`GET /api/v1/audit/{trail}?from=&limit=` with `trail` one of `admin`, `reidentification`, `suppression`,
`evidence`. It needs the `ControlPlane.Auditor` role, which no role that makes these changes has.

- Entries come oldest first, from `from` (default: the beginning), at most `limit` (1 to 500, default 100).
- A full page carries `nextFrom`; pass it as `from`. Entries at that instant repeat, so de-duplicate on the
  whole entry.

## Retention

Entries are kept **at least 90 days**. `retention.audit-retention` (default `P365D`) sets how long; the
application refuses to start with less than 90 days, and the housekeeping sweep removes entries older than it.
The evidence log is never purged.

## Squad suppressions

`POST /api/v1/squads/{squad}/suppressions`, body:

```json
{ "repository": "acme/widgets", "fingerprint": "…", "reason": "accepted until the module is retired",
  "expiresAt": "2026-09-01T00:00:00Z" }
```

- Needs `ControlPlane.Squad` and membership of `{squad}` in the token; the repository must be **owned by that
  squad**. Another squad's repository, a squad the caller is not in and a repository that does not exist are all
  `403`, with the same message.
- `reason`: at least 10 characters, cut at 500. `expiresAt`: in the future and no more than `maxExpiryDays`
  from now; otherwise `400`.
- `201` with the suppression and its `id`. `DELETE …/suppressions/{id}` withdraws it (`204`); another squad's
  suppression, or one that does not exist, is `404`.
- If the audit write fails, `503` and nothing is granted.
