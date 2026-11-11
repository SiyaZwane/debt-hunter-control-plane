# Run normalisation

Everything after the intake queue works with one internal representation, `NormalisedRunBundle`,
and never sees a schema version. `RunNormaliser` is the only place that knows more than one schema
exists.

```
queue → RunBundleReceiver (de-duplicate) → NormalisingBundleHandler → RunNormaliser → NormalisedRunSink
                                                    └── cannot ever normalise ──→ QuarantineSink
```

## What comes out

`NormalisedRunBundle` has three parts:

| Part | Contents |
|---|---|
| `scan` | The run, findings, metrics, and policy outcome as the shared domain model (`ScanResult`), so the control plane and data plane cannot disagree about what a finding is |
| `provenance` | The correlation id, the authenticated workload identity, when it was received, and the schema version it arrived in |
| `report` | What normalisation had to do beyond copying: `defaultedFields` and `warnings` |

> **A normalised bundle still carries raw contributor identity** (finding evidence can name authors).
> It must pass through the pseudonymiser (CP-FR-03) before it reaches any store.

## Schema bridges

The bundle's own `schemaVersion` selects a `SchemaBridge` by major version. Supporting a new schema
means adding a bridge and registering it; nothing existing changes.

| Major | Bridge | Behaviour for a field the current schema has and the bundle lacks |
|---|---|---|
| 1 (current) | `CurrentSchemaBridge` | Refused: the bundle is malformed |
| 0 (N-1) | `PreviousSchemaBridge` | Defaulted, and recorded in the report |

The previous-version bridge is **provisional**, like the v0 schema it reads: the data plane has only
ever emitted 1.x, so there is no real v0 to be faithful to.

### Three rules for mapping

1. **Present in both versions** — mapped directly.
2. **In the old version, loosened or removed in the new** — mapped to the closest equivalent, with a
   warning in the report and the original value kept so nothing is lost:
   - A finding `category` outside the current closed set becomes `CUSTOM`; the original text is kept
     in the finding's `evidence.originalCategory`.
   - A free-text policy reason becomes a violation whose `rule` is the text and whose `threshold`
     and `actual` are `unknown`.
3. **In the new version only** — set to a documented default, and recorded in the report.

### Defaults

Defined in `NormalisationDefaults`; every use is recorded under the field's name.

| Field | Default | Why |
|---|---|---|
| `findings[].confidence` | `1.0` | Findings from before confidence existed came from deterministic engines, which have no notion of "unsure" |
| `findings[].score` | `0.0` | Unscored, not low-scored |
| `findings[].isNew` | `false` | Claims nothing; newness is decided afresh by the lifecycle state machine from the fingerprint |
| `findings[].evidence` | `{}` | |
| `metrics` | `{}` | |
| `policy.bundleVersion` | `"unknown"` | |
| `run.degraded` | derived: any engine not `OK` | Not an arbitrary default: it follows from the engine list the bundle does carry |

`confidence = 1.0` is the one judgement call: a consumer that weights by confidence should check
`report.wasDefaulted("findings[].confidence")` before trusting it.

### The report is aggregated

Entries are counted per field (`findings[].confidence × 5000`), not listed per finding, so a report's
size is bounded by the schema, not by how many findings a bundle has.

## Bundles that can never be normalised

`UnnormalisableBundleException` is the **permanent** failure: not JSON, a repeated key, no
`schemaVersion`, a major with no bridge, a field of the wrong type, an unparseable timestamp, or (for
the current schema) a required field missing. Its message names the field and never echoes the value,
because a bundle can carry anything.

The two failure kinds are handled differently on purpose:

- **Permanent** → `NormalisingBundleHandler` sends the bundle to the `QuarantineSink` and returns
  normally. Throwing would make the queue listener hold and retry it forever, blocking every bundle
  behind it in that partition.
- **Anything else** (the next stage being down) → propagates, and the bundle is retried.

If the quarantine itself fails, that propagates too: dropping the bundle would lose it.

## Volatile fields

`VolatileFieldStripper` removes `run.id`, `run.timestamp`, and each `run.engines[].durationMs` — the
fields that differ between two runs of identical code. It works on the JSON layout a bundle shares
with a serialised `ScanResult`, so it applies to either. This is what "the same run in either schema
version normalises to equivalent output" (CP-AC-08) means: identical once those are stripped.

## What is not wired yet

`NormalisedRunSink` and `QuarantineSink` are interfaces with no production implementation, so the
application still does not start the queue listener. The sink would be the pseudonymiser (CP-FR-03)
followed by the store (CP-FR-05); starting a consumer before then would either drop bundles or write
raw contributor identity. Accepted bundles keep waiting safely on the topic.

## Known gap: a quarantined run id is spent

`RunBundleReceiver` marks a run processed once the handler returns normally, and quarantining returns
normally. A corrected bundle resubmitted under the **same `run.id`** would therefore be discarded as
a duplicate. Fixing it properly needs the idempotency guard (CP-FR-04) to distinguish "processed"
from "set aside".
