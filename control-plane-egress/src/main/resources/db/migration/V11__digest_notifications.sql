-- Digest notifications: where each squad is told, what each period's window of the transition log
-- was, and the digest built for each squad and period.

-- A squad is a repository owner. The secret reference names the webhook; the webhook address itself
-- is a secret and is never stored here.
CREATE TABLE squad_channel (
    owner       TEXT PRIMARY KEY,
    kind        TEXT NOT NULL CHECK (kind IN ('SLACK', 'TEAMS')),
    secret_ref  TEXT NOT NULL
);

-- The slice of the transition log each period covers, fixed when the period is first closed so a
-- rerun builds the same digest.
CREATE TABLE digest_period (
    period_start  TIMESTAMPTZ PRIMARY KEY,
    period_end    TIMESTAMPTZ NOT NULL,
    from_seq      BIGINT      NOT NULL,
    to_seq        BIGINT      NOT NULL,
    baseline      BOOLEAN     NOT NULL
);

-- One row per squad per period. The payload is the digest as built (null when there was nothing to
-- say), so late delivery shows the original period and a retry never rebuilds it.
CREATE TABLE digest_ledger (
    owner         TEXT        NOT NULL,
    period_start  TIMESTAMPTZ NOT NULL,
    payload       TEXT,
    queued_at     TIMESTAMPTZ,
    sent_at       TIMESTAMPTZ,
    PRIMARY KEY (owner, period_start)
);
