-- A durable work queue in the system of record. Intake of run bundles uses it, and so will other
-- background work. An item's payload is erased when it is finished; only its bookkeeping stays
-- until purged.
CREATE TABLE work_queue (
    id           BIGSERIAL PRIMARY KEY,
    kind         TEXT        NOT NULL,
    queue_key    TEXT        NOT NULL,
    dedup_key    TEXT,
    payload      BYTEA       NOT NULL,
    status       TEXT        NOT NULL CHECK (status IN ('PENDING', 'IN_PROGRESS', 'DONE', 'DEAD')),
    attempts     INTEGER     NOT NULL DEFAULT 0,
    enqueued_at  TIMESTAMPTZ NOT NULL,
    not_before   TIMESTAMPTZ NOT NULL,
    lease_until  TIMESTAMPTZ,
    leased_by    TEXT,
    last_error   TEXT,
    finished_at  TIMESTAMPTZ
);

CREATE INDEX work_queue_claim ON work_queue (kind, status, id);
CREATE INDEX work_queue_key ON work_queue (kind, queue_key, id) WHERE status IN ('PENDING', 'IN_PROGRESS');
CREATE UNIQUE INDEX work_queue_dedup ON work_queue (kind, dedup_key)
    WHERE dedup_key IS NOT NULL AND status IN ('PENDING', 'IN_PROGRESS');
CREATE INDEX work_queue_finished ON work_queue (status, finished_at) WHERE status IN ('DONE', 'DEAD');
