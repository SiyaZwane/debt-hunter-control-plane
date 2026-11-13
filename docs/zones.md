# Zones

Three parts of the control plane can be switched off without affecting ingest. Ingest, the system of
record and the analytics read models always run; the zones are what sits beside them.

| Zone | Switch | Default | When it is off |
|---|---|---|---|
| Read APIs (serve) | `zones.serve.enabled` | on | no read endpoints, no beans: the paths answer 404 |
| Work-item egress | `zones.egress.enabled` | on | no worker: work waits in the system of record |
| Digests | `zones.digests.enabled` | off | no worker: nothing is built or sent |

A zone that is switched off needs none of its own configuration. A zone that is on refuses to start with
configuration that is half there, with a message naming the property: a tracker needs both `base-url` and
`authorization`, the address must be https (plain http only to this machine, so a credential is never sent
in the clear), and digests need `squad-link-base`. Egress with **no** tracker configured is allowed: it
runs, and work queues until one is.

## Stopping a zone without restarting

Each zone is a lifecycle bean (`ServeZone`, and `egressZone` and `digestZone`, which are `WorkerZone`s).
Stopping one has these effects, and no others:

- **Serve** answers 503 `{"error":"unavailable"}` on `/api/v1/portfolio`, `/squads`, `/findings`,
  `/evidence` and `/audit`. Ingestion and administration are untouched.
- **Egress and digests** stop their worker. Nothing is lost: the transition log is still written, the
  feeder's position is kept, and a message already queued stays queued. When the zone starts again
  it picks up where it stopped, and a work item is created once.

## What the tests prove

| Test | Proves |
|---|---|
| `CpAc92_ServeStopDoesNotBlockIngestTest` | with serve stopped, reads answer 503 and runs are still accepted and ingested |
| `CpAc93_EgressStopQueuesAndDeliversOnRestartTest` | with egress stopped, nothing reaches the tracker and nothing is lost; on restart the item is created, once |
| `CpAc95_StaleReadModelIsIndicatedTest` | the portfolio is fresh while the projector runs and labelled stale once it stops |
| `ZoneSwitchesTest` | defaults, off means absent, and the start-up refusals |

All of them run the whole application against PostgreSQL.

## Not in the zones

Governance publication, remediation and the model client are not wired and have no switch. The
`control-plane-ai` dependency stays banned everywhere except serve and the app.
