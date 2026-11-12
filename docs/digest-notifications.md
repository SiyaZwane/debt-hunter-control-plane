# Digest notifications (CP-FR-14)

Each squad is told what changed once per period, in one message, however many findings changed. A squad
is a repository owner (`OnboardedRepository.owner`). `README.md` lists the decisions to confirm and the
gaps.

## The path

```
registry (owner → repos) ─┐
transition log window ────┼─► DigestBuilder ─► digest_ledger (built once) ─► TransactionalOutbox (kind digest-send)
suppressions, stages, ────┘                                                           │
open-finding counts                                       DigestSendHandler ◄─────────┘
                                                            │ channel + webhook secret
                                                            ▼
                                              Slack / Teams adapter ─► incoming webhook
```

Nothing a pipeline waits on is involved. The service only reads the system of record.

## Periods and windows

Periods are fixed lengths from the Unix epoch (default one day, so they close at 00:00 UTC). The first
tick in a new period *closes the one before it*:

1. It fixes which slice of the transition log that period covers (`digest_period`): from where the
   previous recorded period stopped to the newest event now. Log *positions* are used, not event times,
   because event times are commit dates and a late run would otherwise fall into a period already sent.
2. It builds each squad's digest from that slice and records it (`digest_ledger`).
3. It hands each recorded digest to the outbox.

Every step is idempotent. A tick can be repeated, run on two instances, or interrupted at any point, and
each squad still gets one digest per period. A period missed while the service was down is covered by the
next digest, which runs from the last recorded position. The very first period ever seen is a **baseline**:
it only sets a starting point, so a squad's first digest covers changes since the service started and
never all history.

## What a digest says

New findings by severity; resolved and regressed counts; suppressions ending within seven days (with the
reason and date); findings open now by severity; the number of distinct files with a new or returning
finding; up to five findings by name (returning ones first, then by severity) as rule, path, severity and
message, with a count of the rest; each repository's rollout stage (ten at most, with a count of the rest);
a link to the squad's view if one is configured; and a reminder that the full detail comes from re-running
the scan. A period in which nothing changed (no new, resolved, regressed or ending-soon) sends nothing.

Never a contributor identity: a finding carries pseudonyms only and the digest has no field that could
hold one (tested). Never source code.

Everything that came from a repository (path, message, rule, repository name, suppression reason) is
untrusted. It is cut to 200 characters, stripped of control characters, has mentions broken, has `& < >`
escaped (so `<!channel>`, `<@U123>` and links cannot act) and is put in a code span.

## Delivery

| What happened | What the digest does |
|---|---|
| Channel unreachable, timing out, 5xx, 429 | **held**: no attempt spent; sent when the channel is back, as built for its period |
| The squad has no channel, or its webhook secret cannot be found or read | **held**, with a warning in the log |
| Webhook revoked or message refused (other 4xx) | **retried** with backoff, then **set aside** with an alert |
| Digest already sent (a message handed out twice) | **skipped** |
| Unreadable job or stored digest | **set aside at once** with an alert |

## Where a squad is told

`squad_channel` holds `(owner, SLACK|TEAMS, secret reference)`. The webhook address itself is a secret and
is never stored there: `WebhookSecrets` turns the reference into the address. There is no admin endpoint or
secret-store implementation yet.

## Not wired into the application

No beans, no configuration properties, no `WebhookSecrets` implementation, no worker lifecycle. The zone
joins the application with CP-AC-93. Migration `V11__digest_notifications.sql` is already applied with the
others.
